(ns tatomic.test)

(defmacro unit-test [& _])

(def ^:private prepare-test-help
  (str "tatomic.test/prepare-test is a development helper and needs the "
       "test fixture namespace on the classpath.\n\n"
       "From a terminal REPL, start with:\n"
       "  clojure -M:dev:datomic-peer\n\n"
       "For the test suite, run:\n"
       "  clojure -M:test\n\n"
       "In Emacs/CIDER with clojure-cli, jack in with aliases "
       ":dev:datomic-peer. One way is `C-u M-x cider-jack-in-clj` and "
       "enter `:dev:datomic-peer` when prompted for aliases. You can also "
       "set `cider-clojure-cli-aliases` to \":dev:datomic-peer\"."))

(defn prepare-test [dbname]
  (let [prepare (try
                  (require 'tatomic.test.db)
                  (or (resolve 'tatomic.test.db/prepare-test)
                      (throw (ex-info "Missing prepare-test var."
                                      {:helper 'tatomic.test.db/prepare-test})))
                  (catch Throwable e
                    (throw (ex-info prepare-test-help
                                    {:helper 'tatomic.test.db/prepare-test
                                     :cause-class (class e)
                                     :cause-message (ex-message e)}))))]
    (prepare dbname)))
