"""Hand-written runtime for the generated client (client.py). Standard library only (urllib)."""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any, Callable, Dict, Iterable, Iterator, List, Mapping, Optional, Tuple, Union

# No built-in deployment: pass base_url (your VegaDūta API origin) or set VEGADUTA_BASE_URL.
DEFAULT_BASE_URL = ""

# The server sends this literal as the final SSE chunk when a response dies mid-stream, after the
# HTTP status was already committed (mirrors AgentController.STREAM_ERROR_MARKER in core).
STREAM_ERROR_MARKER = "@@AGENT_CHAT_STREAM_ERROR_9f2b1c@@"

TokenSource = Union[str, Callable[[], str], None]


class ApiError(Exception):
    """Raised for every non-2xx response. ``body`` is the parsed JSON error or the raw text."""

    def __init__(self, status: int, body: Any, message: Optional[str] = None) -> None:
        self.status = status
        self.body = body
        self.message = message or _describe_error(status, body)
        super().__init__(f"HTTP {status}: {self.message}")


class StreamInterruptedError(Exception):
    """Raised while iterating a stream that the server aborted after it had started."""


def _describe_error(status: int, body: Any) -> str:
    if isinstance(body, dict):
        for key in ("error", "message"):
            value = body.get(key)
            if isinstance(value, str) and value:
                return value
    if isinstance(body, str) and body:
        return body[:300]
    return f"HTTP {status}"


def quote_path(value: Any) -> str:
    """Percent-encode one path segment (slashes included)."""
    return urllib.parse.quote(str(value), safe="")


class SseDecoder:
    """Incremental decoder for this platform's Server-Sent Events.

    An event is its ``data:`` lines up to the next blank line, joined with ``\\n`` (the server writes a
    chunk that contains newlines as several data lines). Exactly the five characters ``data:`` are
    stripped and nothing else: the server never adds a framing space, so a chunk that is a single
    space arrives as ``data: `` and must be kept. This deliberately differs from the generic SSE
    spec, which would drop one leading space.
    """

    def __init__(self) -> None:
        self._data: List[str] = []

    def feed_line(self, raw: str) -> Optional[str]:
        """Feed one line (without its trailing newline). Returns an event payload when one completes."""
        line = raw[:-1] if raw.endswith("\r") else raw
        if line == "":
            if self._data:
                payload = "\n".join(self._data)
                self._data = []
                return payload
            return None
        if line.startswith("data:"):
            self._data.append(line[5:])
        # event:, id:, retry: and :comment lines carry no payload on this API.
        return None

    def flush(self) -> Optional[str]:
        """Call at end of stream: returns a final event the server never terminated with a blank line."""
        if self._data:
            payload = "\n".join(self._data)
            self._data = []
            return payload
        return None


def iter_sse(lines: Iterable[bytes]) -> Iterator[str]:
    """Yield the payload of every SSE event in an iterable of raw byte lines (as an HTTP response yields)."""
    decoder = SseDecoder()
    for raw in lines:
        text = raw.decode("utf-8")
        if text.endswith("\n"):
            text = text[:-1]
        event = decoder.feed_line(text)
        if event is not None:
            yield event
    tail = decoder.flush()
    if tail is not None:
        yield tail


def encode_multipart(fields: Mapping[str, Any]) -> Tuple[bytes, str]:
    """Encode a multipart/form-data body. A value may be str, bytes, or (filename, bytes[, content_type])."""
    boundary = "----vegaduta" + uuid.uuid4().hex
    parts: List[bytes] = []
    for name, value in fields.items():
        if value is None:
            continue
        disposition = f'form-data; name="{name}"'
        content_type = None
        if isinstance(value, tuple):
            filename = value[0]
            data = value[1]
            content_type = value[2] if len(value) > 2 else "application/octet-stream"
            disposition += f'; filename="{filename}"'
        elif isinstance(value, (bytes, bytearray)):
            data = bytes(value)
        else:
            data = str(value).encode("utf-8")
        head = f"--{boundary}\r\nContent-Disposition: {disposition}\r\n"
        if content_type:
            head += f"Content-Type: {content_type}\r\n"
        parts.append(head.encode("utf-8") + b"\r\n" + data + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode("utf-8"))
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


