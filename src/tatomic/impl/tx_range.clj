(ns tatomic.impl.tx-range
  (:require [tatomic.impl.ops :as d]))

(defn- without-tx [db [e a v _ added?]]
  [e (d/ident db a) v added?])

(defn- each-without-tx [db datoms]
  (mapv #(without-tx db %) datoms))

(defn tx-range [conn edn]
  (let [{:keys [from to]} edn
        db (d/db conn)
        range (->> (d/tx-range conn from to)
                   (map #(update % :data (partial each-without-tx db))))]
    {:ret range}))
