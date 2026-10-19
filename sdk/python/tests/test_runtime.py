"""Unit tests for the hand-written runtime (urllib only). Run: python -m unittest discover -s tests"""

import json
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from vegaduta_sdk import runtime  # noqa: E402
from vegaduta_sdk.runtime import (  # noqa: E402
    ApiError,
    HttpClient,
    SseDecoder,
    StreamInterruptedError,
    encode_multipart,
    iter_sse,
    quote_path,
)


class SseDecoderTest(unittest.TestCase):
    def test_joins_data_lines_and_strips_exactly_five_characters(self):
        d = SseDecoder()
        events = []
        for line in ["data:Hello", "data: world", "", "data: ", "", ": comment", "event: x", "data:last"]:
            e = d.feed_line(line)
            if e is not None:
                events.append(e)
        tail = d.flush()
        if tail is not None:
            events.append(tail)
        # first event keeps the leading space of " world"; the single-space chunk survives; last is unterminated
        self.assertEqual(events, ["Hello\n world", " ", "last"])

    def test_crlf_and_empty_event(self):
        d = SseDecoder()
        self.assertIsNone(d.feed_line("data:a\r"))
        self.assertEqual(d.feed_line("\r"), "a")
        self.assertIsNone(d.feed_line(""))  # blank line with no data dispatches nothing

    def test_iter_sse_over_byte_lines(self):
        raw = [b"data:one\n", b"\n", b"data:two\n", b"data:three\n", b"\n", b"data:tail"]
        self.assertEqual(list(iter_sse(raw)), ["one", "two\nthree", "tail"])


