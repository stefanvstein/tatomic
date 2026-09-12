
(ns datomictools.dblog
  "Show transactions in bit more friendly manner"
  (:require [cvtool.connops :as d]))

(defn- distinctv [xs]
  (second
    (reduce (fn [[seen v] x]
              (if (contains? seen x)
                [seen v]
                [(conj seen x) (conj v x)]))
            [#{} []]
            xs)))

(defn- identity-attr-idents [db]
  (->> (d/q '[:find ?aident
              :where
              [?a :db/ident ?aident]
              [?a :db/unique :db.unique/identity]
              [?a :db/cardinality :db.cardinality/one]]
            db)
       (map first)
       sort
       vec))

(defn- eid->label [db id-attrs pull-pattern cache eid]
  (or (@cache eid)
      (let [m (d/pull db pull-pattern eid)
            label (cond
                    (:db/ident m)
                    (:db/ident m)
                    
                    :else
                    (let [present (into []
                                        (keep (fn [aident]
                                                (when (contains? m aident)
                                                  [aident (get m aident)])))
                                        id-attrs)]
                      (when (= 1 (count present))
                        (first present))))]
        (when label
          (swap! cache assoc eid label))
        label)))

(defn- make-eid->label [db]
  (let [id-attrs (identity-attr-idents db)
        pull-pattern (distinctv (into [:db/ident] id-attrs))
        cache (atom {})]
    (partial eid->label db id-attrs pull-pattern cache)))

(defn- render-entity [eid->label eid]
  (let [label (eid->label eid)]
    (cond
      (keyword? label) label
      (vector? label) (conj label eid)   ;; [a v eid]
      :else eid)))


(defn- datom->row
  [db eid->label [e a v _tx added?]]
  (let [m      (d/pull db [:db/ident {:db/valueType [:db/ident]}] a)
        aident (:db/ident m)
        vt     (get-in m [:db/valueType :db/ident])
        e*     (render-entity eid->label e)
        v*     (if (= :db.type/ref vt)
                 (render-entity eid->label v)
                 v)]
    [e* aident v* added?]))


(defn- tx-datoms->attrs
  "Tar raw tx-datoms (bara de där e==tx) och bygger en attr-map.
   Renderar a till ident och ref-värden till entity-label.
   Hanterar cardinality/one och /many."
  [db eid->label tx-datoms]
  (let [a-meta (fn [a-eid]
                 (d/pull db [:db/ident
                            {:db/valueType [:db/ident]}
                            :db/cardinality]
                         a-eid))
        render-v (fn [vt-ident v]
                   (if (= :db.type/ref vt-ident)
                     (render-entity eid->label v)
                     v))]
    (reduce
      (fn [m [_e a v _tx added?]]
        ;; vi tar bara med adds (retracts kan du välja att visa separat om du vill)
        (if (not added?)
          m
          (let [am (a-meta a)
                aident (:db/ident am)
                vt    (get-in am [:db/valueType :db/ident])
                card  (:db/cardinality am)
                v*    (render-v vt v)]
            (if (= card :db.cardinality/many)
              (update m aident (fnil conj []) v*)
              (assoc m aident v*)))))
      {}
      tx-datoms)))



(defn db-attr [a]
  (let [ns (namespace a)]
    (or (= "db" ns)
        (= "db.install" ns)
        (= :fressian/tag a))))

(defn txs
  "Options:
   {:start <start-t inclusive>
    :end <end-t exclusive>
    :skip-attributes pred e.g. db-attr"
  ([conn db] (txs conn db {}))
  ([conn db opts]
   (let [start (:start opts)
         end   (or (:end opts)
                   (inc (d/basis-t db)))
         accept-attributes (complement (or (:skip-attributes opts)
                                           (constantly false)))
         
         eid->label (make-eid->label db)]
     (->> (for [{:keys [t data]} (d/tx-range conn start end)]
           (let [
                 tx-datoms (filterv (fn [[e _a _v tx _added?]] (= e tx)) data)
                 other     (filterv (fn [[e a _v tx _added?]]
                                      (and (not= e tx)
                                           (accept-attributes
                                             (d/ident db a)))) data)
                 tx-attrs  (tx-datoms->attrs db eid->label tx-datoms)
                 changes (mapv #(datom->row db eid->label %) other)
                 tx-eid (:e (first tx-datoms))]
             (when (seq changes)
               (merge {:db/id tx-eid
                       :t t
                       :data changes}
                      tx-attrs))))
         (filter identity)))))

