(ns datomictools.peerdb 
  (:require
    [datomic.api :as d]))

(defn delete [url]
  (d/delete-database url))

(defn connect [url]
  (d/create-database url)
  (d/connect url))

(comment
  (connect  "datomic:mem://hello")
  (connect "datomic:dev://localhost:4334/min-databas?password=datomic"))
