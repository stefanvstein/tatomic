(ns tatomic-http.response-test
  (:require [clojure.test :refer [deftest is]]
            [tatomic-http.body :as body]
            [tatomic-http.response :as response]))

(deftest json-responses-preserve-keywords
  (let [data {:ret [{:person/name "Ada" :person/role :role/author}]
              :t 1}
        req {:headers {"accept" "application/json"}}]
    (doseq [result [(response/response req data)
                    (response/ret-response-json data)]]
      (is (= data
             (body/read-body
              {:headers {"content-type" "application/json"}
               :body (java.io.StringReader. (:body result))}))))))
