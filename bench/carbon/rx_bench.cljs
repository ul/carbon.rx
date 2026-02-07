(ns carbon.rx-bench
  (:require [carbon.rx :as rx]
            ["tinybench" :refer [Bench]])
  (:require-macros [carbon.macros :refer [cell rx dosync]]))

(defn add-bench [^js bench name f] (.add bench name f))

(defn run-suite
  ([name setup-fn] (run-suite name setup-fn 2000))
  ([name setup-fn time-ms]
   (js/Promise. (fn [resolve _reject]
                  (let [bench (Bench. #js {:time time-ms, :warmupTime 500})]
                    (setup-fn bench)
                    (-> (.run bench)
                        (.then (fn [_]
                                 (println (str "\n===== " name " ====="))
                                 (js/console.table (.table bench))
                                 (resolve nil)))))))))

;; ---------------------------------------------------------------------------
;; 1. Primitive Operations
;; ---------------------------------------------------------------------------

(defn add-primitives
  [^js bench]
  (add-bench bench "Cell creation" (fn [] (cell 0)))
  (let [c (cell 0)] (add-bench bench "Cell deref" (fn [] @c)))
  (let [c (cell 0)] (add-bench bench "Cell reset!" (fn [] (reset! c 1))))
  (let [c (cell 0)] (add-bench bench "Cell swap!" (fn [] (swap! c inc))))
  (let [c (cell 0)] (add-bench bench "rx creation" (fn [] (rx @c))))
  ;; Each iteration must create its own cell because deref registers the rx
  ;; as a sink on the cell and sinks are held strongly — a shared cell
  ;; would accumulate millions of dead sinks and OOM.
  (add-bench bench "rx first deref" (fn [] (let [c (cell 0) r (rx @c)] @r)))
  (let [c (cell 0)
        r (rx @c)]
    @r
    (add-bench bench "rx cached deref" (fn [] @r))))

;; ---------------------------------------------------------------------------
;; 2. Propagation Topologies
;; ---------------------------------------------------------------------------

(defn add-propagation
  [^js bench]
  (let [c1 (cell 0)
        chain (reduce (fn [prev _] (let [p prev] (rx @p))) c1 (range 10))
        _ @chain]
    (add-bench bench "Linear chain (depth 10)" (fn [] (swap! c1 inc) @chain)))
  (let [c2 (cell 0)
        leaves (mapv (fn [_] (let [src c2] (rx @src))) (range 100))
        _ (doseq [l leaves] @l)]
    (add-bench bench
               "Wide fan-out (width 100)"
               (fn [] (swap! c2 inc) (doseq [l leaves] @l))))
  (let [c3 (cell 0)
        left (rx (inc @c3))
        right (rx (dec @c3))
        join (rx (+ @left @right))
        _ @join]
    (add-bench bench "Diamond propagation" (fn [] (swap! c3 inc) @join)))
  (let [c4 (cell 0)
        build-level (fn [parents]
                      (vec
                        (for [p parents _ (range 5)] (let [src p] (rx @src)))))
        level1 (build-level [c4])
        level2 (build-level level1)
        level3 (build-level level2)
        level4 (build-level level3)
        _ (doseq [l level4] @l)]
    (add-bench bench
               "Deep+wide (depth 4, width 5)"
               (fn [] (swap! c4 inc) (doseq [l level4] @l)))))

;; ---------------------------------------------------------------------------
;; 3. Dynamic Dependencies
;; ---------------------------------------------------------------------------

(defn add-conditional-switch
  [^js bench]
  (let [flag (cell true)
        a (cell 1)
        b (cell 2)
        r (rx (if @flag @a @b))
        _ @r]
    (add-bench bench "Conditional branch switch" (fn [] (swap! flag not) @r))))

;; ---------------------------------------------------------------------------
;; 4. Batching
;; ---------------------------------------------------------------------------

(defn add-batching
  [^js bench]
  (let [cells1 (vec (repeatedly 100 #(cell 0)))
        sums1 (rx (reduce + (map deref cells1)))
        _ @sums1
        cells2 (vec (repeatedly 100 #(cell 0)))
        sums2 (rx (reduce + (map deref cells2)))
        _ @sums2]
    (add-bench bench
               "Unbatched 100 cell updates"
               (fn [] (doseq [c cells1] (swap! c inc)) @sums1))
    (add-bench bench
               "Batched (dosync) 100 cell updates"
               (fn [] (dosync (doseq [c cells2] (swap! c inc))) @sums2))))

;; ---------------------------------------------------------------------------
;; 5. Cursor
;; ---------------------------------------------------------------------------

(defn add-cursor
  [^js bench]
  (add-bench bench
             "Cursor creation (cache miss)"
             (fn [] (let [c (cell {:a {:b 1}})] (rx/cursor c [:a :b]))))
  (let [c (cell {:a {:b 1}})
        cu (rx/cursor c [:a :b])
        _ @cu]
    (add-bench bench "Cursor deref" (fn [] @cu)))
  (let [c (cell {:a {:b 1}})
        _ (rx/cursor c [:a :b])]
    (add-bench bench
               "Cursor creation (cache hit)"
               (fn [] (rx/cursor c [:a :b])))))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn main
  []
  (println "carbon.rx Node benchmarks (tinybench)\n")
  ;; Primitives get a shorter time window because allocation-heavy
  ;; benchmarks
  ;; (cell/rx creation) accumulate objects that the GC cannot reclaim
  ;; mid-suite.
  (-> (run-suite "Primitive Operations" add-primitives 500)
      (.then #(run-suite "Propagation Topologies" add-propagation))
      (.then #(run-suite "Dynamic Dependencies" add-conditional-switch))
      (.then #(run-suite "Batching" add-batching))
      (.then #(run-suite "Cursor" add-cursor))
      (.then #(println "\nDone."))))
