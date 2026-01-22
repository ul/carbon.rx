(ns carbon.rx
  (#?(:clj :require :cljs :require-macros) [carbon.macros :as macros])
  #?(:clj
     (:import [clojure.lang IDeref IMeta IAtom IRef]))
  #?(:clj
     (:refer-clojure :exclude [dosync])))

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

(defn id [x]
  #?(:clj (System/identityHashCode x)
     :cljs (goog/getUid x)))

(defn compare-rank [x y]
  (if (identical? x y)
    0
    (let [z (- (get-rank x) (get-rank y))]
      (if (zero? z)
        (compare (id x) (id y))
        z))))

(def empty-queue (sorted-set-by compare-rank))

(defn propagate
  "Recursively compute all dirty sinks in the `queue` and return all visited sources to clean."
  [queue]
  (binding [*rx* nil *rank* nil]                            ; try to be foolproof
    (loop [queue queue dirty '()]
      (if-let [x (first queue)]
        (let [queue (disj queue x)]
          (recur (if (= @x (compute x)) queue (->> x get-sinks (into queue)))
                 (conj dirty x)))
        dirty))))

(defn clean
  "Recursively garbage collect all disconnected sources in the `queue`"
  [queue]
  (doseq [source queue]
    (gc source)))

(defn register [source]
  (when *rx*                                                ; *rank* too
    (add-sink source *rx*)
    (add-source *rx* source)
    (swap! *rank* max (get-rank source))))

(defn dosync* [f]
  (let [sinks (or *dirty-sinks* (atom empty-queue))
        sources (or *dirty-sources* (atom empty-queue))
        result (binding [*dirty-sinks* sinks
                         *dirty-sources* sources]
                 (f))]
    ;; top-level dosync*
    (when-not *dirty-sinks*
      (binding [*dirty-sources* sources]
        (swap! *dirty-sources* into (propagate @sinks))))
    ;; top-level dosync*
    (when-not *dirty-sources*
      (clean (reverse @sources)))
    result))

#?(:cljs
   (defn safe-realized? [x]
     (if (implements? IPending x)
       (realized? x)
       true)))

#?(:cljs
   (defn fully-realized?
     [form]
     (if (seqable? form)
       (and (safe-realized? form) (every? fully-realized? form))
       (safe-realized? form))))

