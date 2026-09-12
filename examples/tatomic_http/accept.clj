


(ns tatomic-http.accept
  (:require [clojure.string :as str]
            ))



(defn parse-double-or [s default]
  (try
    (Double/parseDouble s)
    (catch Exception _
      default)))

(defn parse-accept-part [index part]
  (let [[media-type & params] (map str/trim (str/split part #";"))
        params-map (into {}
                         (keep (fn [p]
                                 (let [[k v] (map str/trim (str/split p #"=" 2))]
                                   (when-not (str/blank? k)
                                     [(str/lower-case k) v]))))
                         params)]
    {:media-type (str/lower-case media-type)
     :q          (parse-double-or (get params-map "q") 1.0)
     :params     (dissoc params-map "q")
     :index      index}))

(defn specificity [media-type]
  (let [[t st] (str/split media-type #"/" 2)]
    (cond
      (= media-type "*/*") 0
      (= st "*")           1
      :else                2)))

(defn accept-priority
  "Returnerar Accept-headern som prioriterad lista.

   Ex:
   [{:media-type \"application/edn\" :q 1.0 ...}
    {:media-type \"application/json\" :q 0.8 ...}]"
  [accept-header]
  (->> (str/split (or accept-header "*/*") #",")
       (map-indexed parse-accept-part)
       (remove #(str/blank? (:media-type %)))
       (remove #(<= (:q %) 0.0))
       (sort-by (juxt (comp - :q)
                      (comp - specificity :media-type)
                      :index))
       vec))

(defn request-accept-priority [req]
  (accept-priority (get-in req [:headers "accept"])))

(defn accepted? [accepts media-type]
  (some #(= media-type (:media-type %)) accepts))

(comment
  (accept-priority "application/edn;q=0.9, */*"))
