(ns tatomic.datomic.peer
  (:require [datomic.api :as d]
            [tatomic.ops :as ops])
  (:refer-clojure :exclude [sync]))

(defn- transact* [conn tx opts]
  (if (seq opts)
    (apply d/transact conn tx (mapcat identity opts))
    (d/transact conn tx)))

(defn- pull* [db pattern eid opts]
  (if (seq opts)
    (apply d/pull db pattern eid (mapcat identity opts))
    (d/pull db pattern eid)))

(defn- view-t* [db]
  (or (d/as-of-t db)
      (d/basis-t db)))

(extend-protocol ops/Ops
  datomic.Connection
  (db [conn] (d/db conn))
  (transact
    ([conn tx] (transact* conn tx nil))
    ([conn tx opts] (transact* conn tx opts)))
  (qseq [_ query-map] (d/qseq query-map))
  (sync
    ([conn] (d/sync conn))
    ([conn t] (d/sync conn t)))
  (tx->t [_ tx-eid] (d/tx->t tx-eid))
  (t->tx [_ t] (d/t->tx t))
  (tx-range [conn start end] (d/tx-range (d/log conn) start end))

  datomic.db.IDb
  (pull
    ([db pattern eid] (pull* db pattern eid nil))
    ([db pattern eid opts] (pull* db pattern eid opts)))
  (as-of [db t] (d/as-of db t))
  (view-t [db] (view-t* db))
  (history [db] (d/history db))
  (basis-t [db] (d/basis-t db))
  (ident [db a-eid] (d/ident db a-eid))
  (entid [db entity] (d/entid db entity)))
