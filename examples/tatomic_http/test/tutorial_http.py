import http.client
import json as jsonlib
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.parse
from contextlib import contextmanager


def available_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def wait_for_server(process, port, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"in-memory server exited with status {process.returncode}")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                return
        except OSError:
            time.sleep(0.1)
    raise TimeoutError(f"in-memory server did not start on port {port}")


@contextmanager
def tutorial_server():
    """Give the tutorial an isolated in-memory server and always clean it up."""
    port = available_port()
    print(f"Starting an in-memory Datomic Peer server on port {port}", flush=True)
    working_dir = Path(__file__).resolve().parents[1]
    process = subprocess.Popen(
        ["clojure", "-M:runmem", "--port", str(port)],
        cwd=working_dir
    )
    try:
        print(f"Waiting for server to open port:{port}")
        wait_for_server(process, port)
        yield f"http://localhost:{port}"
    finally:
        if process.poll() is None:
            print(f"Terminating server with port:{port}")
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


@contextmanager
def http_client(host, port):
    """Keep one HTTP connection open across all tutorial requests."""
    with StdlibKeepAliveClient(host, port) as client:
        yield client


class StdlibKeepAliveClient:
    """Small dependency-free HTTP client, with keep alive, used to keep the tutorial self-contained."""

    def __init__(self, host, port):
        self.host = host
        self.port = port
        self.conn = None

    def __enter__(self):
        """Open the connection once so subsequent requests demonstrate keep-alive."""
        self.conn = http.client.HTTPConnection(self.host, self.port, timeout=10)
        return self

    def __exit__(self, *_):
        """Close the shared connection when the tutorial has finished."""
        self.conn.close()

    def request(self, method, path, body, headers):
        """Send one request and recover the connection after a truncated stream."""
        self.conn.request(method, path, body=body, headers=headers)
        response = self.conn.getresponse()
        response_headers = response.getheaders()
        try:
            text = response.read().decode("utf-8")
        except http.client.IncompleteRead as exc:
            text = exc.partial.decode("utf-8")
            text += "\n;; client saw EOF while reading a streamed response\n"
            self.conn.close()
            self.conn = http.client.HTTPConnection(self.host, self.port, timeout=10)
        return response.status, response_headers, text


def print_traffic(direction, title, headers, body=None):
    """Print raw HTTP headers and body so transport details remain visible."""
    print(f"\n{direction} {title}")
    for name, value in headers:
        print(f"{name}: {value}")
    if body is not None:
        print()
        sys.stdout.write(body)
        if not body.endswith("\n"):
            print()
    sys.stdout.flush()


def edn(value):
    """Encode the small Python data subset used by the EDN request examples."""
    if isinstance(value, dict):
        return "{" + " ".join(f"{edn(key)} {edn(val)}" for key, val in value.items()) + "}"
    if isinstance(value, list):
        return "[" + " ".join(edn(item) for item in value) + "]"
    if isinstance(value, str):
        if value.startswith(":") or value.startswith("?") or value.startswith("$") or value == "*":
            return value
        return jsonlib.dumps(value)
    if isinstance(value, bool):
        return "true" if value else "false"
    if value is None:
        return "nil"
    return str(value)


def json(value):
    """Encode Python data as a JSON request body."""
    return jsonlib.dumps(value)


def parseJson(text):
    """Turn a JSON response body into ordinary Python data."""
    return jsonlib.loads(text)


def parseEdn(text):
    """Parse the EDN subset emitted by the tutorial server into Python data."""
    index = 0

    def skip_space():
        nonlocal index
        while index < len(text) and (text[index].isspace() or text[index] == ","):
            index += 1

    def read_token():
        nonlocal index
        start = index
        while index < len(text) and not text[index].isspace() and text[index] not in ",{}[]":
            index += 1
        return text[start:index]

    def read():
        nonlocal index
        skip_space()
        if index >= len(text):
            raise ValueError("unexpected end of EDN")

        marker = text[index]
        if marker == "{":
            index += 1
            result = {}
            while True:
                skip_space()
                if index < len(text) and text[index] == "}":
                    index += 1
                    return result
                key = read()
                result[key] = read()
        if marker == "[":
            index += 1
            result = []
            while True:
                skip_space()
                if index < len(text) and text[index] == "]":
                    index += 1
                    return result
                result.append(read())
        if marker == '"':
            value, consumed = jsonlib.JSONDecoder().raw_decode(text[index:])
            index += consumed
            return value

        token = read_token()
        if token.startswith("#"):
            return read()
        if token.startswith(":"):
            return token[1:]
        if token == "true":
            return True
        if token == "false":
            return False
        if token == "nil":
            return None
        try:
            return int(token)
        except ValueError:
            try:
                return float(token)
            except ValueError:
                return token

    value = read()
    skip_space()
    if index != len(text):
        raise ValueError(f"unexpected EDN at position {index}")
    return value


