(ns tatomic.impl.db
  (:require [tatomic.impl.ops :as d])
  (:refer-clojure :exclude [sync]))

(defn as-of [conn db t timeout]
  (let [basis (d/basis-t db)]
    (if (integer? t)
      (if (<= basis t)
        (let [enough-db (deref (d/sync conn t) timeout :timeout)]
          (if (= :timeout enough-db)
            nil       
            (d/as-of enough-db t)))
        (d/as-of db t))
      (d/as-of db t))))

(defn db-of-t [conn t timeout]
  (let [db-now (d/db conn)]
    (if t
      (as-of conn db-now t timeout)
      db-now)))

(defn current-t [conn]
  (d/basis-t (d/db conn)))

(defn tx->t [conn tx-eid]
  (d/tx->t conn tx-eid))

(defn t->tx [conn t]
  (d/t->tx conn t))

(defn entid [conn edn]
  (let [{:keys [ident t time-out]
         :or {time-out 60000}} edn
        start (System/currentTimeMillis)
        db (db-of-t conn t time-out)]
    (cond
      (nil? db) {:error (str "Requested t " t " not available for "
                             (- (System/currentTimeMillis) start) " ms.")}
      (not ident) {:error "ident required for entid."}
      :else {:ret (d/entid db ident)
             :t (or (d/view-t db)
                    (d/basis-t db))
             :basis-t (d/basis-t (d/db conn))})))

(defn ident [conn edn]
  (let [{:keys [eid t time-out]
         :or {time-out 60000}} edn
        start (System/currentTimeMillis)
        db (db-of-t conn t time-out)]
    (cond
      (nil? db) {:error (str "Requested t " t " not available for "
                             (- (System/currentTimeMillis) start) " ms.")}
      (not eid) {:error "eid required for ident."}
      :else {:ret (d/ident db eid)
             :t (or (d/view-t db)
                    (d/basis-t db))
             :basis-t (d/basis-t (d/db conn))})))

(defn sync [conn edn]
  (let [{:keys [t time-out]
         :or {time-out 60000}} edn
        start (System/currentTimeMillis)
        db (deref (if t
                    (d/sync conn t)
                    (d/sync conn))
                  time-out nil)]
    (cond
      (and (nil? db) t) {:error (str "Requested t " t " not available for "
                                     (- (System/currentTimeMillis) start) " ms.")}
      (nil? db) {:error (str "Could not sync in  "
                             (- (System/currentTimeMillis) start) " ms.")}
      :else {:ret (d/basis-t db)})))
