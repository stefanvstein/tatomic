(ns tatomic.ops
  (:refer-clojure :exclude [sync]))

(defprotocol Ops
  (db [conn])
  (transact [conn tx] [conn tx opts])
  (qseq [conn query-map])
  (pull [db pattern eid] [db pattern eid opts])
  (sync [conn] [conn t])
  (as-of [db t])
  (view-t [db])
  (history [db])
  (tx->t [conn tx-eid])
  (t->tx [conn t])
  (tx-range [conn start end])
  (basis-t [db])
  (ident [db a-eid])
  (entid [db entity]))
