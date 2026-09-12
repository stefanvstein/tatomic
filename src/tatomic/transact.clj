(ns tatomic.transact
  (:require [tatomic.ops :as d]
             [coretools.keyword :refer [qualified-keyword]]))

(defn transact [conn {:keys [tx-data io-context] :as all} ]
  (let [tx-result (if tx-data
                    (if io-context
                      @(d/transact conn tx-data
                                   {:io-context (qualified-keyword io-context)})
                      @(d/transact conn tx-data))
                    @(d/transact conn all))
        db-after (:db-after tx-result)]
    (-> tx-result
          (assoc :tx-eid (nth (first (:tx-data tx-result)) 3))
          (update :db-after d/basis-t)
          (update :db-before d/basis-t)
          (update :tx-data (fn [datom] (mapv (fn [[e a v _t added?]]
                                               [e (d/ident db-after a) v added?])
                                             datom))))))

