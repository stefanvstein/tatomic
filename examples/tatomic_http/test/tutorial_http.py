import json
import os
import sys
import http.client
import uuid
import urllib.error
import urllib.parse
from contextlib import contextmanager

try:
    import httpx
except ModuleNotFoundError:
    httpx = None


BASE_URL = os.environ.get("TATOMIC_HTTP_URL", "http://localhost:8091")
RUN_ID = os.environ.get("TATOMIC_TUTORIAL_RUN_ID", uuid.uuid4().hex[:8])


def tutorial_id(name):
    return f"{name}-{RUN_ID}"


@contextmanager
def http_client():
    if httpx is not None:
        print("HTTP client: httpx.Client keep-alive session")
        with httpx.Client(timeout=10) as client:
            yield client
    else:
        print("HTTP client: http.client.HTTPConnection keep-alive session")
        with StdlibKeepAliveClient(BASE_URL) as client:
            yield client


class StdlibKeepAliveClient:
    def __init__(self, base_url):
        parsed = urllib.parse.urlsplit(base_url)
        self.host = parsed.hostname
        self.port = parsed.port or (443 if parsed.scheme == "https" else 80)
        self.conn = None

    def __enter__(self):
        self.conn = http.client.HTTPConnection(self.host, self.port, timeout=10)
        return self

    def __exit__(self, *_):
        self.conn.close()

    def request(self, method, url, body, headers):
        path = urllib.parse.urlsplit(url).path
        self.conn.request(method, path, body=body, headers=headers)
        response = self.conn.getresponse()
        try:
            text = response.read().decode("utf-8")
        except http.client.IncompleteRead as exc:
            text = exc.partial.decode("utf-8")
            text += "\n;; client saw EOF while reading a streamed response\n"
            self.conn.close()
            self.conn = http.client.HTTPConnection(self.host, self.port, timeout=10)
        return response.status, text


def pretty_body(text):
    if text is None or text == "":
        return ""
    try:
        return json.dumps(json.loads(text), indent=2, ensure_ascii=False)
    except Exception:
        return text


def print_traffic(direction, title, body=None):
    print(f"\n{direction} {title}")
    if body:
        print(pretty_body(body))
    sys.stdout.flush()


