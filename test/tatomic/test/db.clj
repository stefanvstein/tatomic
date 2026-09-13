(ns tatomic.test.db
  (:require [datomic.api :as d]
            [tatomic.core :as tatomic]
            [tatomic.impl.peer]))

(defn- schema-of-ident-map [ident-map]
  (->> ident-map
       (map (fn [[k v]]
              (assoc (or v {}) :db/ident k)))
       vec))

(defn- connect [url]
  (d/create-database url)
  (d/connect url))

(defn transact-schema [conn]
  (let [tx-data (schema-of-ident-map
                 {:person/name {:db/valueType :db.type/string
                                :db/cardinality :db.cardinality/one
                                :db/doc "A person name"}
                  :person/id {:db/valueType :db.type/string
                              :db/cardinality :db.cardinality/one
                              :db/unique :db.unique/identity
                              :db/doc "Potentially external id that make this person unique"}
                  :person/assignments {:db/valueType :db.type/ref
                                       :db/cardinality :db.cardinality/many
                                       :db/doc "Reference to assignments, a person refers to assignments. Many persons could work with the same assignment"}
                  :assignment/name {:db/valueType :db.type/string
                                    :db/cardinality :db.cardinality/one
                                    :db/doc "Name of assignment. A default name or a way for humans to refer to an assignment."}
                  :assignment/id {:db/valueType :db.type/string
                                  :db/cardinality :db.cardinality/one
                                  :db/unique :db.unique/identity
                                  :db/doc "Potentially external id that make this assignment unique"}
                  :assignment/days {:db/valueType :db.type/long
                                    :db/cardinality :db.cardinality/one
                                    :db/doc "number of days to add to the assignment length"}
                  :assignment/client {:db/valueType :db.type/ref
                                      :db/cardinality :db.cardinality/one
                                      :db/doc "Reference to the client of the assignment"}})]
    (tatomic/transact conn tx-data)))

(defn prepare-data [conn]
  [(tatomic/transact conn {:tx-data [{:person/id "adam 1" :person/name "Adam"}]})
   (tatomic/transact conn [{:person/id "bertil 1" :person/name "Bertil"}])
   (tatomic/transact conn {:tx-data [{:person/id "bertil 1" :person/name "Bertilsson"}
                                     {:person/id "cesar 1" :person/name "Cesar"}]
                           :io-context :foo})
   (tatomic/transact conn [{:assignment/id "ass 1" :assignment/name "Painting" :db/id "painting"}
                           {:person/id "adam 1" :person/assignments "painting"}
                           {:assignment/id "ass 2"
                            :assignment/name "Driving"
                            :db/id "driving"}
                           {:person/id "bertil 1" :person/assignments "driving"}])
   (tatomic/transact conn [{:person/id "adam 1" :person/name "Adam Andersson"}
                           [:db/add [:person/id "adam 1"]
                            :person/assignments [:assignment/id "ass 2"]]])])

(defn prepare-test [dbname]
  (let [uri (str "datomic:mem://" dbname)]
    (d/delete-database uri)
    (let [conn (connect uri)]
      (transact-schema conn)
      (prepare-data conn)
      conn)))
