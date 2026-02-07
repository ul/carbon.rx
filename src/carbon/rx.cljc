(ns carbon.rx
  (#?(:clj :require
      :cljs :require-macros)
   [carbon.macros :as macros])
  #?(:clj (:import [clojure.lang IDeref IMeta IAtom IRef]
                   [java.util Collections WeakHashMap]))
  #?(:clj (:refer-clojure :exclude [dosync])))

(defprotocol IReactiveSource
  (get-rank [_])
  (add-sink [_ sink])
  (remove-sink [_ sink])
  (get-sinks [_]))

(defprotocol IReactiveExpression
  (compute [_])
  (computed? [_])
  (gc [_])
  (add-source [_ source])
  (remove-source [_ source]))

(defprotocol IReactiveDrop
  (add-drop [_ key f])
  (remove-drop [_ key])
  (notify-drops [_]))

(def ^:dynamic *rx* nil)                                    ; current parent expression
(def ^:dynamic *value* nil)                                 ; previous value of expression
(def ^:dynamic *rank* nil)                                  ; highest rank met during expression compute
(def ^:dynamic *dirty-sinks* nil)                           ; subject to `compute`
(def ^:dynamic *dirty-sources* nil)                         ; subject to `gc`
(def ^:dynamic *provenance* [])

(def ^:dynamic *cycle-detection*
  #?(:clj *assert*
     :cljs js/goog.DEBUG))

;; ---------------------------------------------------------------------------
;; Rank-bucketed priority queue: sorted-map rank → #{nodes}
;; ---------------------------------------------------------------------------

(def empty-queue (sorted-map))

