# tatomic HTTP example

This is a standalone example project that depends on tatomic through
`:local/root`. It can use either the Datomic Peer or Client adapter at the
application edge.

Run the Python tutorial from this directory:

```sh
python3 test/tutorial_http.py
```

The tutorial chooses a free port, starts `tatomic-http.runmem` against an
in-memory Datomic Peer database, and stops the server when it finishes.

You can also run the in-memory server separately with `clojure -M:runmem`, but
the Python tutorial always manages its own server.

For a persistent tutorial server backed by Datomic Local, use `run`:

```sh
clojure -M:run
```

It listens on `http://localhost:8091`, creates/uses db `tutorial`, and stores
Datomic Local data in `.datomic-local-run`.

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

The test runs `test/tutorial_http.py` without an endpoint, exercising its
self-started in-memory Datomic Peer server. The Python client installs a schema,
transacts data, and exercises `pull`, `qseq`, `entid`, `ident`, `sync`, `tx->t`,
`t->tx`, and `tx-range`.

The Python tutorial is written with ordinary Python dictionaries and lists.
Its small EDN formatter writes strings beginning with `:` as keyword literals
and strings beginning with `?` as logical variable literals. Other strings
stay quoted. Explicit symbols such as `pull`, `$`, and `*` use the `Symbol`
string subclass. Tuples become EDN lists, for example
`(Symbol("pull"), LogicalVar("person"), [Keyword("person/name")])`.
At the HTTP edge it converts those structures to `application/json` or
`application/edn`, switching formats across the tutorial so the transcript shows
both request styles without repeating every step. If `httpx` is installed it is
used as the HTTP client; otherwise the script falls back to Python's standard
library so the example remains runnable without extra Python setup.

The tutorial uses a single keep-alive HTTP client session for the whole run. It
also asks selected endpoints for `application/edn` responses so `/q` and
`/tx-range` exercise the chunked streaming EDN response path; the transcript
prints the request and response traffic as it goes.

## Python tutorial walkthrough

The Python tutorial is meant to read like a small guided HTTP session. Each
step prints the outgoing request and the incoming response before moving on.

1. Install the schema with JSON.

   The first `/transact` call sends schema attributes for people and
   assignments. The Python code writes Clojure keywords explicitly as strings,
   using constants such as `ident = ":db/ident"` above the schema literal,
   so the request data matches what is sent over the wire.

2. Add one person and one assignment with JSON.

   The second `/transact` call creates Ada Lovelace, creates an assignment, and
   connects them with `:person/assignments`. It uses fixed ids and is intended
   to run once against a fresh database; clear a persistent database before
   running it again.

3. Add a larger batch over the same keep-alive connection.

   A second data transaction inserts several more people and assignments. This
   gives later query and transaction-log examples enough data to be useful, and
   shows that the client keeps the same HTTP connection open across requests.

4. Pull one entity and its related data with EDN.

   The `/pull` request asks for Ada's name and follows the
   `:person/assignments` relation to include assignment fields. This
   demonstrates hierarchical selection from an entity and its relations.

5. Query related values with JSON.

   The `/q` request sends a JSON array beginning with the string `":find"`. Tatomic
   normalizes that vector to the request shape the adapter expects, supplies the
   current database input, and returns matching person and assignment names.

6. Ask for an EDN query response.

   The next `/q` call requests `Accept: application/edn`. The server returns an
   EDN response instead of JSON. For responses whose main value is `:ret`, EDN
   and text/plain are sent as chunked streaming bodies; JSON is buffered into a
   normal response body.

7. Resolve entity ids and idents with mixed request formats.

   `/entid` turns a lookup ref such as `[:person/id "..."]` into a numeric
   entity id. A second `/entid` resolves the schema ident `:person/id`, and
   `/ident` turns that numeric id back into the keyword ident name.

8. Synchronize to a known database time.

   `/sync` waits until the connection can observe the database value produced
   by the batch transaction and returns the synchronized basis t.

9. Convert between transaction entity ids and logical t values.

   `/tx-to-t/:tx` converts the transaction entity id from the batch transaction
   to its logical t. Later, `/t-to-tx/:t` converts that t back to the same
   transaction entity id.

10. Read the transaction log as EDN.

    `/tx-range` streams the transactions from the schema transaction onward
    when the client asks for EDN or text/plain. The response contains
    transaction entries with normalized datoms of the form `[e a v added?]`,
    where attribute ids have been resolved to idents.

11. Finish with a round trip from t back to tx.

    The final `/t-to-tx/:t` call verifies that the logical t from step 9 maps
    back to the batch transaction entity id. This closes the tutorial with a
    small consistency check across the transaction-log helpers.

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

The HTTP server treats JSON as a transport format for Datomic-shaped data. It
does not guess that a string such as `"query"` or `"person/id"` should be a
keyword. Keywords and Datalog symbols must be explicit:

- JSON object keys beginning with `:` become keywords.
- JSON string values beginning with `:` become keywords.
- JSON string values beginning with `?` or `$`, and the string `"*"`, become
  symbols for Datalog queries.
- JSON arrays become vectors.

That means JSON sent to the server should carry Datomic/EDN syntax in strings:

```json
{
  ":query": [":find", "?name", ":where", ["?e", ":person/name", "?name"]]
}
```

If a value must literally be a string such as `":name"` or `"?name"`, send an
raw EDN request body with explicitly quoted strings; the tutorial's EDN
formatter also interprets the `:` and `?` prefixes as literals.
The Python tutorial uses ordinary strings such as `":person/id"` in JSON
requests and for reading JSON responses. Its EDN request structures use
explicit `Keyword("person/id")` and `LogicalVar("name")` values, with
`Symbol("pull")` for other symbols. The EDN formatter also accepts plain
strings with `:` or `?` prefixes and writes them directly as literals.

JSON responses preserve the leading `:` on keyword keys and values, too.
For example, `/ident` returns `{":ret": ":person/id", ...}`, and a pull
result uses keys such as `":person/name"`. Cheshire is configured with
`:key-fn str`; keyword values are converted to their full string representation
before encoding. This keeps the keyword convention consistent in requests and
responses. The Python EDN parser currently returns keyword names without `:`,
so EDN response assertions use keys such as `"ret"`.
