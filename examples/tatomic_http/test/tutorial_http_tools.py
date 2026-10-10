import codecs
import http.client
import json as jsonlib
from contextlib import contextmanager
from pathlib import Path
import socket
import subprocess
import sys
import time


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
        cwd=working_dir,
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
    """Small dependency-free HTTP client with keep-alive."""

    def __init__(self, host, port):
        self.host = host
        self.port = port
        self.conn = None

    def __enter__(self):
        self.conn = http.client.HTTPConnection(self.host, self.port, timeout=10)
        return self

    def __exit__(self, *_):
        self.conn.close()

    def request(self, method, path, body, headers):
        self.conn.request(method, path, body=body, headers=headers)
        return self.conn.getresponse()


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


def response_chunks(response):
    """Yield each HTTP chunk with incrementally decoded text."""
    decoder = codecs.getincrementaldecoder("utf-8")()
    while True:
        data = response.read1()
        if not data:
            break
        text = decoder.decode(data)
        if text:
            yield data, text
    final_text = decoder.decode(b"", final=True)
    if final_text:
        yield b"", final_text


class Symbol(str):
    """A string explicitly encoded as an EDN symbol, for example Symbol('pull')."""


class Keyword(str):
    """An EDN keyword, stored with its leading colon for JSON transport too."""

    def __new__(cls, name):
        return super().__new__(cls, ":" + name.removeprefix(":"))

class LogicalVar(str):
    """A logical variable, stored with its leading question mark."""

    def __new__(cls, name):
        return super().__new__(cls, "?" + name.removeprefix("?"))


def edn(value):
    """Encode the small Python data subset used by the EDN request examples."""
    if isinstance(value, dict):
        return "{" + " ".join(f"{edn(key)} {edn(val)}" for key, val in value.items()) + "}"
    if isinstance(value, list):
        return "[" + " ".join(edn(item) for item in value) + "]"
    if isinstance(value, tuple):
        return "(" + " ".join(edn(item) for item in value) + ")"
    if isinstance(value, (Symbol, Keyword, LogicalVar)):
        return str(value)
    if isinstance(value, str):
        if value.startswith((":", "?")):
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
    content_type="application/edn"):
    """Send one request and print the request and response in wire form."""
    url = base_url + path
    headers = {
        "Accept": accept,
        "Content-Type": f"{content_type}; charset=utf-8",
    }
    print_traffic(">>>", f"{method} {url}", headers.items(), body)

    response = client.request(method, path, body, headers)
    print_traffic("<<<", f"{response.status} {method} {path}", response.getheaders())

    if response.chunked:
        chunks = []
        print()
        for data, text in response_chunks(response):
            sys.stdout.write(f"{len(data):x}\r\n")
            sys.stdout.write(text)
            sys.stdout.write("\r\n")
            sys.stdout.flush()
            chunks.append(text)
        sys.stdout.write("0\r\n\r\n")
        print("----------------")
        sys.stdout.flush()
        return response.status, "".join(chunks)

    text = "".join(text for _, text in response_chunks(response))
    sys.stdout.write("\n" + text)
    if not text.endswith("\n"):
        print()
    sys.stdout.flush()
    print("----------------")
    return response.status, text


class Client:
    """Keep status handling and HTTP transport out of the endpoint examples."""

    def __init__(self, base_url, client):
        self.base_url = base_url
        self.client = client

    def post(
        self,
        path,
        body,
        content_type="application/json",
        accept="application/json"):
        status, text = http_request(
            self.base_url,
            self.client,
            "POST",
            path,
            body,
            accept=accept,
            content_type=content_type)
        assert status == 200, f"{path} returned {status}: {text}"
        return text

    def get(self, path, accept="application/json"):
        status, text = http_request(self.base_url, self.client, "GET", path, accept=accept)
        assert status == 200, f"{path} returned {status}: {text}"
        return text