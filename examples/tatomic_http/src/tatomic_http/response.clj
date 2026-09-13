(ns tatomic-http.response
  (:require [tatomic-http.accept :as accept]
            [cheshire.core :as json]
            [clojure.pprint :as pprint]))

(defn pprint-str [x]
  (with-out-str
    (pprint/pprint x)))

(defn classify-accepted-type [media-type]
  (case media-type
    "application/json" :json
    "application/edn"  :edn-application
    "text/plain"      :edn-text

    ;; wildcard-policy:
    ;; För browser/debug vill du defaulta till EDN som text.
    "application/*"   :edn-text
    "*/*"             :edn-text

    nil))

(defn choose-response-format [req]
  (let [accepts (accept/accept-priority (get-in req [:headers "accept"]))]
    (or (some #(classify-accepted-type (:media-type %)) accepts)
        :edn-text)))

(defn response [req data]
  (case (choose-response-format req)

    :json
    {:status 200
     :headers {"content-type" "application/json; charset=utf-8"}
     :body (json/encode data)}

    :edn-application
    {:status 200
     :headers {"content-type" "application/edn; charset=utf-8"}
     :body (pr-str data)}

    :edn-text
    {:status 200
     :headers {"content-type" "text/plain; charset=utf-8"}
     :body (pprint-str data)}))