def http_request(
    base_url,
    client,
    method,
    path,
    body=None,
    accept="application/json",
    content_type="application/edn",
):
    """Add transport headers and print both sides of a tutorial request."""
    url = base_url + path
    headers = {
        "Accept": accept,
        "Content-Type": f"{content_type}; charset=utf-8",
    }
    print_traffic(">>>", f"{method} {url}", headers.items(), body)

    status, response_headers, text = client.request(method, path, body, headers)
    print_traffic("<<<", f"{status} {method} {path}", response_headers, text)
    return status, text


class Client:
    """Keep status handling and HTTP transport out of the endpoint examples."""

    def __init__(self, base_url, client):
        """Combine the display URL with the connected transport client."""
        self.base_url = base_url
        self.client = client

    def post(self, path, body, content_type="application/json", accept="application/json"):
        """Post an already encoded body and return the response text."""
        status, text = http_request(
            self.base_url,
            self.client,
            "POST",
            path,
            body,
            accept=accept,
            content_type=content_type,
        )
        assert status == 200, f"{path} returned {status}: {text}"
        return text

    def get(self, path, accept="application/json"):
        """Call a path-based helper and return the response text."""
        status, text = http_request(self.base_url, self.client, "GET", path, accept=accept)
        assert status == 200, f"{path} returned {status}: {text}"
        return text


SCHEMA = [
    {
        ":db/ident": ":person/id",
        ":db/valueType": ":db.type/string",
        ":db/cardinality": ":db.cardinality/one",
        ":db/unique": ":db.unique/identity",
    },
    {
        ":db/ident": ":person/name",
        ":db/valueType": ":db.type/string",
        ":db/cardinality": ":db.cardinality/one",
    },
    {
        ":db/ident": ":person/assignments",
        ":db/valueType": ":db.type/ref",
        ":db/cardinality": ":db.cardinality/many",
    },
    {
        ":db/ident": ":assignment/id",
        ":db/valueType": ":db.type/string",
        ":db/cardinality": ":db.cardinality/one",
        ":db/unique": ":db.unique/identity",
    },
    {
        ":db/ident": ":assignment/name",
        ":db/valueType": ":db.type/string",
        ":db/cardinality": ":db.cardinality/one",
    },
    {
        ":db/ident": ":assignment/days",
        ":db/valueType": ":db.type/long",
        ":db/cardinality": ":db.cardinality/one",
    },
]


QUERY = [
    ":find",
    "?name",
    "?assignment",
    ":where",
    ["?person", ":person/name", "?name"],
    ["?person", ":person/assignments", "?a"],
    ["?a", ":assignment/name", "?assignment"],
]


def more_people():
    """Build enough related sample data to make query and streaming results useful."""
    names = [
        ("grace", "Grace Hopper", "compiler"),
        ("katherine", "Katherine Johnson", "orbital"),
        ("margaret", "Margaret Hamilton", "apollo"),
        ("annie", "Annie Easley", "rocket"),
        ("dorothy", "Dorothy Vaughan", "fortran"),
        ("mary", "Mary Jackson", "wind"),
    ]
    tx = []
    for person_name_id, person_name, assignment_name_id in names:
        tx.extend(
            [
                {
                    ":person/id": person_name_id,
                    ":person/name": person_name,
                    ":db/id": person_name_id,
                },
                {
                    ":assignment/id": assignment_name_id,
                    ":assignment/name": f"{person_name.split()[0]}'s work",
                    ":assignment/days": len(person_name),
                    ":db/id": assignment_name_id,
                },
                {
                    ":person/id": person_name_id,
                    ":person/assignments": assignment_name_id,
                },
            ]
        )
    return tx