class HttpClient:
    """Minimal HTTP layer over urllib.request used by the generated client."""

    def __init__(
        self,
        base_url: str = DEFAULT_BASE_URL,
        token: TokenSource = None,
        timeout: float = 60.0,
        headers: Optional[Mapping[str, str]] = None,
    ) -> None:
        resolved = base_url or os.environ.get("VEGADUTA_BASE_URL", "")
        if not resolved:
            raise ValueError(
                "base_url is required: pass your VegaDuta API origin (e.g. https://api.example.com) "
                "or set VEGADUTA_BASE_URL"
            )
        self.base_url = resolved.rstrip("/")
        self.token = token
        self.timeout = timeout
        self.default_headers: Dict[str, str] = dict(headers or {})

    # -- public ---------------------------------------------------------------------------------

    def request(
        self,
        method: str,
        path: str,
        *,
        query: Optional[Mapping[str, Any]] = None,
        headers: Optional[Mapping[str, Any]] = None,
        body: Any = None,
        body_type: str = "json",
        content_type: Optional[str] = None,
        response: str = "json",
    ) -> Any:
        """Perform a request and return the parsed body; raises ApiError for non-2xx."""
        accept = "application/json" if response == "json" else "*/*"
        req = self._build(method, path, query, headers, body, body_type, content_type, accept)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as res:
                raw = res.read()
        except urllib.error.HTTPError as err:
            raise self._error(err) from None
        if response == "void":
            return None
        if response == "blob":
            return raw
        text = raw.decode("utf-8") if raw else ""
        if response == "text":
            return text
        return json.loads(text) if text else None

    def stream(
        self,
        method: str,
        path: str,
        *,
        query: Optional[Mapping[str, Any]] = None,
        headers: Optional[Mapping[str, Any]] = None,
        body: Any = None,
        body_type: str = "json",
        content_type: Optional[str] = None,
    ) -> Iterator[str]:
        """Perform a request that answers with Server-Sent Events; yields each event's payload.

        The request is sent when the first item is requested (this is a generator). Closing the
        generator closes the connection.
        """
        req = self._build(method, path, query, headers, body, body_type, content_type, "text/event-stream")
        try:
            res = urllib.request.urlopen(req, timeout=self.timeout)
        except urllib.error.HTTPError as err:
            raise self._error(err) from None
        try:
            for payload in iter_sse(res):
                if payload.startswith(STREAM_ERROR_MARKER):
                    raise StreamInterruptedError(
                        payload[len(STREAM_ERROR_MARKER):] or "The response stream was interrupted. Please try again."
                    )
                yield payload
        finally:
            res.close()

    # -- internals ------------------------------------------------------------------------------

    def _url(self, path: str, query: Optional[Mapping[str, Any]]) -> str:
        pairs: List[Tuple[str, str]] = []
        for key, value in (query or {}).items():
            if value is None:
                continue
            items = value if isinstance(value, (list, tuple)) else [value]
            for item in items:
                pairs.append((key, _query_text(item)))
        qs = urllib.parse.urlencode(pairs, quote_via=urllib.parse.quote)
        return f"{self.base_url}{path}" + (f"?{qs}" if qs else "")

    def _resolve_token(self) -> Optional[str]:
        return self.token() if callable(self.token) else self.token

    def _build(
        self,
        method: str,
        path: str,
        query: Optional[Mapping[str, Any]],
        headers: Optional[Mapping[str, Any]],
        body: Any,
        body_type: str,
        content_type: Optional[str],
        accept: str,
    ) -> urllib.request.Request:
        merged: Dict[str, str] = dict(self.default_headers)
        token = self._resolve_token()
        if token:
            merged["Authorization"] = f"Bearer {token}"
        merged["Accept"] = accept
        for key, value in (headers or {}).items():
            if value is not None:
                merged[key] = str(value)
        data: Optional[bytes] = None
        if body is not None:
            if body_type == "json":
                merged["Content-Type"] = "application/json"
                data = json.dumps(body).encode("utf-8")
            elif body_type == "multipart":
                data, merged["Content-Type"] = encode_multipart(body)
            else:
                if content_type and "Content-Type" not in merged:
                    merged["Content-Type"] = content_type
                data = body.encode("utf-8") if isinstance(body, str) else bytes(body)
        return urllib.request.Request(self._url(path, query), data=data, headers=merged, method=method)

    @staticmethod
    def _error(err: urllib.error.HTTPError) -> ApiError:
        try:
            text = err.read().decode("utf-8", "replace")
        except Exception:  # noqa: BLE001 - a broken error body must not mask the HTTP status
            text = ""
        body: Any = text
        if text:
            try:
                body = json.loads(text)
            except ValueError:
                body = text
        return ApiError(err.code, body)


def _query_text(value: Any) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)
