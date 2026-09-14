(ns tatomic-http.run
  (:require [clojure.java.io :as io]
            [datomic.client.api :as d]
            [tatomic-http.server :as server]
            [tatomic.impl.client]))

(defn- arg [args option default]
  (or (some (fn [[k v]]
              (when (= k option)
                v))
            (partition 2 2 nil args))
      default))

(defn- absolute-path [path]
  (.getAbsolutePath (io/file path)))

(defn -main [& args]
  (let [port (parse-long (arg args "--port"
                              (or (System/getenv "TATOMIC_HTTP_PORT")
                                  "8091")))
        system (arg args "--system"
                    (or (System/getenv "TATOMIC_LOCAL_SYSTEM")
                        "tatomic-http-tutorial"))
        db-name (arg args "--db"
                     (or (System/getenv "TATOMIC_LOCAL_DB")
                         "tutorial"))
        storage-dir (absolute-path
                     (arg args "--storage-dir"
                          (or (System/getenv "TATOMIC_LOCAL_STORAGE_DIR")
                              ".datomic-local-run")))
        client (d/client {:server-type :datomic-local
                          :system system
                          :storage-dir storage-dir})]
    (d/create-database client {:db-name db-name})
    (server/start (d/connect client {:db-name db-name}) {:port port})
    (println "Tatomic HTTP tutorial server")
    (println "  url:" (str "http://localhost:" port))
    (println "  datomic-local storage:" storage-dir)
    (println "  system:" system)
    (println "  db:" db-name)
    (println "Press Ctrl-C to stop.")
    @(promise)))
