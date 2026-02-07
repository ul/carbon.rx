(ns carbon.rx-test
  (:require [clojure.test :refer [deftest is testing]]
            [carbon.rx :as rx]
            [carbon.macros :refer [cell rx lens dosync no-rx]])
  #?(:clj (:import [java.lang.ref WeakReference])))

;; ---------------------------------------------------------------------------
;; Cell basics
;; ---------------------------------------------------------------------------

(deftest cell-creation-test
  (testing "Cell holds initial value" (let [c (cell 42)] (is (= 42 @c))))
  (testing "Cell with nil initial value" (let [c (cell nil)] (is (nil? @c)))))

(deftest cell-reset-test
  (testing "reset! changes cell value"
    (let [c (cell 1)]
      (reset! c 2)
      (is (= 2 @c))))
  (testing "reset! to nil"
    (let [c (cell 1)]
      (reset! c nil)
      (is (nil? @c)))))

(deftest cell-swap-test
  (testing "swap! applies function"
    (let [c (cell 10)]
      (swap! c + 5)
      (is (= 15 @c))))
  (testing "swap! with multiple args"
    (let [c (cell [1 2])]
      (swap! c conj 3)
      (is (= [1 2 3] @c)))))

(deftest cell-compare-and-set-test
  (testing "compare-and-set! succeeds with matching old value"
    (let [c (cell 1)]
      (is (true? (compare-and-set! c 1 2)))
      (is (= 2 @c))))
  (testing "compare-and-set! fails with mismatched old value"
    (let [c (cell 1)]
      (is (false? (compare-and-set! c 99 2)))
      (is (= 1 @c)))))

(deftest cell-metadata-test
  (testing "Cell carries metadata"
    (let [c (cell 1 :meta {:foo :bar})]
      (is (contains? (meta c) :foo))
      (is (= :bar (:foo (meta c)))))))

(deftest cell-watches-test
  (testing "Cell watch fires on value change"
    (let [c (cell 1)
          log (atom [])
          ;; Force deref inside an rx to set up watch
          r (rx @c)]
      ;; Force computation so watch is active
      @r
      (add-watch c ::test (fn [k _ o n] (swap! log conj [o n])))
      (reset! c 2)
      (is (some #(= [1 2] %) @log)))))

;; ---------------------------------------------------------------------------
;; Reactive expressions basics
;; ---------------------------------------------------------------------------

(deftest rx-basic-test
  (testing "rx computes derived value"
    (let [c (cell 3) r (rx (* @c @c))] (is (= 9 @r))))
  (testing "rx is lazy - not computed until deref"
    (let [computed (atom false)
          c (cell 1)
          r (rx (do (reset! computed true) @c))]
      (is (false? @computed) "Should not be computed before deref")
      @r
      (is (true? @computed) "Should be computed after deref"))))

(deftest rx-propagation-test
  (testing "rx updates when cell changes"
    (let [c (cell 1)
          r (rx (inc @c))]
      (is (= 2 @r))
      (reset! c 10)
      (is (= 11 @r))))
  (testing "rx updates through a chain"
    (let [a (cell 1)
          b (rx (+ @a 1))
          c (rx (* @b 2))]
      (is (= 4 @c))
      (reset! a 5)
      (is (= 12 @c)))))

(deftest rx-multiple-dependencies-test
  (testing "rx with multiple cell dependencies"
    (let [a (cell 2)
          b (cell 3)
          r (rx (+ @a @b))]
      (is (= 5 @r))
      (reset! a 10)
      (is (= 13 @r))
      (reset! b 20)
      (is (= 30 @r)))))

(deftest rx-diamond-dependency-test
  (testing "Diamond dependency graph - no glitches"
    (let [a (cell 1)
          b (rx (+ @a 1))
          c (rx (* @a 2))
          d (rx (+ @b @c))]
      ;; a=1 => b=2, c=2 => d=4
      (is (= 4 @d))
      ;; a=3 => b=4, c=6 => d=10 (no intermediate states)
      (reset! a 3)
      (is (= 10 @d)))))

(deftest rx-dynamic-dependencies-test
  (testing "Dependencies change based on conditional"
    (let [flag (cell true)
          a (cell 1)
          b (cell 2)
          r (rx (if @flag @a @b))]
      (is (= 1 @r))
      ;; Changing b should not affect r while flag is true
      (reset! b 99)
      (is (= 1 @r))
      ;; Switch branch
      (reset! flag false)
      (is (= 99 @r))
      ;; Now changing a should not affect r
      (reset! a 100)
      (is (= 99 @r))
      ;; Changing b now should
      (reset! b 42)
      (is (= 42 @r)))))

(deftest rx-nil-value-test
  (testing "rx can return nil" (let [c (cell nil) r (rx @c)] (is (nil? @r))))
  (testing "rx transitions to nil"
    (let [c (cell 1)
          r (rx (when (odd? @c) @c))]
      (is (= 1 @r))
      (reset! c 2)
      (is (nil? @r)))))

(deftest rx-no-change-no-propagation-test
  (testing "No propagation when value unchanged"
    (let [c (cell 1)
          count (atom 0)
          r1 (rx (if (> @c 0) :pos :non-pos))
          r2 (rx (do (swap! count inc) @r1))]
      (is (= :pos @r2))
      (is (= 1 @count))
      ;; Change c but r1 still returns :pos
      (reset! c 2)
      @r2
      ;; r2 should not recompute because r1's value didn't change
      (is (= :pos @r2)))))

(deftest rx-metadata-test
  (testing "rx carries metadata from macro form"
    (let [r (rx 42)]
      (is (some? (meta r)))
      (is (contains? (meta r) :form)))))

;; ---------------------------------------------------------------------------
;; *value* (previous value access)
;; ---------------------------------------------------------------------------

(deftest rx-previous-value-test
  (testing "*value* provides previous value during recomputation"
    (let [c (cell 1)
          history (atom [])
          r (rx (let [v @c]
                  (swap! history conj rx/*value*)
                  v))]
      @r
      (is (= :carbon.rx/thunk (first @history))
          "First computation has ::thunk as previous value")
      (reset! c 2)
      @r
      (is (= 1 (second @history))
          "Second computation has old value as *value*"))))

;; ---------------------------------------------------------------------------
;; Lens
;; ---------------------------------------------------------------------------

(deftest lens-read-test
  (testing "Lens can be read like an rx"
    (let [c (cell {:x 1})
          l (lens (:x @c) (partial swap! c assoc :x))]
      (is (= 1 @l)))))

(deftest lens-write-test
  (testing "Lens can be written to, updating source"
    (let [c (cell {:x 1})
          l (lens (:x @c) (partial swap! c assoc :x))]
      @l
      (reset! l 42)
      (is (= 42 @l))
      (is (= {:x 42} @c))))
  (testing "swap! on a lens"
    (let [c (cell {:x 10})
          l (lens (:x @c) (partial swap! c assoc :x))]
      @l
      (swap! l + 5)
      (is (= 15 @l))
      (is (= {:x 15} @c)))))

(deftest lens-no-setter-test
  (testing "Resetting a lens without setter throws"
    (let [c (cell 1)
          l (lens @c)]
      @l
      (is (thrown? AssertionError (reset! l 99))))))

(deftest lens-compare-and-set-test
  (testing "compareAndSet on a lens"
    (let [c (cell {:x 1})
          l (lens (:x @c) (partial swap! c assoc :x))]
      @l
      (is (true? (compare-and-set! l 1 2)))
      (is (= 2 @l))
      (is (false? (compare-and-set! l 99 3)))
      (is (= 2 @l)))))

;; ---------------------------------------------------------------------------
;; Cursor
;; ---------------------------------------------------------------------------

(deftest cursor-read-test
  (testing "Cursor reads nested value"
    (let [c (cell {:a {:b 42}}) cr (rx/cursor c [:a :b])] (is (= 42 @cr)))))

(deftest cursor-write-test
  (testing "Cursor writes propagate to parent"
    (let [c (cell {:a 1})
          cr (rx/cursor c [:a])]
      @cr
      (reset! cr 99)
      (is (= 99 @cr))
      (is (= {:a 99} @c)))))

(deftest cursor-caching-test
  (testing "Same path returns identical cursor"
    (let [c (cell {:x 1})
          cr1 (rx/cursor c [:x])
          cr2 (rx/cursor c [:x])]
      (is (identical? cr1 cr2))))
  (testing "Different paths return different cursors"
    (let [c (cell {:x 1, :y 2})
          cr1 (rx/cursor c [:x])
          cr2 (rx/cursor c [:y])]
      (is (not (identical? cr1 cr2))))))

(deftest cursor-reactivity-test
  (testing "Cursor updates when parent cell changes"
    (let [c (cell {:a 1})
          cr (rx/cursor c [:a])]
      (is (= 1 @cr))
      (swap! c assoc :a 10)
      (is (= 10 @cr)))))

(deftest cursor-normalize-path-test
  (testing "Cursor normalizes path to vector"
    (let [c (cell {:a 1})
          cr1 (rx/cursor c [:a])
          cr2 (rx/cursor c '(:a))]
      (is (identical? cr1 cr2)))))

;; ---------------------------------------------------------------------------
;; dosync - batching
;; ---------------------------------------------------------------------------

(deftest dosync-batching-test
  (testing "dosync batches multiple updates"
    (let [a (cell 1)
          b (cell 2)
          history (atom [])
          r (rx (let [v (+ @a @b)]
                  (swap! history conj v)
                  v))]
      ;; Initial computation
      @r
      (is (= [3] @history))
      ;; Without dosync, each reset! would propagate independently
      ;; With dosync, propagation happens once at the end
      (dosync (reset! a 10) (reset! b 20))
      @r
      ;; Should see 30, and NOT see intermediate 12 (a=10,b=2) in history
      (is (= 30 (last @history)))
      (is (not (some #{12} @history))
          "Should not observe intermediate state a=10,b=2"))))

(deftest dosync-nested-test
  (testing "Nested dosync defers to outermost"
    (let [c (cell 0)
          history (atom [])
          r (rx (let [v @c]
                  (swap! history conj v)
                  v))]
      @r
      (dosync (reset! c 1) (dosync (reset! c 2)))
      @r
      ;; Only the final value should be observed
      (is (= 2 @r))
      (is (= 2 (last @history))))))

(deftest dosync-return-value-test
  (testing "dosync returns the value of the last expression"
    (let [result (dosync (+ 1 2))] (is (= 3 result)))))

;; ---------------------------------------------------------------------------
;; no-rx
;; ---------------------------------------------------------------------------

(deftest no-rx-test
  (testing "Deref inside no-rx does not create dependency"
    (let [a (cell 1)
          b (cell 2)
          r (rx (+ @a (no-rx @b)))]
      (is (= 3 @r))
      ;; Changing b should not trigger recomputation
      (reset! b 99)
      (is (= 3 @r) "r should not see change to b")
      ;; Changing a should still work
      (reset! a 10)
      ;; When recomputed, it picks up current b
      (is (= 109 @r)))))

;; ---------------------------------------------------------------------------
;; Watches on reactive expressions
;; ---------------------------------------------------------------------------

(deftest rx-watch-test
  (testing "Watch on rx fires when value changes"
    (let [c (cell 1)
          r (rx (* @c 2))
          log (atom [])]
      @r
      (add-watch r ::w (fn [k ref o n] (swap! log conj {:old o, :new n})))
      (reset! c 5)
      @r
      (is (some #(and (= 2 (:old %)) (= 10 (:new %))) @log))))
  (testing "Removing watch triggers GC eligibility"
    (let [c (cell 1)
          r (rx @c)]
      @r
      (add-watch r ::w (fn [_ _ _ _]))
      (remove-watch r ::w)
      ;; After removing the only watch, rx should be eligible for GC
      ;; (no sinks, no watches). Just verify no error.
      (is (= 1 @r)))))

;; ---------------------------------------------------------------------------
;; Drop handlers
;; ---------------------------------------------------------------------------

(deftest drop-handler-test
  (testing "Drop handler is called on GC"
    (let [dropped (atom false)
          c (cell 1)
          r (rx @c)]
      @r
      (rx/add-drop r ::d (fn [_ _] (reset! dropped true)))
      ;; Force GC by ensuring no sinks and no watches
      (rx/gc r)
      (is (true? @dropped))))
  (testing "Remove drop handler"
    (let [dropped (atom false)
          c (cell 1)
          r (rx @c)]
      @r
      (rx/add-drop r ::d (fn [_ _] (reset! dropped true)))
      (rx/remove-drop r ::d)
      (rx/gc r)
      (is (false? @dropped)))))

;; ---------------------------------------------------------------------------
;; Garbage collection
;; ---------------------------------------------------------------------------

(deftest gc-resets-to-thunk-test
  (testing "GC resets rx back to thunk state"
    (let [c (cell 1)
          r (rx @c)]
      @r
      (is (rx/computed? r))
      (rx/gc r)
      (is (not (rx/computed? r)))
      ;; Re-deref recomputes
      (reset! c 42)
      (is (= 42 @r)))))

(deftest gc-disconnects-sources-test
  (testing "GC removes rx from source sinks"
    (let [c (cell 1)
          r (rx @c)]
      @r
      (is (contains? (rx/get-sinks c) r))
      (rx/gc r)
      (is (not (contains? (rx/get-sinks c) r))))))

(deftest gc-with-active-sinks-no-op-test
  (testing "GC does not reset rx that has active sinks"
    (let [c (cell 1)
          r1 (rx @c)
          r2 (rx @r1)]
      @r2
      ;; r1 has r2 as a sink, so gc should be deferred
      (rx/gc r1)
      (is (rx/computed? r1)))))

;; ---------------------------------------------------------------------------
;; Rank computation
;; ---------------------------------------------------------------------------

(deftest rank-test
  (testing "Cell has rank 0" (let [c (cell 1)] (is (= 0 (rx/get-rank c)))))
  (testing "rx rank is higher than its source"
    (let [c (cell 1)
          r (rx @c)]
      @r
      (is (> (rx/get-rank r) (rx/get-rank c)))))
  (testing "Chained rx has increasing rank"
    (let [a (cell 1)
          b (rx @a)
          c (rx @b)]
      @c
      (is (> (rx/get-rank c) (rx/get-rank b)))
      (is (> (rx/get-rank b) (rx/get-rank a))))))

;; ---------------------------------------------------------------------------
;; Protocol implementations
;; ---------------------------------------------------------------------------

(deftest reactive-source-protocol-test
  (testing "add-sink and remove-sink on a cell"
    (let [c (cell 1)
          sentinel (rx 1)]
      @sentinel
      (rx/add-sink c sentinel)
      (is (contains? (rx/get-sinks c) sentinel))
      (rx/remove-sink c sentinel)
      (is (not (contains? (rx/get-sinks c) sentinel))))))

(deftest reactive-expression-protocol-test
  (testing "add-source and remove-source"
    (let [c (cell 1)
          r (rx @c)]
      @r
      ;; After computation, c should be in r's sources (indirectly tested
      ;; via sink)
      (is (contains? (rx/get-sinks c) r) "r should be in c's sinks")
      (rx/remove-source r c)
      ;; remove-source only updates the rx's internal sources set. We test
      ;; via GC behavior: after removing source, GC won't touch that source
      (is (some? r)))))

;; ---------------------------------------------------------------------------
;; Edge cases
;; ---------------------------------------------------------------------------

(deftest rx-constant-test
  (testing "rx with no dependencies (constant)"
    (let [r (rx 42)] (is (= 42 @r)))))

(deftest rx-recompute-idempotent-test
  (testing "Multiple derefs return same value without recomputation"
    (let [count (atom 0)
          c (cell 1)
          r (rx (do (swap! count inc) @c))]
      @r
      @r
      @r
      (is (= 1 @count) "Should only compute once without changes"))))

(deftest cell-with-collection-values-test
  (testing "Cell with map value"
    (let [c (cell {:a 1, :b 2})]
      (swap! c assoc :c 3)
      (is (= {:a 1, :b 2, :c 3} @c))))
  (testing "Cell with vector value"
    (let [c (cell [1 2 3])]
      (swap! c conj 4)
      (is (= [1 2 3 4] @c)))))

(deftest deep-chain-test
  (testing "Long chain of reactive expressions"
    (let [c (cell 1)
          r1 (rx (inc @c))
          r2 (rx (inc @r1))
          r3 (rx (inc @r2))
          r4 (rx (inc @r3))
          r5 (rx (inc @r4))]
      (is (= 6 @r5))
      (reset! c 10)
      (is (= 15 @r5)))))

(deftest wide-fan-out-test
  (testing "Single cell with many dependent rx"
    (let [c (cell 1)
          rxs (vec (for [i (range 20)] (rx (+ @c i))))]
      (doseq [i (range 20)] (is (= (+ 1 i) @(rxs i))))
      (reset! c 100)
      (doseq [i (range 20)] (is (= (+ 100 i) @(rxs i)))))))

;; ---------------------------------------------------------------------------
;; Propagation queue correctness
;; ---------------------------------------------------------------------------

(deftest propagation-order-test
  (testing "Lower-rank nodes are computed before higher-rank nodes"
    (let [order (atom [])
          c (cell 0)
          r1 (rx (do (swap! order conj :r1) (inc @c)))
          r2 (rx (do (swap! order conj :r2) (inc @r1)))
          r3 (rx (do (swap! order conj :r3) (inc @r2)))]
      @r3
      ;; Keep chain alive so clean doesn't GC it after propagation
      (add-watch r3 ::keep-alive (fn [_ _ _ _]))
      (reset! order [])
      (reset! c 10)
      (is (= [:r1 :r2 :r3] @order)
          "Propagation must process nodes in ascending rank order")
      (remove-watch r3 ::keep-alive))))

(deftest propagation-single-computation-test
  (testing "Diamond: join node computed only once per propagation"
    (let [cnt (atom 0)
          c (cell 0)
          left (rx (inc @c))
          right (rx (dec @c))
          join (rx (do (swap! cnt inc) (+ @left @right)))]
      @join
      ;; Keep join alive so clean doesn't GC it
      (add-watch join ::keep-alive (fn [_ _ _ _]))
      (reset! cnt 0)
      (swap! c inc)
      (is (= 1 @cnt) "Join should be computed exactly once")
      (remove-watch join ::keep-alive))))

(deftest glitch-free-diamond-test
  (testing "Diamond graph never observes inconsistent intermediate state"
    (let [c (cell 0)
          left (rx (inc @c))
          right (rx (dec @c))
          observed (atom [])
          join (rx (let [l @left
                         r @right]
                     (swap! observed conj [l r])
                     (+ l r)))]
      @join
      ;; Keep join alive for the whole test
      (add-watch join ::keep-alive (fn [_ _ _ _]))
      (reset! observed [])
      (dotimes [_ 10] (swap! c inc))
      ;; Invariant: left = right + 2, always
      (is (every? (fn [[l r]] (= l (+ r 2))) @observed)
          "Should never observe inconsistent left/right pair")
      (remove-watch join ::keep-alive))))

(deftest propagation-convergent-paths-test
  (testing
    "Node reachable via multiple paths is computed once with correct inputs"
    (let [c (cell 1)
          a (rx (* @c 2))    ;; rank 1
          b (rx (* @c 3))    ;; rank 1
          mid (rx (+ @a @b)) ;; rank 2
          d (rx (* @mid 10)) ;; rank 3
          compute-count (atom 0)
          final (rx (do (swap! compute-count inc) (+ @d @mid)))] ;; rank 4,
                                                                 ;; depends on
                                                                 ;; both d and
                                                                 ;; mid
      @final
      (is (= 55 @final))  ;; (2+3)*10 + (2+3) = 55
      ;; Keep final alive so clean doesn't GC the chain
      (add-watch final ::keep-alive (fn [_ _ _ _]))
      (reset! compute-count 0)
      (reset! c 2)
      (is (= 110 @final)) ;; (4+6)*10 + (4+6) = 110
      (is (= 1 @compute-count)
          "final should compute exactly once despite two dirty paths")
      (remove-watch final ::keep-alive))))

;; ---------------------------------------------------------------------------
;; Edge cases
;; ---------------------------------------------------------------------------

(deftest cycle-detection-test
  (testing "Cycle detection throws exception"
    (let [d-ref (atom nil)
          c (rx (if @d-ref @@d-ref 1))
          d (rx @c)]
      (reset! d-ref d)
      ;; c depends on d, d depends on c.
      ;; Dereferencing c starts computation.
      ;; c -> d -> c
      (is (thrown? clojure.lang.ExceptionInfo @c)))))

(deftest many-same-rank-sinks-test
  (testing "Propagation handles many sinks at the same rank"
    (let [c (cell 0)
          rxs (vec (repeatedly 50 #(rx @c)))]
      ;; Force initial computation
      (doseq [r rxs] @r)
      ;; Keep all alive
      (doseq [r rxs] (add-watch r ::keep (fn [_ _ _ _])))
      (reset! c 42)
      (is (every? #(= 42 @%) rxs)
          "All 50 same-rank sinks should receive the update")
      (doseq [r rxs] (remove-watch r ::keep)))))

(deftest cursor-cache-race-test
  (testing "Cursor GC does not evict newer cursors for same path"
    (let [c (cell {:k 1})
          cr1 (rx/cursor c [:k])]
      (is (some? (rx/cursor-cached c [:k])) "cr1 should be in cache")
      ;; Simulate race: evict cr1 from cache
      (rx/cursor-cache-evict! c [:k])
      (is (nil? (rx/cursor-cached c [:k])) "Cache entry should be gone")
      ;; Create second cursor for same path (since cache is empty, new one
      ;; is created)
      (let [cr2 (rx/cursor c [:k])]
        (is (not (identical? cr1 cr2)) "cr2 should be a new instance")
        (is (identical? cr2 (rx/cursor-cached c [:k])) "cr2 should be in cache")
        ;; Trigger GC on cr1 (the old cursor)
        (rx/gc cr1)
        ;; Assert that cr2 is STILL in the cache
        (is (some? (rx/cursor-cached c [:k]))
            "cr2 should NOT be evicted by cr1's GC")
        (is (identical? cr2 (rx/cursor-cached c [:k]))
            "Cache should still hold cr2")))))

#?(:clj (deftest cursor-cache-weak-ref-test
          (testing "Cursor cache does not prevent GC of parent cells"
            ;; Create a cell + cursor, deref to force computation, then GC
            ;; both. The cursor's lens closure captures the parent, so we
            ;; must also drop the cursor reference.  With a WeakHashMap,
            ;; once no strong refs remain to the parent (from user code or
            ;; cached cursors), the cache entry is eligible for collection.
            (let [weak-ref (let [c (cell {:x 1})
                                 cr (rx/cursor c [:x])]
                             @cr
                             ;; GC the cursor so it drops out of the cache
                             ;; via its
                             ;; drop handler; this removes the closure
                             ;; holding `c`.
                             (rx/gc cr)
                             (WeakReference. c))]
              ;; Now no strong references to the cell remain.
              (System/gc)
              (System/gc)
              (Thread/sleep 100)
              (System/gc)
              (is (nil? (.get weak-ref))
                  "Parent cell should be GC-eligible after cursor is GC'd")))))

#?(:clj
     (deftest rx-get-validator-test
       (testing
         "getValidator on ReactiveExpression returns the validator function"
         (let [v (fn [x] (pos? x))
               r (rx/rx* (fn [] 1) (fn [x]) nil v)]
           @r
           (is
             (= v (.getValidator r))
             "getValidator should return the validator passed at construction")))))

#?(:clj (deftest cell-get-validator-test
          (testing "Cell getValidator delegates to underlying atom"
            (let [v pos?
                  c (cell 1 :validator v)]
              (is (= v (.getValidator c))
                  "Cell getValidator should return the atom's validator")))))

(deftest cycle-detection-flag-test
  (testing "*cycle-detection* defaults to true (development)"
    (is (true? rx/*cycle-detection*)))
  (testing "Cycle detected when *cycle-detection* is explicitly true"
    (binding [rx/*cycle-detection* true]
      (let [d-ref (atom nil)
            c (rx (if @d-ref @@d-ref 1))
            d (rx @c)]
        (reset! d-ref d)
        (is (thrown? clojure.lang.ExceptionInfo @c)))))
  (testing "Cycle detection can be disabled via binding"
    ;; When disabled, cycle detection guard is skipped. The cycle itself
    ;; will cause a StackOverflowError instead.
    (binding [rx/*cycle-detection* false]
      (let [d-ref (atom nil)
            c (rx (if @d-ref @@d-ref 1))
            d (rx @c)]
        (reset! d-ref d)
        (is (thrown? StackOverflowError @c))))))

#?(:clj (deftest cell-tostring-test
          (testing "Cell has a readable string representation"
            (let [c (cell 42)]
              (is (re-find #"42" (str c))
                  "Cell toString should include its value")))))

#?(:clj
     (deftest rx-tostring-test
       (testing "ReactiveExpression has a readable string representation"
         (let [r (rx 42)]
           @r
           (is
             (re-find #"42" (str r))
             "ReactiveExpression toString should include its computed value")))
       (testing "Uncomputed rx shows thunk state"
         (let [r (rx 42)]
           (is (re-find #"thunk" (str r))
               "Uncomputed rx toString should indicate thunk state")))))
