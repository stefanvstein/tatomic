(ns tatomic-http.core
  (:require [clojure.edn :as edn]
            [clojure.repl :refer [pst]]
            [tatomic.core :as tatomic])
  (:refer-clojure :exclude [sync]))



(defn db [conn]
  [200 (tatomic/current-t conn)])


(defn pull [conn edn]
  (if (string? edn)
    (try
      (pull conn (edn/read-string edn))
      (catch Exception e [400 (ex-message e)]))
    (try
      (let [res (tatomic/pull conn edn)]
        (if-let [e (:error res)]
          [400 e]
          [200 res]))
      (catch Exception e
                  [500 (ex-message e)]))))


(defn qseq [conn edn]
  (if (string? edn)
    (try
      (qseq conn (edn/read-string edn))
      (catch Exception e [400 (ex-message e)]))
    (try
      (let [res (tatomic/qseq conn edn)]
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
      [200 (tatomic/tx-range conn edn)]
      (catch Exception e [500 (ex-message e)]))))



(defn entid [conn edn]
  (if (string? edn)
    (try
      (entid conn (edn/read-string edn))
      (catch Exception e (.printStackTrace e) [400 (ex-message e)]))
    (try
      (let [res (tatomic/entid conn edn)]
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
      (let [res (tatomic/ident conn edn)]
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
      (let [res (tatomic/sync conn edn)]
        (if-let [e (:error res)]
          [400 e]
          [200 res]))
      (catch Exception e [500 (ex-message e)]))))

(defn transact [conn edn ]
  (if (string? edn)
    (try
      (transact conn (edn/read-string edn))
      (catch Exception e [400 (ex-message e)]))
    (try
      [200 (tatomic/transact conn edn)]
      (catch Exception e [500 (ex-message e)]))))

;; 
(comment
  (do
    (def peer-url "datomic:dev://localhost:4334/min-databas?password=datomic")
    (require '[datomic.api :as d])
    (d/create-database peer-url)
    (def conn (d/connect peer-url)))
  
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
