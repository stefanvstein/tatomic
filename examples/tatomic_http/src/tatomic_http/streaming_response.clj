(ns tatomic-http.streaming-response
  (:require [org.httpkit.server :as http]
            [cheshire.core :as json]
            [tatomic-http.body :as body]
            [tatomic-http.response :as response]))

(defn response-edn [data req content-type]
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

(defn json-elements! [ch xs write-json]
  (loop [xs (seq xs)]
    (when-not (http/open? ch)
      (throw (ex-info "client closed" {})))
    (when xs
      (if-let [next-xs (next xs)]
        (do (http/send! ch (str "\n  " (write-json (first xs)) ",") false)
            (recur next-xs))
        (http/send! ch (str "\n  " (write-json (first xs))) false)))))

(defn response-json [data req]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/encode (update data :ret vec))})


(defn response [data req]
  (case (response/choose-response-format req)
        :json (response-json data req)
        :edn-application (response-edn data req "application/edn")
        :edn-text (response-edn data req "text/plain")))

(defn ret-call [call conn req]
  (let [body (body/read-body req)
        [code data] (call conn body)]
    (if (= 200 code)
      (response data req)
      {:status code
       :body data})))