(defn- queue-add
  "Add a single reactive node to the queue, bucketed by its rank."
  [q node]
  (let [r (get-rank node)] (update q r (fnil conj #{}) node)))

(defn- queue-into
  "Add all `nodes` to the queue."
  [q nodes]
  (reduce queue-add q nodes))

(defn- queue-pop
  "Remove and return [node, queue'] for the lowest-rank node."
  [q]
  (when-let [[r bucket] (first q)]
    (let [node (first bucket)
          bucket' (disj bucket node)]
      [node (if (empty? bucket') (dissoc q r) (assoc q r bucket'))])))

(defn- queue-rseq
  "Return a seq of all nodes in descending rank order."
  [q]
  (mapcat val (rseq q)))

(defn propagate
  "Recursively compute all dirty sinks in the `queue` and return all visited sources to clean."
  [queue]
  (binding [*rx* nil
            *rank* nil] ; try to be foolproof
    (loop [queue queue
           dirty '()]
      (if-let [[x queue'] (queue-pop queue)]
        (recur (if (= @x (compute x)) queue' (queue-into queue' (get-sinks x)))
               (conj dirty x))
        dirty))))

(defn clean
  "Recursively garbage collect all disconnected sources in the `queue`"
  [sources]
  (doseq [source sources] (gc source)))

(defn register
  [source]
  (when *rx*
    (add-sink source *rx*)
    (add-source *rx* source)
    (vswap! *rank* max (get-rank source))))

(defn dosync*
  [f]
  (let [sinks (or *dirty-sinks* (atom empty-queue))
        sources (or *dirty-sources* (atom empty-queue))
        result (binding [*dirty-sinks* sinks *dirty-sources* sources] (f))]
    ;; top-level dosync*
    (when-not *dirty-sinks*
      (binding [*dirty-sources* sources]
        (swap! *dirty-sources* queue-into (propagate @sinks))))
    ;; top-level dosync*
    (when-not *dirty-sources* (clean (queue-rseq @sources)))
    result))

#?(:cljs
     (defn safe-realized? [x] (if (implements? IPending x) (realized? x) true)))

#?(:cljs (defn fully-realized?
           [form]
           (if (seqable? form)
             (and (safe-realized? form) (every? fully-realized? form))
             (safe-realized? form))))

(deftype ReactiveExpression [getter setter metadata validator drop-handlers
                             state watches rank sources sinks]
  IDeref
    (#?(:clj deref
         :cljs -deref)
      [this]
      (when-not (computed? this) (compute this))
      (register this)
      @state)
  IReactiveSource
    (get-rank [_] @rank)
    (add-sink [_ sink] (vswap! sinks conj sink))
    (remove-sink [_ sink] (vswap! sinks disj sink))
    (get-sinks [_] @sinks)
  IReactiveExpression
    (computed? [this] (not= @state ::thunk))
    (compute [this]
      (doseq [source @sources] (remove-sink source this))
      (vreset! sources #{})
      (when (and *cycle-detection* (some #(identical? this %) *provenance*))
        (throw (ex-info (str
                          "carbon.rx: detected a cycle in computation graph!\n"
                          (pr-str (map meta *provenance*)))
                        {:provenance *provenance*})))
      (let [old-value @state
            r (volatile! 0)
            new-value (binding [*rx* this
                                *value* old-value
                                *rank* r
                                *provenance* (conj *provenance* this)]
                        (getter))]
        (vreset! rank (inc @r))
        (when (not= old-value new-value)
          (vreset! state new-value)
          (doseq [[key f] @watches] (f key this old-value new-value)))
        new-value))
    (gc [this]
      (if *dirty-sources*
        (swap! *dirty-sources* queue-add this)
        (when (and (empty? @sinks) (empty? @watches))
          (doseq [source @sources]
            (remove-sink source this)
            (when (satisfies? IReactiveExpression source) (gc source)))
          (vreset! sources #{})
          (vreset! state ::thunk)
          (notify-drops this))))
    (add-source [_ source] (vswap! sources conj source))
    (remove-source [_ source] (vswap! sources disj source))
  IReactiveDrop
    (add-drop [this key f] (vswap! drop-handlers assoc key f) this)
    (remove-drop [this key] (vswap! drop-handlers dissoc key) this)
    (notify-drops [this] (doseq [[key f] @drop-handlers] (f key this)))
  IMeta
    (#?(:clj meta
         :cljs -meta)
      [_]
      metadata)
  #?@(:clj [IRef (setValidator [_ _]) (getValidator [_] validator)
            (getWatches [_] @watches)
            (addWatch [this key f]
              (when-not (computed? this) (compute this))
              (vswap! watches assoc key f)
              this)
            (removeWatch [this key] (vswap! watches dissoc key) (gc this) this)
            IAtom (swap [this f] (macros/no-rx (reset! this (f @this))))
            (swap [this f x] (macros/no-rx (reset! this (f @this x))))
            (swap [this f x y] (macros/no-rx (reset! this (f @this x y))))
            (swap [this f x y xs]
              (macros/no-rx (reset! this (apply f @this x y xs))))
            (compareAndSet [this oldval newval]
              (locking state
                (if (= oldval @state) (do (reset! this newval) true) false)))
            (reset [_ new-value]
              (assert setter "Can't reset lens w/o setter")
              (when-not (nil? validator)
                (assert (validator new-value)
                        "Validator rejected reference state"))
              (dosync* #(setter new-value))
              new-value) Object
            (toString [_]
              (let [v @state]
                (if (= v ::thunk)
                  "#<RX: :thunk>"
                  (str "#<RX: " (pr-str v) ">")))) #_IReference ;; TODO
                                                                ;; alterMeta,
                                                                ;; resetMeta
           ]
      :cljs
        [IWatchable
         (-notify-watches [this oldval newval]
           (doseq [[key f] @watches] (f key this oldval newval)))
         (-add-watch [this key f]
           (when-not (computed? this) (compute this))
           (vswap! watches assoc key f)
           this)
         (-remove-watch [this key] (vswap! watches dissoc key) (gc this) this)
         IReset
         (-reset! [_ new-value]
           (assert setter "Can't reset lens w/o setter")
           (when-not (nil? validator)
             (assert (validator new-value)
                     "Validator rejected reference state"))
           (dosync* #(setter new-value))
           new-value) ISwap
         (-swap! [this f] (macros/no-rx (reset! this (f @this))))
         (-swap! [this f x] (macros/no-rx (reset! this (f @this x))))
         (-swap! [this f x y] (macros/no-rx (reset! this (f @this x y))))
         (-swap! [this f x y xs]
           (macros/no-rx (reset! this (apply f @this x y xs)))) Object
         (equiv [this other] (-equiv this other)) IEquiv
         (-equiv [o other] (identical? o other)) IHash
         (-hash [this] (goog/getUid this)) IPrintWithWriter
         (-pr-writer [_ writer opts]
           (-write writer "#<RLens: ")
           (pr-writer @state writer opts)
           (-write writer ">"))]))

(defn watch
  [source _ o n]
  (when (not= o n)
    (dosync* #(swap! *dirty-sinks* queue-into (get-sinks source)))))

#?(:clj (deftype Cell [state metadata sinks watching]
          IReactiveSource
            (get-rank [_] 0)
            (add-sink [_ sink] (vswap! sinks conj sink))
            (remove-sink [_ sink] (vswap! sinks disj sink))
            (get-sinks [_] @sinks)
          IDeref
            (deref [this]
              (register this)
              (when (compare-and-set! watching false true)
                (add-watch state this watch))
              @state)
          IMeta
            (meta [_] metadata)
          IRef
            (setValidator [_ f] (set-validator! state f))
            (getValidator [_] (.getValidator ^IRef state))
            (getWatches [_] (.getWatches state))
            (addWatch [_ key f] (add-watch state key f))
            (removeWatch [_ key] (remove-watch state key))
          IAtom
            (swap [_ f] (swap! state f))
            (swap [_ f x] (swap! state f x))
            (swap [_ f x y] (swap! state f x y))
            (swap [_ f x y xs] (apply swap! state f x y xs))
            (compareAndSet [_ oldval newval]
              (compare-and-set! state oldval newval))
            (reset [_ x] (reset! state x))
          Object
            (toString [_] (str "#<Cell: " (pr-str @state) ">"))))

#?(:clj (defn atom->cell [a m] (Cell. a m (volatile! #{}) (atom false)))
   :cljs (defn atom->cell
           [a _]
           (let [sinks (volatile! #{})
                 watching (atom false)]
             (specify! a
                       IReactiveSource
                       (get-rank [_] 0)
                       (add-sink [_ sink] (vswap! sinks conj sink))
                       (remove-sink [_ sink] (vswap! sinks disj sink))
                       (get-sinks [_] @sinks)
                       IDeref
                       (-deref [this]
                               (register this)
                               (when (compare-and-set! watching false true)
                                 (add-watch this this watch))
                               (.-state this))))))

(defn cell*
  ([x] (atom->cell (atom x) nil))
  ([x m] (atom->cell (apply atom x (flatten (seq m))) (get m :meta))))

(defn rx*
  ([getter] (rx* getter nil nil nil nil))
  ([getter setter] (rx* getter setter nil nil nil))
  ([getter setter meta] (rx* getter setter meta nil nil))
  ([getter setter meta validator] (rx* getter setter meta validator nil))
  ([getter setter meta validator drop-fns]
   (ReactiveExpression. getter
                        setter
                        meta
                        validator
                        (volatile! drop-fns)
                        (volatile! ::thunk)
                        (volatile! {})
                        (volatile! 0)
                        (volatile! #{})
                        (volatile! #{}))))

;; ---------------------------------------------------------------------------
;; cursor-cache backed by WeakHashMap on JVM, WeakMap on CLJS. Parent cells
;; used as keys are held weakly, so they can be GC'd when no strong references
;; remain.
;; ---------------------------------------------------------------------------

#?(:clj (def ^:private ^java.util.Map cursor-cache-impl
          (Collections/synchronizedMap (WeakHashMap.)))
   :cljs (def ^:private cursor-cache-impl (js/WeakMap.)))

;; Public helpers for cache inspection (used by tests and tooling).

(defn cursor-cached
  "Return the cached cursor for `parent` at `path`, or nil."
  [parent path]
  (when-let [m (.get cursor-cache-impl parent)] (get m (vec path))))

(defn cursor-cache-evict!
  "Remove the cache entry for `parent` at `path` (unconditionally)."
  [parent path]
  (let [path (vec path)]
    #?(:clj (locking cursor-cache-impl
              (when-let [m (.get cursor-cache-impl parent)]
                (let [m' (dissoc m path)]
                  (if (empty? m')
                    (.remove cursor-cache-impl parent)
                    (.put cursor-cache-impl parent m')))))
       :cljs (when-let [m (.get cursor-cache-impl parent)]
               (let [m' (dissoc m path)]
                 (if (empty? m')
                   (.delete cursor-cache-impl parent)
                   (.set cursor-cache-impl parent m')))))))

(def normalize-cursor-path vec)

;; Private helpers for mutating the cursor cache.

(defn- cursor-cache-get
  [parent path]
  (when-let [m (.get cursor-cache-impl parent)] (get m path)))

(defn- cursor-cache-put!
  [parent path val]
  #?(:clj (locking cursor-cache-impl
            (.put cursor-cache-impl
                  parent
                  (assoc (or (.get cursor-cache-impl parent) {}) path val)))
     :cljs (.set cursor-cache-impl
                 parent
                 (assoc (or (.get cursor-cache-impl parent) {}) path val))))

(defn- cursor-cache-dissoc!
  [parent path val]
  #?(:clj (locking cursor-cache-impl
            (when-let [m (.get cursor-cache-impl parent)]
              (when (identical? val (get m path))
                (let [m' (dissoc m path)]
                  (if (empty? m')
                    (.remove cursor-cache-impl parent)
                    (.put cursor-cache-impl parent m'))))))
     :cljs (when-let [m (.get cursor-cache-impl parent)]
             (when (identical? val (get m path))
               (let [m' (dissoc m path)]
                 (if (empty? m')
                   (.delete cursor-cache-impl parent)
                   (.set cursor-cache-impl parent m')))))))

(defn cursor
  [parent path]
  (macros/no-rx (let [path (normalize-cursor-path path)]
                  (or (cursor-cache-get parent path)
                      (let [x (macros/lens
                                (get-in @parent path)
                                (partial swap! parent assoc-in path))]
                        (add-drop x
                                  ::cursor
                                  (fn [_ dropped]
                                    (cursor-cache-dissoc! parent path dropped)))
                        (cursor-cache-put! parent path x)
                        x)))))

#?(:clj (defmacro cell [& body] `(carbon.macros/cell ~@body)))
#?(:clj (defmacro $ [& body] `(carbon.macros/$ ~@body)))
#?(:clj (defmacro lens [& body] `(carbon.macros/lens ~@body)))
#?(:clj (defmacro rx [& body] `(carbon.macros/rx ~@body)))
#?(:clj (defmacro $$ [& body] `(carbon.macros/$$ ~@body)))
#?(:clj (defmacro dosync [& body] `(carbon.macros/dosync ~@body)))
#?(:clj (defmacro no-rx [& body] `(carbon.macros/no-rx ~@body)))