def to_edn_wire_data(value):
    """Convert plain Python data to the tagged strings this HTTP example uses for EDN.

    Dict keys are written without EDN prefixes for readability, so
    {"person/id": "ada"} becomes {:person/id "ada"} on the wire.
    Values that already look like Datalog variables, such as "?name", are left
    as-is.
    """
    if isinstance(value, dict):
        return {
            (key if key.startswith(":") else f":{key}"): to_edn_wire_data(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [to_edn_wire_data(item) for item in value]
    return value


def edn(value):
    if isinstance(value, dict):
        return "{" + " ".join(f"{edn(key)} {edn(val)}" for key, val in value.items()) + "}"
    if isinstance(value, list):
        return "[" + " ".join(edn(item) for item in value) + "]"
    if isinstance(value, str):
        if value.startswith(":") or value.startswith("?") or value.startswith("$") or value == "*":
            return value
        return json.dumps(value)
    if isinstance(value, bool):
        return "true" if value else "false"
    if value is None:
        return "nil"
    return str(value)


def http_request(client, method, path, body=None, accept="application/json", content_type="application/edn"):
    url = BASE_URL + path
    headers = {
        "Accept": accept,
        "Content-Type": f"{content_type}; charset=utf-8",
    }
    print_traffic(">>>", f"{method} {url}")
    print(f"Accept: {accept}")
    print(f"Content-Type: {headers['Content-Type']}")
    if body is not None:
        print(pretty_body(body))
    sys.stdout.flush()

    if httpx is not None:
        response = client.request(
            method,
            url,
            content=body,
            headers=headers,
            timeout=10,
        )
        print_traffic("<<<", f"{response.status_code} {method} {path}", response.text)
        return response.status_code, response.text

    status, text = client.request(method, url, body, headers)
    print_traffic("<<<", f"{status} {method} {path}", text)
    return status, text


class Client:
    def __init__(self, client):
        self.client = client

    def encode(self, value, content_type):
        if content_type == "application/json":
            return json.dumps(value)
        return edn(to_edn_wire_data(value))

    def post(self, path, value, content_type="application/json", accept="application/json"):
        status, text = http_request(
            self.client,
            "POST",
            path,
            self.encode(value, content_type),
            accept=accept,
            content_type=content_type,
        )
        assert status == 200, f"{path} returned {status}: {text}"
        if accept == "application/json":
            return json.loads(text)
        return text

    def get(self, path, accept="application/json"):
        status, text = http_request(self.client, "GET", path, accept=accept)
        assert status == 200, f"{path} returned {status}: {text}"
        if accept == "application/json":
            return json.loads(text)
        return text


SCHEMA = [
    {
        "db/ident": ":person/id",
        "db/valueType": ":db.type/string",
        "db/cardinality": ":db.cardinality/one",
        "db/unique": ":db.unique/identity",
    },
    {
        "db/ident": ":person/name",
        "db/valueType": ":db.type/string",
        "db/cardinality": ":db.cardinality/one",
    },
    {
        "db/ident": ":person/assignments",
        "db/valueType": ":db.type/ref",
        "db/cardinality": ":db.cardinality/many",
    },
    {
        "db/ident": ":assignment/id",
        "db/valueType": ":db.type/string",
        "db/cardinality": ":db.cardinality/one",
        "db/unique": ":db.unique/identity",
    },
    {
        "db/ident": ":assignment/name",
        "db/valueType": ":db.type/string",
        "db/cardinality": ":db.cardinality/one",
    },
    {
        "db/ident": ":assignment/days",
        "db/valueType": ":db.type/long",
        "db/cardinality": ":db.cardinality/one",
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
        person_id = tutorial_id(person_name_id)
        assignment_id = tutorial_id(assignment_name_id)
        tx.extend(
            [
                {
                    "person/id": person_id,
                    "person/name": person_name,
                    "db/id": person_id,
                },
                {
                    "assignment/id": assignment_id,
                    "assignment/name": f"{person_name.split()[0]}'s work",
                    "assignment/days": len(person_name),
                    "db/id": assignment_id,
                },
                {
                    "person/id": person_id,
                    "person/assignments": assignment_id,
                },
            ]
        )
    return tx


def run_tutorial(raw_client):
    client = Client(raw_client)
    ada_id = tutorial_id("ada")
    notes_id = tutorial_id("notes")

    schema_tx = client.post("/transact", SCHEMA, content_type="application/json")
    assert schema_tx["tx-eid"] > 0
    print("1. schema installed with JSON")

    data_tx = client.post(
        "/transact",
        [
            {
                "person/id": ada_id,
                "person/name": "Ada Lovelace",
                "db/id": ada_id,
            },
            {
                "assignment/id": notes_id,
                "assignment/name": "Analytical notes",
                "assignment/days": 12,
                "db/id": notes_id,
            },
            {
                "person/id": ada_id,
                "person/assignments": notes_id,
            },
        ],
        content_type="application/json",
    )
    assert data_tx["tx-eid"] > schema_tx["tx-eid"]
    print("2. data transacted with JSON")

    batch_tx = client.post("/transact", more_people(), content_type="application/json")
    assert batch_tx["tx-eid"] > data_tx["tx-eid"]
    print("3. more data added over the same keep-alive connection")

    pull = client.post(
        "/pull",
        {
            "pattern": [
                ":person/name",
                {"person/assignments": [":assignment/name", ":assignment/days"]},
            ],
            "eid": [":person/id", ada_id],
        },
        content_type="application/edn",
    )
    assert pull["ret"]["person/name"] == "Ada Lovelace"
    assignment = pull["ret"]["person/assignments"][0]
    assert assignment["assignment/name"] == "Analytical notes"
    assert assignment["assignment/days"] == 12
    print("4. pull followed a relation with EDN")

    query = client.post("/q", QUERY, content_type="application/edn")
    assert ["Ada Lovelace", "Analytical notes"] in query["ret"]
    print("5. q found related values with EDN")

    streamed_query = client.post(
        "/q",
        {
            "query": [
                ":find",
                "?name",
                ":where",
                ["?person", ":person/name", "?name"],
            ]
        },
        content_type="application/edn",
        accept="application/edn",
    )
    assert "Grace Hopper" in streamed_query
    assert "Dorothy Vaughan" in streamed_query
    print("6. q streamed a larger result as EDN")

    person_id = client.post(
        "/entid",
        {"ident": [":person/id", ada_id]},
        content_type="application/json",
    )
    assert isinstance(person_id["ret"], int)

    attr_id = client.post("/entid", {"ident": ":person/id"}, content_type="application/edn")
    attr_ident = client.post("/ident", {"eid": attr_id["ret"]}, content_type="application/json")
    assert attr_ident["ret"] == "person/id"
    print("7. entid and ident resolved database ids with mixed formats")

    synced = client.post("/sync", {"t": batch_tx["db-after"]}, content_type="application/json")
    assert synced["ret"] >= batch_tx["db-after"]
    print("8. sync observed the transaction with JSON")

    tx_t = client.get("/tot/%d" % batch_tx["tx-eid"])
    assert isinstance(tx_t, int)
    print("9. tx->t converted the transaction entity")

    tx_range = client.post(
        "/txrange",
        {"from": schema_tx["tx-eid"]},
        content_type="application/edn",
        accept="application/edn",
    )
    assert "Grace Hopper" in tx_range
    assert ":ret" in tx_range
    print("10. tx-range streamed the transaction log with EDN")

    roundtrip_tx = client.get("/totx/%d" % tx_t)
    assert roundtrip_tx == batch_tx["tx-eid"]
    print("11. t->tx converted the logical t back to the transaction entity")


def main():
    print(f"Using {BASE_URL}")
    print(f"Tutorial run id: {RUN_ID}")
    with http_client() as raw_client:
        run_tutorial(raw_client)
    print("HTTP tutorial completed")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"FAILED: {exc}", file=sys.stderr)
        raise