def run_tutorial(base_url, raw_client):
    """Run the ordered HTTP examples and assert each demonstrated result."""
    client = Client(base_url, raw_client)
    request = json(SCHEMA)
    response = client.post(
        "/transact", request, content_type="application/json"
    )
    schema_tx = parseJson(response)
    assert schema_tx["tx-eid"] > 0
    print("1. schema installed with JSON")

    ada_id = "ada"
    notes_id = "notes"

    request = json(
        [
            {
                ":person/id": ada_id,
                ":person/name": "Ada Lovelace",
                ":db/id": ada_id,
            },
            {
                ":assignment/id": notes_id,
                ":assignment/name": "Analytical notes",
                ":assignment/days": 12,
                ":db/id": notes_id,
            },
            {
                ":person/id": ada_id,
                ":person/assignments": notes_id,
            },
        ]
    )
    response = client.post(
        "/transact", request, content_type="application/json"
    )
    data_tx = parseJson(response)
    assert data_tx["tx-eid"] > schema_tx["tx-eid"]
    print("2. data transacted with JSON")


    request = json(more_people())
    response = client.post(
        "/transact", request, content_type="application/json"
    )
    batch_tx = parseJson(response)
    assert batch_tx["tx-eid"] > data_tx["tx-eid"]
    print("3. more data added over the same keep-alive connection")


    request = edn(
        {
            ":pattern": [
                ":person/name",
                {":person/assignments": [":assignment/name", ":assignment/days"]},
            ],
            ":eid": [":person/id", ada_id],
        }
    )
    response = parseJson( client.post(
        "/pull", request, content_type="application/edn"
    ))
    assert response["ret"]["person/name"] == "Ada Lovelace"
    assignment = response["ret"]["person/assignments"][0]
    assert assignment["assignment/name"] == "Analytical notes"
    assert assignment["assignment/days"] == 12
    print("4. pull followed a relation with EDN")


    request = edn(QUERY)
    response = client.post("/q", request, content_type="application/edn")
    query = parseJson(response)
    assert ["Ada Lovelace", "Analytical notes"] in query["ret"]
    print("5. q found related values with EDN")


    request = edn(
        {
            ":query": [
                ":find",
                "?name",
                ":where",
                ["?person", ":person/name", "?name"],
            ]
        }
    )
    request = edn([":find","?name", ":where", ["?person", ":person/name", "?name"]])
    streamed_response = client.post(
        "/q",
        request,
        content_type="application/edn",
        accept="application/edn",
    )
    streamed_query = parseEdn(streamed_response)
    assert ["Grace Hopper"] in streamed_query["ret"]
    assert ["Dorothy Vaughan"] in streamed_query["ret"]
    print("6. q streamed a larger result as EDN")

    request = '"[:find ?name :where [?person :person/name ?name]]"'
    streamed_response = client.post(
            "/q",
            request,
            content_type="application/edn",
            accept="application/edn",
        )
    streamed_query = parseEdn(streamed_response)
    assert ["Grace Hopper"] in streamed_query["ret"]
    assert ["Dorothy Vaughan"] in streamed_query["ret"]
    print("61. q streamed a larger result as EDN")

    person_id_request = json({":ident": [":person/id", ada_id]})
    person_id_response = client.post(
        "/entid", person_id_request, content_type="application/json"
    )
    person_id = parseJson(person_id_response)
    assert isinstance(person_id["ret"], int)

    attr_id_request = edn({":ident": ":person/id"})
    attr_id_response = client.post(
        "/entid", attr_id_request, content_type="application/edn"
    )
    attr_id = parseJson(attr_id_response)

    attr_ident_request = json({":eid": attr_id["ret"]})
    attr_ident_response = client.post(
        "/ident", attr_ident_request, content_type="application/json"
    )
    attr_ident = parseJson(attr_ident_response)
    assert attr_ident["ret"] == "person/id"
    print("7. entid and ident resolved database ids with mixed formats")

    sync_request = json({":t": batch_tx["db-after"]})
    sync_response = client.post(
        "/sync", sync_request, content_type="application/json"
    )
    synced = parseJson(sync_response)
    assert synced["ret"] >= batch_tx["db-after"]
    print("8. sync observed the transaction with JSON")

    tx_t_response = client.get("/tx-to-t/%d" % batch_tx["tx-eid"])
    tx_t = parseJson(tx_t_response)
    assert isinstance(tx_t, int)
    print("9. tx->t converted the transaction entity")

    tx_range_request = edn({":from": schema_tx["tx-eid"]})
    tx_range_response = client.post(
        "/tx-range",
        tx_range_request,
        content_type="application/edn",
        accept="application/edn",
    )
    tx_range = parseEdn(tx_range_response)
    assert any(
        datom[2] == "Grace Hopper"
        for transaction in tx_range["ret"]
        for datom in transaction["data"]
    )
    print("10. tx-range streamed the transaction log with EDN")

    roundtrip_tx_response = client.get("/t-to-tx/%d" % tx_t)
    roundtrip_tx = parseJson(roundtrip_tx_response)
    assert roundtrip_tx == batch_tx["tx-eid"]
    print("11. t->tx converted the logical t back to the transaction entity")


def main():
    """Own the temporary server and connection for one complete tutorial run."""
    with tutorial_server() as base_url:
        parsed_url = urllib.parse.urlsplit(base_url)
        host = parsed_url.hostname
        port = parsed_url.port
        print(f"Using {base_url}")
        with http_client(host, port) as raw_client:
            run_tutorial(base_url, raw_client)
    print("HTTP tutorial completed")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"FAILED: {exc}", file=sys.stderr)
        raise