(deftype ReactiveExpression [getter setter metadata validator drop state watches rank sources sinks]

  IDeref
  (#?(:clj deref :cljs -deref) [this]
    (when-not (computed? this) (compute this))
    (register this)
    @state)

  IReactiveSource
  (get-rank [_] @rank)
  (add-sink [_ sink] (swap! sinks conj sink))
  (remove-sink [_ sink] (swap! sinks disj sink))
  (get-sinks [_] @sinks)

  IReactiveExpression
  (computed? [this]
    (not= @state ::thunk))
  (compute [this]
    (doseq [source @sources]
      (remove-sink source this))
    (reset! sources #{})
    (when #?(:cljs ^boolean js/goog.DEBUG :clj *assert*)
      (when (some #(identical? this %) *provenance*)
        (throw (ex-info (str "carbon.rx: detected a cycle in computation graph!\n"
                             (pr-str (map meta *provenance*)))
                        {:provenance *provenance*}))))
    (let [old-value @state
          r (atom 0)
          new-value (binding [*rx* this
                              *value* old-value
                              *rank* r
                              *provenance* (conj *provenance* this)]
                      (let [x (getter)]
                        ;; #?(:cljs
                        ;;    (when ^boolean js/goog.DEBUG
                        ;;      (when-not (fully-realized? x)
                        ;;        (js/console.warn
                        ;;          "carbon.rx: this branch returns not fully realized value, make sure that no dependencies are derefed inside lazy part:\n"
                        ;;          (map meta *provenance*)
                        ;;          "\n" x))))
                        x))]
      (reset! rank (inc @r))
      (when (not= old-value new-value)
        (reset! state new-value)
        (doseq [[key f] @watches]
          (f key this old-value new-value)))
      new-value))
  (gc [this]
    (if *dirty-sources*
      (swap! *dirty-sources* conj this)
      (when (and (empty? @sinks) (empty? @watches))
        (doseq [source @sources]
          (remove-sink source this)
          (when (satisfies? IReactiveExpression source)
            (gc source)))
        (reset! sources #{})
        (reset! state ::thunk)
        (notify-drops this))))
  (add-source [_ source]
    (swap! sources conj source))
  (remove-source [_ source]
    (swap! sources disj source))

  IReactiveDrop
  (add-drop [this key f]
    (swap! drop assoc key f)
    this)
  (remove-drop [this key]
    (swap! drop dissoc key)
    this)
  (notify-drops [this]
    (doseq [[key f] @drop]
      (f key this)))

  IMeta
  (#?(:clj meta :cljs -meta) [_] metadata)

  #?@(:clj
      [IRef
       (setValidator [_ _])
       (getValidator [_])
       (getWatches [_] @watches)
       (addWatch [this key f]
         (when-not (computed? this) (compute this))
         (swap! watches assoc key f)
         this)
       (removeWatch [this key]
         (swap! watches dissoc key)
         (gc this)
         this)

       IAtom
       (swap [this f] (macros/no-rx (reset! this (f @this))))
       (swap [this f x] (macros/no-rx (reset! this (f @this x))))
       (swap [this f x y] (macros/no-rx (reset! this (f @this x y))))
       (swap [this f x y xs] (macros/no-rx (reset! this (apply f @this x y xs))))
       (compareAndSet [this oldval newval]
         (if (= oldval @state)
           (do (reset! this newval) true)
           false))
       (reset [_ new-value]
         (assert setter "Can't reset lens w/o setter")
         (when-not (nil? validator)
           (assert (validator new-value) "Validator rejected reference state"))
         (dosync* #(setter new-value))
         new-value)

       #_IReference                                         ;; TODO alterMeta, resetMeta
       ]

      :cljs
      [IWatchable
       (-notify-watches [this oldval newval]
                        (doseq [[key f] @watches]
                          (f key this oldval newval)))
       (-add-watch [this key f]
                   (when-not (computed? this) (compute this))
                   (swap! watches assoc key f)
                   this)
       (-remove-watch [this key]
                      (swap! watches dissoc key)
                      (gc this)
                      this)

       IReset
       (-reset! [_ new-value]
                (assert setter "Can't reset lens w/o setter")
                (when-not (nil? validator)
                  (assert (validator new-value) "Validator rejected reference state"))
                (dosync* #(setter new-value))
                new-value)

       ISwap
       (-swap! [this f] (macros/no-rx (reset! this (f @this))))
       (-swap! [this f x] (macros/no-rx (reset! this (f @this x))))
       (-swap! [this f x y] (macros/no-rx (reset! this (f @this x y))))
       (-swap! [this f x y xs] (macros/no-rx (reset! this (apply f @this x y xs))))

       Object
       (equiv [this other] (-equiv this other))

       IEquiv
       (-equiv [o other] (identical? o other))

       IHash
       (-hash [this] (goog/getUid this))

       IPrintWithWriter
       (-pr-writer [_ writer opts]
                   (-write writer "#<RLens: ")
                   (pr-writer @state writer opts)
                   (-write writer ">"))]))

(defn watch [source _ o n]
  (when (not= o n)
    (dosync* #(swap! *dirty-sinks* into (get-sinks source)))))

#?(:clj
   (deftype Cell [state metadata sinks]

     IReactiveSource
     (get-rank [_] 0)
     (add-sink [_ sink] (swap! sinks conj sink))
     (remove-sink [_ sink] (swap! sinks disj sink))
     (get-sinks [_] @sinks)

     IDeref
     (deref [this]
       (register this)
       (add-watch state this watch)
       @state)

     IMeta
     (meta [_] metadata)

     IRef
     (setValidator [_ _])
     (getValidator [_])
     (getWatches [_] (.getWatches state))
     (addWatch [_ key f] (add-watch state key f))
     (removeWatch [_ key] (remove-watch state key))

     IAtom
     (swap [_ f] (swap! state f))
     (swap [_ f x] (swap! state f x))
     (swap [_ f x y] (swap! state f x y))
     (swap [_ f x y xs] (apply swap! state f x y xs))
     (compareAndSet [_ oldval newval] (compare-and-set! state oldval newval))
     (reset [_ x] (reset! state x))))

#?(:clj
   (defn atom->cell [a m]
     (Cell. a m (atom #{})))

   :cljs
   (defn atom->cell [a _]
     (let [sinks (atom #{})]
       (specify! a

         IReactiveSource
         (get-rank [_] 0)
         (add-sink [_ sink] (swap! sinks conj sink))
         (remove-sink [_ sink] (swap! sinks disj sink))
         (get-sinks [_] @sinks)

         IDeref
         (-deref [this]
           (register this)
           (add-watch this this watch)
           (.-state this))))))

(defn cell*
  ([x] (atom->cell (atom x) nil))
  ([x m] (atom->cell (apply atom x (flatten (seq m))) (get m :meta))))

(defn rx*
  ([getter] (rx* getter nil nil nil nil))
  ([getter setter] (rx* getter setter nil nil nil))
  ([getter setter meta] (rx* getter setter meta nil nil))
  ([getter setter meta validator] (rx* getter setter meta validator nil))
  ([getter setter meta validator drop]
   (ReactiveExpression. getter setter meta validator (atom drop) (atom ::thunk) (atom {}) (atom 0) (atom #{}) (atom #{}))))

(def cursor-cache (atom {}))

(defn cache-dissoc [cache parent path val]
  (if (identical? val (get-in cache [parent path]))
    (let [cache (update cache parent dissoc path)]
      (if (empty? (get cache parent))
        (dissoc cache parent)
        cache))
    cache))

(def normalize-cursor-path vec)

(defn cursor [parent path]
  (macros/no-rx
    (let [path (normalize-cursor-path path)]
      (or (get-in @cursor-cache [parent path])
          (let [x (macros/lens (get-in @parent path) (partial swap! parent assoc-in path))]
            (add-drop x ::cursor (fn [_ dropped] (swap! cursor-cache cache-dissoc parent path dropped)))
            (swap! cursor-cache assoc-in [parent path] x)
            x)))))

#?(:clj (defmacro cell [& body] `(carbon.macros/cell ~@body)))
#?(:clj (defmacro $ [& body] `(carbon.macros/$ ~@body)))
#?(:clj (defmacro lens [& body] `(carbon.macros/lens ~@body)))
#?(:clj (defmacro rx [& body] `(carbon.macros/rx ~@body)))
#?(:clj (defmacro $$ [& body] `(carbon.macros/$$ ~@body)))
#?(:clj (defmacro dosync [& body] `(carbon.macros/dosync ~@body)))
#?(:clj (defmacro no-rx [& body] `(carbon.macros/no-rx ~@body)))
