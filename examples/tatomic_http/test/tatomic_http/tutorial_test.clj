(ns tatomic-http.tutorial-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.util.concurrent TimeUnit]))

(defn- run-python-tutorial []
  (let [builder (ProcessBuilder. ["python3" "test/tutorial_http.py"])
        _ (.directory builder (io/file "."))
        _ (.redirectErrorStream builder true)
        process (.start builder)
        finished? (.waitFor process 60 TimeUnit/SECONDS)
        _ (when-not finished?
            (.destroyForcibly process)
            (.waitFor process))
        output (slurp (.getInputStream process))]
    {:finished? finished?
     :exit (.exitValue process)
     :output output}))

(deftest http-tutorial-with-in-memory-peer
  (let [{:keys [finished? exit output]} (run-python-tutorial)]
    (println output)
    (is finished? output)
    (is (zero? exit) output)
    (is (re-find #"HTTP tutorial completed" output) output)))

(defn -main [& _]
  (let [{:keys [fail error]}
        (clojure.test/run-tests 'tatomic-http.tutorial-test)]
    (System/exit (+ fail error))))
