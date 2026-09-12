(ns datomictools.schema )


(def value-type :db/valueType)
(def string :db.type/string)
(def reference :db.type/ref)
(def long-num :db.type/long )
(def cardinality :db/cardinality)
(def one :db.cardinality/one)
(def many :db.cardinality/many)
(def doc :db/doc)
(def unique :db/unique)
(def by-identity :db.unique/identity)
(def ident :db/ident)
(def tuple :db.type/tuple)
(def tuple-attr :db/tupleAttrs)
(def uuid :db.type/uuid)
(def keyword-type :db.type/keyword)



(defn of-ident-map [ident-map]
  (->> ident-map
       (map (fn [[k v]]
              (assoc (or v {}) :db/ident k)))
       vec))

(defn ident-map [schema-maps]
  (reduce (fn [a x]
              (if-let [k (:db/ident x)]
                (assoc a k (dissoc x :db/ident))
                a))
          {} 
          schema-maps))

(comment
  
  (ident-map (of-ident-map {:person/name {value-type string
                                          cardinality one
                                          doc "A person name"}
                            :person/id {value-type string
                                        cardinality one
                                        unique by-identity
                                        :db/doc "Potentially external id that make this person unique"}}
                          ))
  )

