(ns tatomic.impl.existing)

(defmacro update-existing
  [m ks f & args]
  `(let [m# ~m
         ks# ~ks]
     (if (contains? m# ks#)
       (update-in m# ks# ~f ~@args)
       m#)))

(defn contains-in?
  [m ks]
  (loop [m m
         ks ks]
    (if (empty? ks)
      true
      (let [k (first ks)]
        (if (and (associative? m)
                 (contains? m k))
          (recur (get m k) (rest ks))
          false)))))

(defmacro update-in-existing
  [m ks f & args]
  `(let [m#  ~m
         ks# ~ks]
     (if (contains-in? m# ks#)
       (update-in m# ks# ~f ~@args)
       m#)))
