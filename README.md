# tatomic

Tatomic is a small interface for database operations where callers use `t`
values instead of holding database values directly.

The public command API lives in `tatomic.core` and focuses on operations such
as `pull`, `qseq`, `transact`, `tx-range`, `entid`, `ident`, and `sync`. The
core does not depend on Datomic libraries directly. Datomic support is provided
by optional adapters.

The HTTP interface is kept aside as a separate example project in
`examples/tatomic_http`. It has its own `deps.edn` and shows how an application
can depend on tatomic and choose the Datomic Peer adapter at the edge.

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
(require '[tatomic.impl.peer])
(require '[tatomic.core :as tatomic])
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
(require '[tatomic.impl.client])
(require '[tatomic.core :as tatomic])
```

Load only the adapter you need to keep dependencies narrow.

## API

Require the public command namespace:

```clojure
(require '[tatomic.core :as tatomic])
```

### pull

`pull` reads one entity using the selected adapter's pull pattern syntax and
wraps the result in a Tatomic response map.

```clojure
(tatomic/pull conn {:pattern [:person/name]
                    :eid [:person/id "adam 1"]})
;; => {:ret {:person/name "Adam"}
;;     :t 1001
;;     :basis-t 1001}
```

Tatomic-specific request keys:

| key | meaning |
| --- | --- |
| `:pattern` | Required. Pull pattern passed to the adapter. |
| `:eid` | Required. Entity id, ident, or lookup ref. |
| `:t` | Optional. Read from the database value at this t. |
| `:timeout` | Optional. Milliseconds to wait for a requested future t. Defaults to `60000`. |
| `:io-context` | Optional. Passed to adapters that support IO stats/context. |

On success, `pull` returns `:ret`, the effective read `:t`, and the current
`:basis-t`. If `:pattern` or `:eid` is missing, or if the requested `:t` cannot
be reached before timeout, it returns `{:error ...}`.

For Datomic adapters, `:pattern` and `:eid` follow Datomic Pull semantics; see
the official Datomic Pull reference:
https://docs.datomic.com/query/query-pull.html

During local development, use `:local/root` instead of the Git coordinate:

```clojure
{:deps {stefanvstein/tatomic {:local/root "../tatomic"}}}
```

## Development

Start a REPL with:

```sh
clojure -M:dev
```

Start a development REPL where inline `unit-test` forms can call
`tatomic.test/prepare-test` with a Datomic Peer in-memory database:

```sh
clojure -M:dev:datomic-peer
```

In Emacs/CIDER with clojure-cli, jack in with aliases `:dev:datomic-peer`
(for example with `C-u M-x cider-jack-in-clj`, or by setting
`cider-clojure-cli-aliases` to `":dev:datomic-peer"`).

Run tests with:

```sh
clojure -M:test
```

Load the HTTP example from its own project directory with:

```sh
cd examples/tatomic_http
clojure -M
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
