# tatomic HTTP example

This is a standalone example project that depends on tatomic through
`:local/root` and chooses the Datomic Peer adapter at the application edge.

Start a REPL from this directory:

```sh
clojure -M
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

Stop it with:

```clojure
(server/stop)
```
