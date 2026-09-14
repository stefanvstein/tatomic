(ns tatomic-http.tutorial-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [datomic.client.api :as d]
            [tatomic-http.server :as server]
            [tatomic.impl.client])
  (:import [java.util.concurrent TimeUnit]))

(defn- test-port []
  (parse-long (or (System/getenv "TATOMIC_HTTP_TEST_PORT")
                  "18091")))

(defn- local-conn [db-name]
  (let [storage-dir (io/file ".datomic-local-test")
        client (d/client {:server-type :datomic-local
                          :system "tatomic-http-tutorial"
                          :storage-dir (.getAbsolutePath storage-dir)})]
    (d/create-database client {:db-name db-name})
    (d/connect client {:db-name db-name})))

(defn- run-python-tutorial [base-url]
  (let [builder (ProcessBuilder. ["python3" "test/tutorial_http.py"])
        _ (.directory builder (io/file "."))
        _ (.redirectErrorStream builder true)
        _ (.put (.environment builder) "TATOMIC_HTTP_URL" base-url)
        process (.start builder)
        finished? (.waitFor process 60 TimeUnit/SECONDS)
        output (slurp (.getInputStream process))]
    (when-not finished?
      (.destroyForcibly process))
    {:finished? finished?
     :exit (.exitValue process)
     :output output}))

(deftest http-tutorial-with-datomic-local
  (let [port (test-port)
        conn (local-conn (str "tutorial-" (System/currentTimeMillis)))
        base-url (str "http://localhost:" port)]
    (try
      (server/start conn {:port port})
      (let [{:keys [finished? exit output]} (run-python-tutorial base-url)]
        (println output)
        (is finished? output)
        (is (zero? exit) output)
        (is (re-find #"HTTP tutorial completed" output) output))
      (finally
        (server/stop)))))

(defn -main [& _]
  (let [{:keys [fail error]}
        (clojure.test/run-tests 'tatomic-http.tutorial-test)]
    (System/exit (+ fail error))))
