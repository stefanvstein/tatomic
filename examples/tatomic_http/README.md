# tatomic HTTP example

This is a standalone example project that depends on tatomic through
`:local/root` and chooses the Datomic Peer adapter at the application edge.

Run the tutorial server against Datomic Local from this directory:

```sh
clojure -M:run
```

By default it listens on `http://localhost:8091`, creates/uses db `tutorial`,
and stores Datomic Local data in `.datomic-local-run`.

You can override the defaults with command line options:

```sh
clojure -M:run --port 18091 --db my-db --system my-system --storage-dir .datomic-local-run
```

Or with environment variables:

```sh
TATOMIC_HTTP_PORT=18091 TATOMIC_LOCAL_DB=my-db clojure -M:run
```

Start a Peer REPL from this directory:

```sh
clojure -M:peer
```

Load the server namespace:

```clojure
(require '[tatomic-http.server :as server])
```

Create or obtain a Datomic Peer connection, then start the HTTP server:

```clojure
(require '[datomic.api :as d])
(def conn (d/connect "datomic:dev://localhost:4334/my-db?password=datomic"))
(server/start conn)
```

Use Datomic Local by choosing the Local alias and loading the client adapter:

```sh
clojure -M:local
```

```clojure
(require '[datomic.client.api :as d])
(require '[tatomic.impl.client])

(def client (d/client {:server-type :datomic-local
                       :system "my-local-system"
                       :storage-dir "/absolute/path/to/.datomic-local"}))
(d/create-database client {:db-name "my-db"})
(def conn (d/connect client {:db-name "my-db"}))
(server/start conn)
```

Stop it with:

```clojure
(server/stop)
```

## Tutorial test

Run the tutorial-style HTTP test from this directory:

```sh
clojure -M:test -m tatomic-http.tutorial-test
```

The test starts the HTTP server against a fresh Datomic Local database stored in
`.datomic-local-test`, then runs `test/tutorial_http.py` as a small client
tutorial. The Python client installs a schema, transacts data, and exercises
`pull`, `qseq`, `entid`, `ident`, `sync`, `tx->t`, `t->tx`, and `tx-range`.

The Python tutorial is written with ordinary Python dictionaries and lists.
At the HTTP edge it converts those structures to `application/json` or
`application/edn`, switching formats across the tutorial so the transcript shows
both request styles without repeating every step. If `httpx` is installed it is
used as the HTTP client; otherwise the script falls back to Python's standard
library so the example remains runnable without extra Python setup.

The tutorial uses a single keep-alive HTTP client session for the whole run. It
also asks selected endpoints for `application/edn` responses so `/q` and
`/txrange` use the streaming response path; the transcript prints the request
and response traffic as it goes.

## Python tutorial walkthrough

The Python tutorial is meant to read like a small guided HTTP session. Each
step prints the outgoing request and the incoming response before moving on.

1. Install the schema with JSON.

   The first `/transact` call sends schema attributes for people and
   assignments. JSON object keys are written as ordinary strings such as
   `"db/ident"`; the HTTP example turns them into EDN keywords before handing
   the request to tatomic.

2. Add one person and one assignment with JSON.

   The second `/transact` call creates Ada Lovelace, creates an assignment, and
   connects them with `:person/assignments`. The tutorial gives each run a
   unique id suffix, so the same local database can be reused without turning
   the transaction into a no-op.

3. Add a larger batch over the same keep-alive connection.

   A second data transaction inserts several more people and assignments. This
   gives later query and transaction-log examples enough data to be useful, and
   shows that the client keeps the same HTTP connection open across requests.

4. Pull one entity and its related data with EDN.

   The `/pull` request asks for Ada's name and follows the
   `:person/assignments` relation to include assignment fields. This
   demonstrates hierarchical selection from an entity and its relations.

5. Query related values with EDN.

   The `/q` request sends a plain query vector beginning with `:find`. Tatomic
   normalizes that vector to the request shape the adapter expects, supplies the
   current database input, and returns matching person and assignment names.

6. Ask for an EDN query response.

   The next `/q` call requests `Accept: application/edn`. The server returns an
   EDN response instead of JSON, exercising the streaming response path used for
   larger query results.

7. Resolve entity ids and idents with mixed request formats.

   `/entid` turns a lookup ref such as `[:person/id "..."]` into a numeric
   entity id. A second `/entid` resolves the schema ident `:person/id`, and
   `/ident` turns that numeric id back into the keyword ident name.

8. Synchronize to a known database time.

   `/sync` waits until the connection can observe the database value produced
   by the batch transaction and returns the synchronized basis t.

9. Convert between transaction entity ids and logical t values.

   `/tot/:tx` converts the transaction entity id from the batch transaction to
   its logical t. Later, `/totx/:t` converts that t back to the same transaction
   entity id.

10. Read the transaction log as EDN.

    `/txrange` streams the transactions from the schema transaction onward. The
    response contains transaction entries with normalized datoms of the form
    `[e a v added?]`, where attribute ids have been resolved to idents.

11. Finish with a round trip from t back to tx.

    The final `/totx/:t` call verifies that the logical t from step 9 maps back
    to the batch transaction entity id. This closes the tutorial with a small
    consistency check across the transaction-log helpers.

## Request normalization

Tatomic normalizes a few request shapes before delegating to the selected
adapter. For `/q`, these are equivalent:

```edn
[:find ?name :where [?e :person/name ?name]]
```

```edn
{:query [:find ?name :where [?e :person/name ?name]]}
```

For queries with explicit inputs, use `{:query query :inputs [...]}` or send a
sequence whose first element is the query and whose remaining elements are the
inputs. If the query does not declare an explicit database input, tatomic
supplies the current database for `$`. Database inputs can also be a t value,
`(history)`, or `(history t)`.

The HTTP example also normalizes JSON request bodies into EDN-like data:

- Object keys become keywords, so `"person/id"` and `":person/id"` both become
  `:person/id`.
- String values beginning with `:` become keywords.
- String values beginning with `?` or `$`, and the string `"*"`, become
  symbols for Datalog queries.
- JSON arrays become vectors.

That means JSON can use ordinary object keys while still expressing Datomic
keywords and Datalog variables in values:

```json
{
  "query": [":find", "?name", ":where", ["?e", ":person/name", "?name"]]
}
```
