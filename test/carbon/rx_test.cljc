(ns carbon.rx-test
  (:require [clojure.test :refer [deftest is testing]]
            [carbon.rx :as rx]
            [carbon.macros :refer [cell rx]]))

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

(deftest compare-rank-test
  (testing "Queue handles multiple items with same rank"
    (let [items (repeatedly 50 #(rx 1))
          queue rx/empty-queue]
      
      (let [q (into queue items)]
        (is (= 50 (count q)) "Should contain all items despite same rank")
        ;; Test retrieval/membership to see if the structure is valid
        (is (contains? q (first items)) "Set should contain the first item")))))

(deftest cursor-cache-race-test
  (testing "Cursor GC does not evict newer cursors for same path"
    (let [c (cell {:k 1})
          ;; 1. Create first cursor
          cr1 (rx/cursor c [:k])]
      
      (is (some? (get-in @rx/cursor-cache [c [:k]])) "cr1 should be in cache")
      
      ;; 2. Simulate race: Manually remove cr1 from cache while it's still alive
      (swap! rx/cursor-cache update c dissoc [:k])
      (is (nil? (get-in @rx/cursor-cache [c [:k]])) "Cache entry should be gone")
      
      ;; 3. Create second cursor for same path (since cache is empty, new one is created)
      (let [cr2 (rx/cursor c [:k])]
        (is (not (identical? cr1 cr2)) "cr2 should be a new instance")
        (is (identical? cr2 (get-in @rx/cursor-cache [c [:k]])) "cr2 should be in cache")
        
        ;; 4. Trigger GC on cr1 (the old cursor)
        ;; This invokes the drop handler. If buggy, it will blindly remove the entry for [:k].
        (rx/gc cr1)
        
        ;; 5. Assert that cr2 is STILL in the cache
        (is (some? (get-in @rx/cursor-cache [c [:k]])) "cr2 should NOT be evicted by cr1's GC")
        (is (identical? cr2 (get-in @rx/cursor-cache [c [:k]])) "Cache should still hold cr2")))))