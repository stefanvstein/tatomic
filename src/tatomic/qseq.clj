
(ns tatomic.qseq
  "wrapper around qseq where db is their t"
  (:require [tatomic.ops :as d]
            [coretools.existing :refer [update-in-existing]]
            [coretools.keyword :refer [qualified-keyword
                                       non-empty-namespace-key]]
            [clojure.test :refer [testing is]]
            [tatomic.db :as db]
            [tatomic.test :refer [unit-test
                              prepare-test]]
            [clojure.string :as str]
            ))


(defn- no-db-in-spec?
  "does any symbol in in-spec start with $"
  [in-spec]
  (not (some #(str/starts-with? % "$")
             (str in-spec))))

(unit-test
  (println "Hej")
  (-> (no-db-in-spec? ["1" 2 '?3])
      is)
  (-> (no-db-in-spec? ["1" 2 '$3])
      not
      is)
  (-> (no-db-in-spec? ["1" 2 '?3])
      is)
  (-> (no-db-in-spec? [])
      is)
  (-> (no-db-in-spec? nil)
      is))

(defn- normalize-inputs
  "add db if not "
  [inputs in-spec]
  (let [in-spec' (cond
                   #_#_(not (seq in-spec)) ['$]
                   (no-db-in-spec? in-spec) (into ['$] in-spec)
                   :else in-spec)
        inputs' (let [inputs (or inputs [nil])
                      first-db? (str/starts-with? (str (first in-spec')) "$")
                      one-input-missing? (= 1 (- (count in-spec')
                                                 (count inputs)))]
                  (if (and first-db?
                           one-input-missing?)
                    (into [nil] inputs)
                    inputs))
        db-indices (reduce-kv (fn [a i x]
                                (if (str/starts-with? (str x) "$")
                                  (conj a i)
                                  a))
                              #{}
                              in-spec')]
    {:in-spec in-spec'
     :inputs inputs'
     :db-indices db-indices}))

(unit-test
  (is (= {:in-spec '[$]
          :inputs [nil]
          :db-indices #{0}}
         (normalize-inputs nil nil)))
  (is (= {:in-spec '[$ olle ?adam],
          :inputs [nil 3 34], :db-indices #{0}}
         (normalize-inputs [3 34] ['olle  '?adam])))

  (is (= {:in-spec '[?olle $ $adam]
          :inputs [nil], :db-indices #{1 2}}
         (normalize-inputs nil ['?olle '$ '$adam])))

  (is (= {:in-spec '[$ olle ?adam pull-p [?tupple ?b] [xs ...] [[?a ?b]]],
          :inputs [nil], :db-indices #{0}}
         (normalize-inputs nil ['olle  '?adam 'pull-p '[?tupple ?b] '[xs ...] '[[?a ?b]]]))))

(defn- fill-with-db!
  "Replace each position in inputs that represent at database, 
with the correspoing database. Each database input is nil for 
  current, a t integer as a specific database value, or the seq 
(history nil), (history) for current history, or (hist t) for 
history until t.

db is current db, db-indices is the postions in inputs that 
represent databases, until is when a timout occur, if any db does 
not yet exist."
  [conn db until inputs db-indices]
  (map-indexed (fn [i x]
                 (if (db-indices i)
                   (cond
                     (nil? x) db

                     (or (= (list 'history) x)
                         (= (list 'history nil) x))
                     (d/history db)

                     (and (sequential? x)
                          (= 2 (count x))
                          (= 'history (first x)))
                     (let [now (System/currentTimeMillis)
                           remaining-time (- until now)]
                       (when-let [d (db/as-of conn
                                              db
                                              (second x)
                                              remaining-time)]
                         (d/history d)))

                     :else (let [now (System/currentTimeMillis)
                                 remaining-time (- until now)]
                             (db/as-of conn db x remaining-time)))
                   x))
               inputs))

(defn- single-element
  "First element when there is only one, or nil"
  [xs]
  (when (and (seq xs)
             (not (next xs)))
    (first xs)))

(defn- realize-query-inputs [conn db timeout inputs in-spec]
  (let [{:keys [in-spec inputs db-indices]} (normalize-inputs inputs in-spec)
        start (System/currentTimeMillis)
        with-dbs (fill-with-db! conn
                                db
                                (+ start timeout)
                                inputs db-indices)
        index-of-non-realized-db (->> (select-keys (vec with-dbs) db-indices)
                                      (sort-by first)
                                      (filter #(nil? (second %)))
                                      ffirst)]
    
    (if index-of-non-realized-db
      {:failed (str "Could not find requested database:"
                    (inputs index-of-non-realized-db)
                    " in "
                    (- (System/currentTimeMillis) start) " ms.") }
      (let [unique-ts (into #{} (map #(d/view-t (nth with-dbs %)) db-indices))]
        
        {:success (merge {:inputs with-dbs
                          :in-specs in-spec}
                         (when-let [t (single-element unique-ts)]
                           {:t t}))}))))

(defn- query-list-to-map [query]
  (if (map? query)
    query
    (-> (reduce (fn [[key a] x]
                    (if (keyword? x)
                      [x (assoc a x [])]
                      [key (update a key conj x)]))
                  [:find {}]
                  query)
        second)))


(unit-test 
  (let [target {:find [3 4], :where [[1 2 3] [5 6]]}
        generated (query-list-to-map [:find 3 4 :where [1 2 3] [5 6]])]
    (->> "query should become map format"
         (is (= target generated))))
  
  
  (let [target {:find [3 4], :where [[1 2 3]]}
        already-map (query-list-to-map {:find [3 4], :where [[1 2 3]]})]
    (->> "query should remain in map format"
         (is (= target already-map)))))


;; Normaliserar request-formen till en map. Själva queryn normaliseras senare.
(defn- edn-seq-to-map [edn]
  (cond
    (:query edn) edn

    (and
      (map? edn)
      (:find edn))
    {:query edn}

    (sequential? edn)
    (cond
      (and (next edn)
           (= :find (first edn))) {:query edn}
      
      (next edn)  {:query (first edn)
                   :inputs (next edn)}
      :else {:query (first edn)})
    
    :else edn))

(unit-test
  (defn- query-find-help? [x] (= {:query {:find :help}}
                                 (edn-seq-to-map x)))
  (is (query-find-help? {:find :help}))
  (is (not (query-find-help? {:query {:find :help} :other :things})))
  (is (not (query-find-help? [{:query {:find :help} :other :things}])))
  (is (query-find-help? [{:find :help}]))
  (is (= {:query [:find :help]} (edn-seq-to-map [:find :help])))
  (edn-seq-to-map [:find :help])
  (edn-seq-to-map {:find :help})
  )

(unit-test
  (is (= {:a 1} (edn-seq-to-map {:a 1})))
  (is (= {:query [1 2 3]} (edn-seq-to-map [[1 2 3]]))))
;; Den här ska förmodligen ta conn & edn
(defn qseq [conn edn]
  (let [{:keys [query inputs timeout io-context] :or {timeout 60000}} (edn-seq-to-map edn)
        query-as-map (query-list-to-map query)
        db (d/db conn)
        realized-result (realize-query-inputs conn
                                              db
                                              timeout
                                              inputs
                                              (:in query-as-map))]
    (if-let [error (:failed realized-result)]
      {:error error}
      (let [{:keys [inputs in-specs t] :as _all} (:success realized-result)
            query' (assoc query-as-map :in in-specs)
            query-map (cond-> {:query query' 
                               :args inputs}
                        io-context
                        (assoc :io-context (qualified-keyword io-context)))
            ret (d/qseq conn query-map)]
        (-> (cond-> {:ret ret
                     :basis-t (d/basis-t db)}
              t (assoc :t t))
            (update-in-existing [:io-stats :io-context]
                                non-empty-namespace-key))))))

(unit-test
  
  ((requiring-resolve 'tatomic.logging/set-root-level!) :info)
  ((requiring-resolve 'tatomic.logging/set-logger-level!) "datomic" :warn)
    
    
  (def conn (prepare-test "qseq"))
      
  (let [a (qseq conn '{:query {:find [?e]
                               :where [[?e :person/id]] }} )]
    (is (= 3 (count (:ret a))) "Got 3 items")
    (is (= (:basis-t a) (:t a)) "Answer is from latest t"))

  (let [a (qseq conn {:query '[:find ?id ?name
                               :where [?e :person/name ?name]
                               [?e :person/id ?id]]})]
    (->> "Query in seq format"
         (is (= [["cesar 1" "Cesar"]
                 ["bertil 1" "Bertilsson"]
                 ["adam 1" "Adam Andersson"]]
                (:ret a)))))
  
  (def current (d/basis-t (d/db conn)))

  (defn find-clause [in]
    {:find ['?id]
     :in in
     :where [['?e :person/name '?name]
             ['?e :person/id '?id]]})

  (testing "in and input combinations"
    (->> "in but no input"
         (is (= 3 (count (:ret (qseq conn '{:query {:find [?e]
                                                    :in [$]
                                                    :where [[?e :person/id]] }} ))))))
    
    (let [a (qseq conn {:query (find-clause ['$ '?name])
                        :inputs [nil "Adam Andersson"]})]
      (->> "nil as input"
           (is (= (list ["adam 1"])
                  (:ret a)))))

    
    (->> "no database input"
         (is (= (list ["adam 1"])
                (:ret (qseq conn {:query (find-clause ['$ '?name])
                                  :inputs ["Adam Andersson"]})))))

    (->> "only data in in and input"
         (is (= (list ["adam 1"])
                (:ret (qseq conn {:query (find-clause ['?name])
                                  :inputs ["Adam Andersson"]} )))))
    
    (->> "nil as input but missing"
         (is (= (list ["adam 1"])
                (-> (qseq conn {:query (find-clause ['?name])
                                :inputs [nil "Adam Andersson"]} )
                    :ret)))))    

    
  (def old (dec current))
  (def future-t (inc current))

  (def query {:find ['?name] :in ['$ '?id]
              :where [['?e :person/id '?id]
                      ['?e :person/name '?name]]})
  
  (->> "Should find the old name with decreased t as db"
       (is (= (list ["Adam"]) 
              (:ret (qseq conn {:query query
                                :inputs [old "adam 1"]})))))

  (is (= (list ["Adam Andersson"]) 
         (:ret (qseq conn {:query query 
                           :inputs [current "adam 1"]}))))

  (->> "A future database is not found, and times out"
       (is (-> (:error (qseq conn {:query query 
                                   :inputs [future-t "adam 1"]
                                   :timeout 100}))
               (str/includes? "Could not find requested database"))))

  
  (->> "Should not find fut even though it's not used"
       (is (-> (:error (qseq conn {:query [:find '?e '?name '?id :in  '?name '$ '$foo
                                           :where
                                           ['?e :person/name '?name]
                                           ['?e :person/id '?id]]
                                   :inputs ["adam 1" old future-t]
                                   :timeout 100}))
               (str/includes? "Could not find requested database"))))

  (def hist-query '[:find ?name ?id
                    :where
                    [?e :person/name ?name]
                    [?e :person/id ?id]])
  (is (->> (qseq conn {:query hist-query
                       :inputs [['history]]})
           :ret
           set
           (= #{["Bertil" "bertil 1"]
                ["Adam" "adam 1"]
                ["Adam Andersson" "adam 1"]
                ["Bertilsson" "bertil 1"]
                ["Cesar" "cesar 1"]})
           ))

  (is (->> (qseq conn {:query hist-query
                       :inputs [['history old]]})
           :ret
           set
           (= #{["Bertil" "bertil 1"]
                ["Adam" "adam 1"]
                ["Bertilsson" "bertil 1"]
                ["Cesar" "cesar 1"]})
           ))
  :eoc)
