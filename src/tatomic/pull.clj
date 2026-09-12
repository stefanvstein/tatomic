(ns tatomic.pull
  (:require [tatomic.time :refer [now]]
            [coretools.keyword :refer [qualified-keyword non-empty-namespace-key]]
            [tatomic.ops :as d]
           
            [tatomic.db :as db]
            [coretools.existing :refer [update-in-existing]]
            [clojure.test :refer [testing is]]
            [tatomic.test :refer [unit-test prepare-test]]))

(defn pull [conn edn]
  
  (let [{:keys [timeout t pattern eid io-context]
         :or {timeout 60000}} edn
        start (now)  
        db (db/db-of-t conn t timeout)]
    (cond
      (nil? db) {:error (str "Requested t " t
                             " not available for "
                             (- (now) start) " ms")}
      (not (and pattern eid)) {:error ":pattern and :eid required for pull."}    
      :else 
      (let [r (if io-context
                (d/pull db pattern eid {:io-context (qualified-keyword io-context)})
                {:ret (d/pull db pattern eid)})] 
        (-> r
            (assoc
              :t (or (d/view-t db)
                     (d/basis-t db))
              :basis-t (d/basis-t (d/db conn)))
            (update-in-existing
              [:io-stats :io-context]
              non-empty-namespace-key)))
      )))

(unit-test
  (testing "pull requests"
    ((requiring-resolve 'tatomic.logging/set-root-level!) :info)
    ((requiring-resolve 'tatomic.logging/set-logger-level!) "datomic" :warn)
    
    (def conn (prepare-test "pull"))
   
    (def ret (pull conn  {:pattern [:person/name]
                          :eid [:person/id "adam 1"]
                          :io-context "context"}))
    
    (is (:io-stats ret))
    (is (:basis-t ret) )
    (is (:t ret))
    (is (= "Adam Andersson" (:person/name (:ret ret)))
        "Found name using form request")
    
    (is (= {:error ":pattern and :eid required for pull."} (pull conn {:pattern [:person/name]}))
        "require :eid")
    (is (= {:error ":pattern and :eid required for pull."} (pull conn {:eid [:person/id "adam 1"]}))
        "require :pattern")

    (def previous-t (dec (:t ret)))
    (def query {:pattern [:person/name
                          {:person/assignments [:assignment/id]} ]
                :eid [:person/id "adam 1"]})
    (def old-assignments (-> (pull conn (assoc query :t previous-t))
                             :ret
                             :person/assignments))
    (def cur-assignments (-> (pull conn query)
                             :ret
                             :person/assignments))
    (is (= old-assignments [{:assignment/id "ass 1"}]))
    (is (= cur-assignments [{:assignment/id "ass 1"}
                            {:assignment/id "ass 2"}])))
  :eoc)
