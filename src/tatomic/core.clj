(ns tatomic.core
  (:require [tatomic.impl.db :as db]
            [tatomic.impl.keyword :refer [qualified-keyword]]
            [tatomic.impl.ops :as d]
            [tatomic.impl.pull :as pull]
            [tatomic.impl.qseq :as qseq]
            [tatomic.impl.tx-range :as tx-range])
  (:refer-clojure :exclude [sync]))

(defn pull
  
  "Pull is a declarative way to make hierarchical selections of information 
  about entities. 

  `edn` requires:
  - `:pattern` Datomic pull pattern.
  - `:eid` entity id, ident, or lookup ref.

  Optional keys:
  - `:t` reads from the database value at that t instead of the current db.
  - `:timeout` waits this many milliseconds for a requested future t. Defaults
    to 60000.
  - `:io-context` passes an io-context keyword to adapters that support it.

  Returns `{:ret pulled-map :t read-t :basis-t current-basis-t}` on success, or
  `{:error message}` when validation fails or the requested t is unavailable.

  Tatomic does not define pull pattern syntax; it delegates that to the
  selected adapter, a datomic database. 
  See https://docs.datomic.com/query/query-pull.html"
  [conn edn]
  (pull/pull conn edn))

(defn qseq
  "Searches the database for entities matching a query. The query describes 
  which attributes, values, and relations should match with Datalog. The result 
  can be consumed step by step instead of requiring a fully realized collection.

  `edn` can be:
  - `{:query query}` where query is a Datomic query map or query vector.
  - `{:query query :inputs [...]}` for queries with an `:in` clause.
  - A query map, query vector, or a sequence whose first element is the query
    and the remaining elements are inputs.

  Tatomic supplies the current database for `$` when no explicit database input
  is provided. Database inputs can also be a t value, which will be translated 
  to the corresponding db, `(history)`, or `(history t)`.

  Optional keys:
  - `:timeout` waits this many milliseconds for requested database t values.
    Defaults to 60000.
  - `:io-context` passes an io-context keyword to adapters that support it.

  Returns `{:ret result-seq :basis-t current-basis-t}` on success, including
  `:t` when all database inputs resolve to the same t. Returns
  `{:error message}` when a requested database value is unavailable.

  Tatomic does not define Datalog query syntax; it delegates that to the
  selected adapter, a datomic database.
  see https://docs.datomic.com/query/query-executing.html"
  [conn edn]
  (qseq/qseq conn edn))

(defn tx-range
  "Reads transactions from the database log.

  `edn` can contain:
  - `:from` first transaction t/tx to include.
  - `:to` first transaction t/tx after the range.

  Returns `{:ret tx-seq}` where each transaction contains its `:data` datoms.
  Tatomic removes the transaction column from each datom and resolves attribute
  ids to idents, so datoms are returned as `[e a v added?]`.

  Range semantics are delegated to the selected adapter, a datomic database.
  See https://docs.datomic.com/transactions/transaction-log.html"
  [conn edn]
  (tx-range/tx-range conn edn))

(defn entid
  "Finds the numeric entity id for an ident, lookup ref, or entity value.

  Datomic stores entities by numeric ids, but code often refers to schema
  attributes and domain entities by keyword idents or lookup refs. `entid`
  resolves such a reference to the numeric entity id used by the database.

  `edn` requires:
  - `:ident` ident keyword, lookup ref, entity map, or entity id.

  Optional keys:
  - `:t` reads from the database value at that t instead of the current db.
  - `:time-out` waits this many milliseconds for a requested future t. Defaults
    to 60000.

  Returns `{:ret entity-id :t read-t :basis-t current-basis-t}` on success, or
  `{:error message}` when `:ident` is missing or the requested t is unavailable."
  [conn edn]
  (db/entid conn edn))

(defn ident
  "Finds the keyword ident for an entity id.

  Datomic uses keyword idents, such as `:person/name` or `:db/cardinality`, to
  name schema attributes and other database entities. `ident` looks up that
  keyword name from the numeric entity id.

  `edn` requires:
  - `:eid` entity id.

  Optional keys:
  - `:t` reads from the database value at that t instead of the current db.
  - `:time-out` waits this many milliseconds for a requested future t. Defaults
    to 60000.

  Returns `{:ret ident-keyword :t read-t :basis-t current-basis-t}` on success,
  or `{:error message}` when `:eid` is missing or the requested t is
  unavailable."
  [conn edn]
  (db/ident conn edn))

(defn sync
  "Waits until the connection can observe a database value.

  With `{:t t}`, waits until the database is available at least through that t.
  Without `:t`, syncs to the adapter's current reachable database value.

  Optional keys:
  - `:time-out` waits this many milliseconds. Defaults to 60000.

  Returns `{:ret basis-t}` with the synchronized basis t on success, or
  `{:error message}` if the requested t is not available before the timeout.

  Sync semantics are delegated to the selected adapter, a datomic database."
  [conn edn]
  (db/sync conn edn))

(defn current-t
  "Returns the current basis t for the connection.

  The basis t is Datomic's logical database time for the current database value.
  It can be used with functions that read from a specific `:t`, or to compare
  which database value two reads observed."
  [conn]
  (db/current-t conn))

(defn tx->t
  "Converts a transaction entity id to its logical t value.

  Datomic represents each transaction as an entity. `tx->t` returns the
  database t associated with that transaction entity id.

  Returns the adapter result directly."
  [conn tx-eid]
  (db/tx->t conn tx-eid))

(defn t->tx
  "Converts a logical t value to its transaction entity id.

  Datomic represents each transaction as an entity. `t->tx` returns the entity
  id for the transaction at the given database t.

  Returns the adapter result directly. Not every adapter supports this
  operation; the Datomic client adapter currently throws
  `UnsupportedOperationException`."
  [conn t]
  (db/t->tx conn t))

(defn transact
  "Submits transaction data and returns the applied transaction report.

  A transaction adds or retracts facts as one atomic change. The transaction
  data can use Datomic map forms, list forms, transaction functions, tempids,
  lookup refs, and idents.

  `edn` can be:
  - A transaction data collection directly.
  - `{:tx-data tx-data}`.

  Optional keys:
  - `:io-context` passes an io-context keyword to adapters that support it.

  Returns a transaction report with `:db-before`, `:db-after`, `:tx-data`, and
  `:tempids`. Tatomic returns `:db-before` and `:db-after` as basis t values,
  adds `:tx-eid`, and normalizes `:tx-data` datoms to `[e a v added?]` with
  attribute ids resolved to idents.

  Tatomic does not define transaction data syntax; it delegates that to the
  selected adapter, a datomic database.
  See https://docs.datomic.com/transactions/transaction-data-reference.html"
  [conn {:keys [tx-data io-context] :as all}]
  (let [tx-result (if tx-data
                    (if io-context
                      @(d/transact conn tx-data
                                   {:io-context (qualified-keyword io-context)})
                      @(d/transact conn tx-data))
                    @(d/transact conn all))
        db-after (:db-after tx-result)]
    (-> tx-result
        (assoc :tx-eid (nth (first (:tx-data tx-result)) 3))
        (update :db-after d/basis-t)
        (update :db-before d/basis-t)
        (update :tx-data (fn [datom]
                           (mapv (fn [[e a v _t added?]]
                                   [e (d/ident db-after a) v added?])
                                 datom))))))
