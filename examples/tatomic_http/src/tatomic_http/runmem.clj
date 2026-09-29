(ns tatomic-http.runmem
  (:require [datomic.api :as d]
            [tatomic-http.server :as server]
            [tatomic.impl.peer]
            [tatomic.logging :as logging]))

(defn- arg [args option default]
  (or (some (fn [[k v]]
              (when (= k option)
                v))
            (partition 2 2 nil args))
      default))

(defn -main [& args]
  (logging/set-root-level! :error)
  (let [port (parse-long (arg args "--port"
                              (or (System/getenv "TATOMIC_HTTP_PORT")
                                  "8091")))
        db-name (arg args "--db"
                     (or (System/getenv "TATOMIC_MEM_DB")
                         "tutorial"))
        uri (str "datomic:mem://" db-name)]
    (d/create-database uri)
    (server/start (d/connect uri) {:port port})
    (println "Tatomic HTTP tutorial server")
    (println "  url:" (str "http://localhost:" port))
    (println "  datomic peer:" uri)
    (println "Press Ctrl-C to stop.")
    @(promise)))
