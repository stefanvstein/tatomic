# tatomic

Tatomic is a small interface for database operations where callers use `t`
values instead of holding database values directly.

The core namespaces live under `tatomic.*` and focus on operations such as
`pull`, `qseq`, `transact`, `tx-range`, `entid`, `ident`, and `sync`. The core
does not depend on Datomic libraries directly. Datomic support is provided by
optional adapters.

The HTTP interface is kept aside as an example adapter in `examples/tatomic_http`.
Load it with the `:http-example` alias when you want to expose the same
t-oriented API over HTTP.

## Dependencies

Use only the core when another process owns the database access:

```clojure
{:deps {stefanvstein/tatomic
        {:git/url "https://github.com/stefanvstein/tatomic.git"
         :git/sha "..."}}}
```

Use Datomic Peer without pulling in `datomic.client.api`:

```clojure
{:deps {stefanvstein/tatomic
        {:git/url "https://github.com/stefanvstein/tatomic.git"
         :git/sha "..."}
        com.datomic/peer {:mvn/version "1.0.7622"}}}
```

Then load the peer adapter before calling `tatomic.*` operations:

```clojure
(require '[tatomic.datomic.peer])
(require '[tatomic.pull :as pull])
```

Use a Datomic Client based runtime by depending on a library that provides
`datomic.client.api`, for example Datomic Local:

```clojure
{:deps {stefanvstein/tatomic
        {:git/url "https://github.com/stefanvstein/tatomic.git"
         :git/sha "..."}
        com.datomic/local {:mvn/version "1.0.291"}}}
```

Then load the client adapter:

```clojure
(require '[tatomic.datomic.client])
(require '[tatomic.qseq :as qseq])
```

`tatomic.datomic` is available as a convenience namespace that loads both
adapters. Use `tatomic.datomic.peer` or `tatomic.datomic.client` when you want
to keep dependencies narrow.

During local development, use `:local/root` instead of the Git coordinate:

```clojure
{:deps {stefanvstein/tatomic {:local/root "../tatomic"}}}
```

## Development

Start a REPL with:

```sh
clojure -M:dev
```

Run tests with:

```sh
clojure -M:test
```

Load the HTTP example with:

```sh
clojure -M:http-example
```

Load only the Datomic Peer adapter without the HTTP example with:

```sh
clojure -M:datomic-peer
```

Load only the Datomic Client adapter without the HTTP example with:

```sh
clojure -M:datomic-client
```

Load both Datomic adapters with:

```sh
clojure -M:datomic
```
