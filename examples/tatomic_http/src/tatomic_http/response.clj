(ns tatomic-http.response
  (:require [org.httpkit.server :as http]
            [tatomic-http.accept :as accept]
            [tatomic-http.body :as body]
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

(defn ret-response-edn [data req content-type]
  ;; http-kit channel responses are sent without a fixed Content-Length, so
  ;; clients receive these EDN/text result responses as chunked streaming bodies.
  (http/as-channel
   req
   {:on-open (fn [ch]
               (http/send! ch {:status 200
                               :headers {"Content-Type" content-type}
                               :body "\n{"} false)
               (try
                 (http/send! ch ":ret [" false)
                 (doseq [x (:ret data)]
                   (when-not (http/open? ch)
                     (throw (ex-info "client closed" {})))
                   (http/send! ch (str "\n  " (pr-str x)) false))
                 (http/send! ch "]" false)

                 (let [remaining
                       (apply str (for [k (keys (dissoc data :ret))]
                                    (str "\n " k " " (pr-str (get data k)))))]
                   (when (http/open? ch)
                     (http/send! ch (str remaining  "}\n") true)))
                 (catch Throwable t
                   (when (http/open? ch)
                     (http/send! ch
                                 (str (prn-str {:error (.getMessage t)}) "\n")
                                 false)
                     (http/close ch)))))}))

(defn ret-response-json [data]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/encode (update data :ret vec))})

(defn ret-response [req data]
  (case (choose-response-format req)
    :json (ret-response-json data)
    :edn-application (ret-response-edn data req "application/edn")
    :edn-text (ret-response-edn data req "text/plain")))

(defn ret-call [call conn req]
  (let [body (body/read-body req)
        [code data] (call conn body)]
    (if (= 200 code)
      (ret-response req data)
      {:status code
       :body data})))
