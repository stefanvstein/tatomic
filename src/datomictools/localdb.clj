(ns datomictools.localdb
  (:import [java.io File]
           )
  (:require [datomic.client.api :as d]
            [datomic.local :as l]
            ))

(defn file-path [path]
  (if (= :mem path)
    :mem
    (-> (File. path)
        (.getAbsoluteFile)
        (.getCanonicalPath))))

(defn connect [system path db-name]
  (let [cfg {:server-type :datomic-local
             :system system
             :storage-dir (file-path path) }
        client (d/client cfg)
        ]
    (d/create-database client {:db-name db-name})
    (d/connect client {:db-name db-name})))

(defn connection-in-mem [system db-name]
  (connect system :mem db-name))

(comment
  (connect "mysystem" "./database" "mydb"))


(defn delete [system path db-name]
  
  (l/release-db {:system system :storage-dir (file-path path) :db-name db-name})
                 ;(d/delete-database client {:db-name db-name})
  )
