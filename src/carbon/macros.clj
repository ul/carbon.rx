(ns carbon.macros
  (:refer-clojure :exclude [dosync])
  (:require [clojure.pprint :as pp]))

(defn meta-merge [metadata form]
  (merge {:form (with-out-str (pp/pprint form))}
         (meta form)
         metadata))

(defmacro cell [x & {:as m}]
  `(carbon.rx/cell* ~x ~(update m :meta meta-merge &form)))

(defmacro $ [x & {:as m}]
  `(carbon.rx/cell* ~x ~(update m :meta meta-merge &form)))

(defmacro lens
  ([getter]
   `(carbon.rx/rx*
     (fn [] ~getter)
     nil
     ~(meta-merge nil &form)))
  ([getter setter]
   `(carbon.rx/rx*
     (fn [] ~getter)
     ~setter
     ~(meta-merge nil &form)))
  ([getter setter meta]
   `(carbon.rx/rx*
     (fn [] ~getter)
     ~setter
     ~(meta-merge meta &form)))
  ([getter setter meta validator]
   `(carbon.rx/rx*
     (fn [] ~getter)
     ~setter
     ~(meta-merge meta &form)
     ~validator))
  ([getter setter meta validator drop]
   `(carbon.rx/rx*
     (fn [] ~getter)
     ~setter
     ~(meta-merge meta &form)
     ~validator
     ~drop)))

(defmacro rx [& body]
  `(carbon.rx/rx*
    (fn [] ~@body)
    nil
    ~(meta-merge nil &form)))

(defmacro $$ [& body]
  `(carbon.rx/rx*
    (fn [] ~@body)
    nil
    ~(meta-merge nil &form)))

(defmacro dosync [& body]
  `(carbon.rx/dosync* (fn [] ~@body)))

(defmacro no-rx [& body]
  `(binding [carbon.rx/*rx* nil] ~@body))