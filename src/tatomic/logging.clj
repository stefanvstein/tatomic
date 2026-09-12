(ns tatomic.logging
  (:import
    [org.slf4j LoggerFactory]
    [ch.qos.logback.classic Level LoggerContext]))

(defn set-logger-level!
  [logger-name level]
  (let [context ^LoggerContext (LoggerFactory/getILoggerFactory)]

    (.setLevel (.getLogger context logger-name)
               (case level
                 :debug Level/DEBUG
                 :info  Level/INFO
                 :warn  Level/WARN
                 :error Level/ERROR
                 nil))))

(defn set-root-level! [level]
  (set-logger-level! "ROOT" level))


