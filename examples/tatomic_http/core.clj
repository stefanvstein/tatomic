(ns tatomic-http.core
  (:require [clojure.edn :as edn]
            [clojure.repl :refer [pst]]
            [tatomic.ops :as d]
            [tatomic.test :refer [unit-test prepare-test]]
            [tatomic.transact :as transact]
            [tatomic.pull :as pull]
            [tatomic.qseq :as qseq]
            [tatomic.tx-range :as tx-range]
            [tatomic.db :as web-db]
            [clojure.test :refer [testing is]])
  (:refer-clojure :exclude [sync]))



(defn db [conn]
  [200 (d/basis-t (d/db conn))])


(defn pull [conn edn]
  (if (string? edn)
    (try
      (pull conn (edn/read-string edn))
      (catch Exception e [400 (ex-message e)]))
    (try
      (let [res (pull/pull conn edn)]
        (if-let [e (:error res)]
          [400 e]
          [200 res]))
      (catch Exception e
                  [500 (ex-message e)]))))


(unit-test
  (testing "pull requests"
    ((requiring-resolve 'tatomic.logging/set-root-level!) :info)
    ((requiring-resolve 'tatomic.logging/set-logger-level!) "datomic" :warn)
    (def conn (prepare-test "pull"))
    (def v (pull conn  "{:pattern [:person/name] :eid [:person/id \"adam 1\"] :io-context olle}"))
    (def ret (second v))
    (->> "Ok http status, string request"
         (is (= 200 (first v))))

    (is (= "Adam Andersson" (:person/name (:ret ret)))
        "Found name, string request")
    
    (is (= [400 ":pattern and :eid required for pull."] (pull conn {:pattern [:person/name]}))
        "require :eid"))
  :eoc)

(defn qseq [conn edn]
  (if (string? edn)
    (try
      (qseq conn (edn/read-string edn))
      (catch Exception e [400 (ex-message e)]))
    (try
      (let [res (qseq/qseq conn edn)]
        (if-let [e (:error res)]
          [400 e]
          [200 res]))
      (catch Exception e
        [500 (ex-message e)]))))


(defn tx-range [conn edn]
  (if (string? edn)
    (try
      (tx-range conn (edn/read-string edn))
      (catch Exception e
        (pst e)
        [400 (ex-message e)]))
    (try
      [200 (tx-range/tx-range conn edn)]
      (catch Exception e [500 (ex-message e)]))))



(defn entid [conn edn]
  (if (string? edn)
    (try
      (entid conn (edn/read-string edn))
      (catch Exception e (.printStackTrace e) [400 (ex-message e)]))
    (try
      (let [res (web-db/entid conn edn)]
        (if-let [e (:error res)]
          [400 e]
          [200 res]))
      (catch Exception e [500 (ex-message e)]))))

(defn ident [conn edn]
  (if (string? edn)
    (try
      (ident conn (edn/read-string edn))
      (catch Exception e (.printStackTrace e) [400 (ex-message e)]))
    (try
      (let [res (web-db/ident conn edn)]
        (if-let [e (:error res)]
          [400 e]
          [200 res]))
      (catch Exception e [500 (ex-message e)]))))

(defn sync [conn edn]
  (if (string? edn)
    (try
      (sync conn (edn/read-string edn))
      (catch Exception e (.printStackTrace e) [400 (ex-message e)]))
    (try
      (let [res (web-db/sync conn edn)]
        (if-let [e (:error res)]
          [400 e]
          [200 res]))
      (catch Exception e [500 (ex-message e)]))))

(defn transact [conn edn ]
  (if (string? edn)
    (try
      [200 (transact conn (edn/read-string edn))]
      (catch Exception e [400 (ex-message e)]))
    (try
      [200 (transact/transact conn edn)]
      (catch Exception e [500 (ex-message e)]))))

;; 
(comment
  (do
    (def peer-url "datomic:dev://localhost:4334/min-databas?password=datomic")
    (require '[datomictools.peerdb :as peer])    
    (def conn (peer/connect peer-url)))
  
  (pull conn  "{:pattern [*] :eid [:person/id \"erik-5\"] :io-context olle}")
  
  

  (qseq conn '{:query [:find ?e ?v :where [?e :person/name ?v]]
                :inputs [(history 1040)]
                })  
         
  (qseq conn '{:query {:find [?e] :where [[?e :person/id]] }} )
  (def old (dec (d/basis-t (d/db conn))))
  (def fut (inc (d/basis-t (d/db conn))))
  (pull conn  (format "{:pattern [*] :eid [:person/id \"erik-5\"] :t %d}" old))
  
  (qseq conn {:query {:find ['?e '?id] :in ['$ '?name]
                       :where [['?e :person/name '?name] ['?e :person/id '?id]]}
               :inputs [old "Erik"]} )
  (qseq conn {:query [:find '?e '?name '?id :in  '?name '$ '$foo
                       :where ['?e :person/name '?name] ['?e :person/id '?id]]
               :inputs ["Viktoria"  old old]
               :timeout 1000})
  
  (transact conn [{:person/name "Viktoria Rydberga" :person/id "Viktoria-id-29"}])


  (map (fn [a]
         (Thread/sleep 100)
         a) (d/qseq conn
                    {:query '[:find [?e ...] #_(pull ?e [*]) :where [?e :person/name]]
                     :args [(d/db conn)]}))


  (instance? clojure.lang.IPending (d/qseq conn
                                           {:query '[:find [?e ...] #_(pull ?e [*]) :where [?e :person/name]]
                                            :args [(d/db conn)]}))
  
  
  :eoc)
