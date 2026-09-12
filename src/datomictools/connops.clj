(ns datomictools.connops
  (:require [datomic.api :as d]
            [datomic.client.api :as dc])
  (:refer-clojure :exclude [sync]))


(defn peer? [a]
  (or (instance? datomic.Connection a)
      (instance? datomic.db.IDb a)
      (= :peer a)
      (true? a)))


(defn db [conn]
  (if (peer? conn)
    (d/db conn)
    (dc/db conn)))

(defn transact [conn tx & more]
  (if (peer? conn)
    (d/transact conn tx)
    (future ( dc/transact conn {:tx-data tx}))))

(defn qseq [conn query-map  ]
  (if (peer? conn)
    (d/qseq query-map)
    (dc/qseq query-map)))

;; Anropa qseq istället
(defn q [peer? query & inputs]
  (if (datomictools.connops/peer? peer?)
    (apply d/q query inputs)
    (apply dc/q query inputs)))


(defn pull [db pattern eid & {:as options}]
  (if (peer? db)
    (d/pull db pattern eid  options)
    (dc/pull db (merge {:selector pattern :eid eid}
                       options))))

(defn sync
  ([conn]
   (if (peer? conn)
     (d/sync conn)
     (dc/sync conn (:basis-t (db conn)))))
  ([conn t]
   (if (peer? conn)
     (d/sync conn t)
     (dc/sync conn t))))

(defn as-of [db t]
  (if (peer? db)
    (d/as-of db t)
    (dc/as-of db t)))

(defn as-of-t
  "The t the db is depicting if it happens to be an as-of db"
  [db]
  (if (peer? db)
    (d/as-of-t db)
    (:as-of db)))

(defn view-t
  "The effective t db is depicting. This does not make sence on a history, since or with database, as those databases never has existed"
  [db]
  (if (peer? db)
    (or (d/as-of-t db)
        (d/basis-t db))
    (or (:as-of db)
        (:t db))))

(defn history [db]
  (if (peer? db)
    (d/history db)
    (dc/history db)))

(defn tx->t [conn tx-eid]
  (if (peer? conn)
    (d/tx->t tx-eid)
    (let [tx (first (dc/tx-range conn {:start tx-eid}))]
        (when-not tx
          (throw (ex-info "No tx found for tx-eid" {:tx-eid tx-eid})))
        (:t tx))))

(defn tx-range [conn start end]
  (if (peer? conn)
    (d/tx-range (d/log conn) start end)
    (dc/tx-range conn {:start start :end end})))

;;Kolla :t :basis-t på db-tool
(defn basis-t
  "The most recent t this database can depict"
  [db]
  (if (peer? db)
    (d/basis-t db)
    (:t db)))

(defn ident [db a-eid]
  (if (peer? db)
    (d/ident db a-eid)
    (-> (dc/pull db '[:db/ident] a-eid)
        :db/ident)))

(defn- entid-client 
  [db eid-or-ident]
  (cond
    (nil? eid-or-ident)
    nil

    ;; already an eid
    (integer? eid-or-ident)
    eid-or-ident

    ;; entity map
    (and (map? eid-or-ident) (integer? (:db/id eid-or-ident)))
    (:db/id eid-or-ident)

    ;; keyword ident
    (keyword? eid-or-ident)
    (ffirst
      (dc/q {:query '[:find ?e
                      :in $ ?ident
                      :where [?e :db/ident ?ident]]
             :args [db eid-or-ident]}))

    ;; lookup ref like [:user/id "alice"] or [:skill-link/key [a b]]
    (and (vector? eid-or-ident)
         (= 2 (count eid-or-ident)))
    (let [[a v] eid-or-ident]
      (ffirst
        (dc/q  '[:find ?e
                 :in $ ?a ?v
                 :where [?e ?a ?v]]
               db a v)))

    :else
    (throw (ex-info "Unsupported entid input"
                    {:value eid-or-ident
                     :type  (type eid-or-ident)})))
  )

(defn entid [db entity]
  (if (peer? db)
    (d/entid db entity)
    (entid-client db entity)))


