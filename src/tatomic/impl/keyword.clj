(ns tatomic.impl.keyword)

(defn qualified-keyword [key]
  (if (namespace (keyword key))
    (keyword key)
    (keyword "" (name key))))

(defn non-empty-namespace-key [key]
  (if (empty? (namespace key))
    (keyword (name key))
    key))
