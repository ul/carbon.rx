(ns carbon.rx-bench
  (:require [criterium.core :as crit]
            [carbon.rx :as rx])
  (:refer-clojure :exclude [dosync])
  (:require [carbon.macros :refer [cell rx dosync]]))

(defmacro bench-it
  [label & body]
  `(do (println "\n>>>" ~label) (crit/quick-bench (do ~@body))))

;; ---------------------------------------------------------------------------
;; 1. Primitive Operations
;; ---------------------------------------------------------------------------

(defn bench-primitives
  []
  (println "\n===== Primitive Operations =====")
  (bench-it "Cell creation" (cell 0))
  (let [c (cell 0)] (bench-it "Cell deref" @c))
  (let [c (cell 0)] (bench-it "Cell reset!" (reset! c 1)))
  (let [c (cell 0)] (bench-it "Cell swap!" (swap! c inc)))
  (let [c (cell 0)] (bench-it "rx creation" (rx @c)))
  (let [c (cell 0)] (bench-it "rx first deref" (let [r (rx @c)] @r)))
  (let [c (cell 0)
        r (rx @c)]
    @r
    (bench-it "rx cached deref" @r)))

;; ---------------------------------------------------------------------------
;; 2. Propagation Topologies
;; ---------------------------------------------------------------------------

(defn bench-linear-chain
  []
  (println "\n===== Linear Chain (depth 10) =====")
  (let [c (cell 0)
        chain (reduce (fn [prev _] (let [p prev] (rx @p))) c (range 10))
        _ @chain]
    (bench-it "Linear chain propagation" (swap! c inc) @chain)))

(defn bench-wide-fan-out
  []
  (println "\n===== Wide Fan-out (width 100) =====")
  (let [c (cell 0)
        leaves (mapv (fn [_] (let [src c] (rx @src))) (range 100))
        _ (doseq [l leaves] @l)]
    (bench-it "Wide fan-out propagation" (swap! c inc) (doseq [l leaves] @l))))

(defn bench-diamond
  []
  (println "\n===== Diamond =====")
  (let [c (cell 0)
        left (rx (inc @c))
        right (rx (dec @c))
        join (rx (+ @left @right))
        _ @join]
    (bench-it "Diamond propagation" (swap! c inc) @join)))

(defn bench-deep-wide
  []
  (println "\n===== Deep + Wide (depth 5, width 10) =====")
  (let [c (cell 0)
        build-level (fn [parents]
                      (vec
                        (for [p parents _ (range 10)] (let [src p] (rx @src)))))
        level1 (build-level [c])
        level2 (build-level level1)
        level3 (build-level level2)
        level4 (build-level level3)
        level5 (build-level level4)
        _ (doseq [l level5] @l)]
    (bench-it "Deep+wide propagation" (swap! c inc) (doseq [l level5] @l))))

;; ---------------------------------------------------------------------------
;; 3. Dynamic Dependencies
;; ---------------------------------------------------------------------------

(defn bench-conditional-switch
  []
  (println "\n===== Conditional Branch Switch =====")
  (let [flag (cell true)
        a (cell 1)
        b (cell 2)
        r (rx (if @flag @a @b))
        _ @r]
    (bench-it "Conditional branch switch" (swap! flag not) @r)))

;; ---------------------------------------------------------------------------
;; 4. Batching
;; ---------------------------------------------------------------------------

(defn bench-batching
  []
  (println "\n===== Batching =====")
  (let [cells (vec (repeatedly 100 #(cell 0)))
        sums (rx (reduce + (map deref cells)))
        _ @sums]
    (bench-it "Unbatched 100 cell updates"
              (doseq [c cells] (swap! c inc))
              @sums)
    (bench-it "Batched (dosync) 100 cell updates"
              (dosync (doseq [c cells] (swap! c inc)))
              @sums)))

;; ---------------------------------------------------------------------------
;; 5. Cursor
;; ---------------------------------------------------------------------------

(defn bench-cursor
  []
  (println "\n===== Cursor =====")
  (bench-it "Cursor creation (cache miss)"
            (let [c (cell {:a {:b 1}})] (rx/cursor c [:a :b])))
  (let [c (cell {:a {:b 1}})
        cu (rx/cursor c [:a :b])
        _ @cu]
    (bench-it "Cursor deref" @cu))
  (let [c (cell {:a {:b 1}})
        _ (rx/cursor c [:a :b])]
    (bench-it "Cursor creation (cache hit)" (rx/cursor c [:a :b]))))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn -main
  [& _args]
  (println "carbon.rx JVM benchmarks (Criterium quick-bench)\n")
  (bench-primitives)
  (bench-linear-chain)
  (bench-wide-fan-out)
  (bench-diamond)
  (bench-deep-wide)
  (bench-conditional-switch)
  (bench-batching)
  (bench-cursor)
  (println "\nDone."))
