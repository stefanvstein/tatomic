(ns tatomic-http.body
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(defn- json-content? [req]
  (some-> (get-in req [:headers "content-type"])
          (str/lower-case)
          (str/includes? "application/json")))

(defn- json-token [s]
  (cond
    (str/starts-with? s ":") (keyword (subs s 1))
    (or (str/starts-with? s "?")
        (str/starts-with? s "$")
        (= "*" s)) (symbol s)
    :else s))

(defn- json-key [k]
  (if (string? k)
    (if (str/starts-with? k ":")
      (keyword (subs k 1))
      k)
    k))

(defn- json->edn-data [x]
  (walk/postwalk
   (fn [v]
     (cond
       (string? v) (json-token v)
       (instance? java.util.List v) (vec v)
       (map? v) (into {} (map (fn [[k x]]
                                [(json-key k) x])
                              v))
       :else v))
   x))

(defn read-body [req]
  (let [body (slurp (:body req) :encoding "UTF-8")]
    (if (json-content? req)
      (json->edn-data (json/parse-string body))
      (edn/read-string body))))
