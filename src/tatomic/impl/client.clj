(ns tatomic.impl.client
  (:require [datomic.client.api :as dc]
            [tatomic.impl.ops :as ops])
  (:refer-clojure :exclude [sync]))

(defn- entid-client [db eid-or-ident]
  (cond
    (nil? eid-or-ident) nil

    (integer? eid-or-ident) eid-or-ident

    (and (map? eid-or-ident) (integer? (:db/id eid-or-ident)))
    (:db/id eid-or-ident)

    (keyword? eid-or-ident)
    (ffirst
     (dc/q {:query '[:find ?e
                     :in $ ?ident
                     :where [?e :db/ident ?ident]]
            :args [db eid-or-ident]}))

    (and (vector? eid-or-ident)
         (= 2 (count eid-or-ident)))
    (let [[a v] eid-or-ident]
      (ffirst
       (dc/q '[:find ?e
               :in $ ?a ?v
               :where [?e ?a ?v]]
             db a v)))

    :else
    (throw (ex-info "Unsupported entid input"
                    {:value eid-or-ident
                     :type (type eid-or-ident)}))))

(defn- tx-eid [tx]
  (when-let [datom (some-> tx :data first)]
    (nth datom 0)))

(extend-protocol ops/Ops
  Object
  (db [conn] (dc/db conn))
  (transact
    ([conn tx] (future (dc/transact conn {:tx-data tx})))
    ([conn tx opts] (future (dc/transact conn (assoc opts :tx-data tx)))))
  (qseq [_ query-map] (dc/qseq query-map))
  (pull
    ([db pattern eid] (dc/pull db {:selector pattern :eid eid}))
    ([db pattern eid opts] (dc/pull db (merge {:selector pattern :eid eid}
                                              opts))))
  (sync
    ([conn] (dc/sync conn (:basis-t (dc/db conn))))
    ([conn t] (dc/sync conn t)))
  (as-of [db t] (dc/as-of db t))
  (view-t [db] (or (:as-of db)
                   (:t db)))
  (history [db] (dc/history db))
  (tx->t [conn tx-eid]
    (let [tx (first (dc/tx-range conn {:start tx-eid}))]
      (when-not tx
        (throw (ex-info "No tx found for tx-eid" {:tx-eid tx-eid})))
      (:t tx)))
  (t->tx [conn t]
    (let [tx (first (dc/tx-range conn {:start t :end (inc t)}))]
      (when-not tx
        (throw (ex-info "No tx found for t" {:t t})))
      (tx-eid tx)))
  (tx-range [conn start end] (dc/tx-range conn {:start start :end end}))
  (basis-t [db] (:t db))
  (ident [db a-eid] (-> (dc/pull db '[:db/ident] a-eid)
                        :db/ident))
  (entid [db entity] (entid-client db entity)))