class HelpersTest(unittest.TestCase):
    def test_quote_path_encodes_slashes(self):
        self.assertEqual(quote_path("a b/c"), "a%20b%2Fc")

    def test_encode_multipart(self):
        body, content_type = encode_multipart({"name": "x", "file": ("a.txt", b"hello", "text/plain"), "skip": None})
        self.assertTrue(content_type.startswith("multipart/form-data; boundary="))
        boundary = content_type.split("boundary=")[1].encode()
        self.assertIn(b'name="name"', body)
        self.assertIn(b'filename="a.txt"', body)
        self.assertIn(b"Content-Type: text/plain", body)
        self.assertNotIn(b"skip", body)
        self.assertTrue(body.endswith(b"--" + boundary + b"--\r\n"))

    def test_api_error_message(self):
        self.assertEqual(ApiError(404, {"status": 404, "error": "nope"}).message, "nope")
        self.assertEqual(ApiError(500, "boom").message, "boom")
        self.assertEqual(ApiError(502, None).message, "HTTP 502")


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # keep test output quiet
        pass

    def _send(self, status, body=b"", content_type="application/json"):
        self.send_response(status)
        if status != 204:
            self.send_header("Content-Type", content_type)
        self.end_headers()
        if body:
            self.wfile.write(body)
            self.wfile.flush()

    def _json(self, status, obj):
        self._send(status, json.dumps(obj).encode("utf-8"))

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/echo":
            self._json(200, {
                "query": parse_qs(url.query),
                "auth": self.headers.get("Authorization"),
                "accept": self.headers.get("Accept"),
                "custom": self.headers.get("X-Custom"),
            })
        elif url.path == "/fail":
            self._json(404, {"status": 404, "error": "nope"})
        elif url.path == "/text":
            self._send(200, b"hello", "text/plain")
        elif url.path == "/sse":
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            for chunk in [b"data:Hel\n\n", b"data:lo\n\n", b"data: \n\n", b"data:l1\ndata:l2\n\n"]:
                self.wfile.write(chunk)
                self.wfile.flush()
        elif url.path == "/sse-error":
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            self.wfile.write(b"data:partial\n\n")
            self.wfile.write(("data:" + runtime.STREAM_ERROR_MARKER + "boom\n\n").encode("utf-8"))
            self.wfile.flush()
        else:
            self._json(404, {"status": 404, "error": "unknown"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b""
        ctype = self.headers.get("Content-Type", "")
        if self.path == "/json":
            self._json(201, {"received": json.loads(raw.decode("utf-8")), "content_type": ctype})
        elif self.path == "/upload":
            self._json(200, {"content_type": ctype, "has_file": b'filename="a.txt"' in raw})
        else:
            self._json(404, {"status": 404, "error": "unknown"})

    def do_DELETE(self):
        self._send(204)


class HttpClientTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self._stop)
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"

    def _stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def test_request_sends_bearer_headers_and_query(self):
        client = HttpClient(base_url=self.base + "/", token="secret", headers={"X-Default": "1"})
        out = client.request(
            "GET", "/echo", query={"a": 1, "b": [True, "x y"], "skip": None}, headers={"X-Custom": "c"}
        )
        self.assertEqual(out["auth"], "Bearer secret")
        self.assertEqual(out["accept"], "application/json")
        self.assertEqual(out["custom"], "c")
        self.assertEqual(out["query"], {"a": ["1"], "b": ["true", "x y"]})

    def test_token_may_be_a_callable(self):
        client = HttpClient(base_url=self.base, token=lambda: "fresh")
        self.assertEqual(client.request("GET", "/echo")["auth"], "Bearer fresh")

    def test_json_body_and_status(self):
        out = HttpClient(base_url=self.base).request("POST", "/json", body={"x": 1})
        self.assertEqual(out["received"], {"x": 1})
        self.assertEqual(out["content_type"], "application/json")

    def test_text_void_and_blob_responses(self):
        client = HttpClient(base_url=self.base)
        self.assertEqual(client.request("GET", "/text", response="text"), "hello")
        self.assertEqual(client.request("GET", "/text", response="blob"), b"hello")
        self.assertIsNone(client.request("DELETE", "/anything", response="void"))

    def test_multipart_upload(self):
        out = HttpClient(base_url=self.base).request(
            "POST", "/upload", body={"file": ("a.txt", b"hi")}, body_type="multipart"
        )
        self.assertTrue(out["content_type"].startswith("multipart/form-data; boundary="))
        self.assertTrue(out["has_file"])

    def test_error_response_becomes_api_error(self):
        with self.assertRaises(ApiError) as ctx:
            HttpClient(base_url=self.base).request("GET", "/fail")
        self.assertEqual(ctx.exception.status, 404)
        self.assertEqual(ctx.exception.body, {"status": 404, "error": "nope"})
        self.assertEqual(ctx.exception.message, "nope")

    def test_stream_yields_events_with_spaces_and_newlines_intact(self):
        events = list(HttpClient(base_url=self.base).stream("GET", "/sse"))
        self.assertEqual(events, ["Hel", "lo", " ", "l1\nl2"])
        self.assertEqual("".join(events), "Hello l1\nl2")

    def test_stream_error_marker_raises(self):
        gen = HttpClient(base_url=self.base).stream("GET", "/sse-error")
        self.assertEqual(next(gen), "partial")
        with self.assertRaises(StreamInterruptedError) as ctx:
            next(gen)
        self.assertEqual(str(ctx.exception), "boom")

    def test_stream_http_error_raises_api_error_on_first_item(self):
        gen = HttpClient(base_url=self.base).stream("GET", "/fail")
        with self.assertRaises(ApiError):
            next(gen)


if __name__ == "__main__":
    unittest.main()


class BaseUrlTest(unittest.TestCase):
    """No built-in deployment: the base URL comes from the caller or VEGADUTA_BASE_URL."""

    def test_missing_base_url_is_a_clear_error(self):
        saved = runtime.os.environ.pop("VEGADUTA_BASE_URL", None)
        try:
            with self.assertRaises(ValueError) as ctx:
                HttpClient()
            self.assertIn("VEGADUTA_BASE_URL", str(ctx.exception))
        finally:
            if saved is not None:
                runtime.os.environ["VEGADUTA_BASE_URL"] = saved

    def test_environment_supplies_the_base_url(self):
        saved = runtime.os.environ.get("VEGADUTA_BASE_URL")
        runtime.os.environ["VEGADUTA_BASE_URL"] = "https://api.example.test/"
        try:
            self.assertEqual(HttpClient().base_url, "https://api.example.test")
        finally:
            if saved is None:
                runtime.os.environ.pop("VEGADUTA_BASE_URL", None)
            else:
                runtime.os.environ["VEGADUTA_BASE_URL"] = saved
