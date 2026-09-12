
(ns datomictools.dbtools
  (:require 
   [datomictools.connops :as d]
   [clojure.string :as str]
   [clojure.walk :as walk]))


(defn with-audit-tx
  "Returnerar tx-data med ev. audit-metadata tillagd på \"datomic.tx\".

  Alla audit-fält är optional.
  Om inga audit-nycklar ges, returneras tx-data oförändrad.

  Exempel:
    (with-audit-tx tx-data
      :user-id \"stefan\"
      :reason  \"import skills\"
      :source  \"cvtool\")"
  [tx-data & {:keys [user-id reason source request-id tags]}]
  (let [tx-map (cond-> {}
                 user-id    (assoc :tx/user user-id)
                 reason     (assoc :tx/reason reason)
                 source     (assoc :tx/source source)
                 request-id (assoc :tx/request-id request-id)
                 (seq tags) (assoc :tx/tags (vec tags)))]
    (if (empty? tx-map)
      tx-data
      (conj (vec tx-data)
            (assoc tx-map :db/id "datomic.tx")))))



(defn idents
  "Hämtar alla idents, med eller utan system idents."
  ([db] (idents db false))
  ([db include-system?]
   (let [xs (->> (d/q db '[:find ?e
                        :where [?e :db/ident]]
                      db)
                 (map first)
                 (map #(d/pull db '[:db/ident] %))
                 (map :db/ident))]
     (if include-system?
       xs
       (remove #(or (str/starts-with? (namespace %) "db.")
                    (contains? #{"db" "fressian"}
                               (namespace %)))
               xs)))))

(defn fetch-schema
  "Hämtar schema, med eller utan system schema från db"
  ([db]
   (fetch-schema db false))
  ([db include-system?]
   (let [pattern '[*
                   {:db/valueType [:db/ident]}
                   {:db/cardinality [:db/ident]}
                   {:db/unique [:db/ident]}]]
     (map #(d/pull db pattern %)
          (idents db include-system?)))))

(defn entity-history
  "Hämtar tidslinje för en entity via :db/id."
  [conn eid]
  (let [db (d/db conn)]
    (->> (d/q '[:find ?a ?v ?tx ?added
                :in $ ?e
                :where [?e ?a ?v ?tx ?added]]
              conn
              (d/history db)
              eid)
         (map (fn [[a v tx added]]
                (merge {:db/id eid
                        :db/ident (d/ident db a)
                        :value v
                        :tx tx
                        :t (d/tx->t conn tx)
                        :added? added}
                       (d/pull db '[:db/txInstant] tx))))
         (sort-by :t))))


(defn unwrap-eids [x]
  (walk/postwalk
    (fn [v]
      (if (and (map? v)
               (= (keys v) [:db/id]))
        (:db/id v)
        v))
    x))

(defn unwrap-idents
  "recursivly unwrap any map with sole :db/ident key to its value 
  (unwrap {:foo {:db/ident 42}} = {:foo 42}"
  [x]
  (walk/postwalk
    (fn [v]
      (if (and (map? v)
               (= (keys v) [:db/ident]))
        (:db/ident v)
        v))
    x))

(comment
  (and (= (unwrap-idents {:db/ident 42})
          42)
       (= (unwrap-idents {:foo {:db/ident 42}})
          {:foo 42})
       (= (unwrap-idents [{:foo {:db/ident 42}} {:bar {:db/ident 42}}])
          [{:foo 42} {:bar 42}])
       (= (unwrap-idents [{:db/not-me 345 :db/ident 34} {:foo {:db/ident 42}}])
          [{:db/not-me 345, :db/ident 34} {:foo 42}]))
  :end-comment)
