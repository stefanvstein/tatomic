(ns tatomic.tx-range
  (:require [tatomic.ops :as d]))

(defn- without-tx [db [e a v _ added?]]
  [e (d/ident db a) v added?])

(defn- each-without-tx [db datoms]
  (mapv #(without-tx db %) datoms))

(defn tx-range [conn edn]
  (let [{:keys [from to]} edn
        db (d/db conn)
        range (->> (d/tx-range conn from to)
                   (map #(update % :data each-without-tx db)))]
    {:ret range}))
