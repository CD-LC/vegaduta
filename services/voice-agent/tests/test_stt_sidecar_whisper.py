"""
Unit tests for SidecarWhisperSTT (app/stt_sidecar_whisper.py) against a real
local HTTP server - Python's stdlib `http.server.ThreadingHTTPServer`, the
direct equivalent of the Java side's `com.sun.net.httpserver.HttpServer`
pattern (sidecar/tests/ itself has no existing HTTP-mocking convention to
follow, since sidecar is only ever an HTTP callee, never a caller - so this
introduces the convention for this repo's Python test suites that DO need
one, rather than pulling in a mocking library).

Runs the async plugin method with `asyncio.run(...)` from plain sync test
functions rather than adding a pytest-asyncio dependency, to keep
requirements-dev.txt as minimal as sidecar's own (just `pytest`).
"""
import asyncio
import base64
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from livekit import rtc
from livekit.agents import DEFAULT_API_CONNECT_OPTIONS, APIConnectionError, APIStatusError

from app.stt_sidecar_whisper import SidecarWhisperSTT


def _silence_frame(samples: int = 1600) -> rtc.AudioFrame:
    """~0.1s of silence at 16kHz mono - enough to produce a real WAV file."""
    return rtc.AudioFrame(
        data=b"\x00\x00" * samples,
        sample_rate=16000,
        num_channels=1,
        samples_per_channel=samples,
    )


class _RecordingTranscribeServer:
    """Spins up a real HTTP server that plays back a canned /transcribe
    response and records the last request it received, so tests can assert
    on both the response handling AND the outgoing request shape."""

    def __init__(self, status: int, response_body: dict | None) -> None:
        self.status = status
        self.response_body = response_body
        self.last_request_body: dict | None = None
        self.last_request_path: str | None = None
        server_self = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802 (stdlib API name)
                length = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(length)
                server_self.last_request_path = self.path
                server_self.last_request_body = json.loads(raw) if raw else None

                self.send_response(server_self.status)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                if server_self.response_body is not None:
                    self.wfile.write(json.dumps(server_self.response_body).encode("utf-8"))

            def log_message(self, *args: object) -> None:  # silence stdout noise
                pass

        self._httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._thread = threading.Thread(target=self._httpd.serve_forever, daemon=True)

    @property
    def base_url(self) -> str:
        host, port = self._httpd.server_address
        return f"http://{host}:{port}"

    def __enter__(self) -> "_RecordingTranscribeServer":
        self._thread.start()
        return self

    def __exit__(self, *exc: object) -> None:
        self._httpd.shutdown()
        self._httpd.server_close()


def test_transcribe_success_returns_final_transcript() -> None:
    with _RecordingTranscribeServer(200, {"text": "  hello world  "}) as server:
        stt_plugin = SidecarWhisperSTT(base_url=server.base_url)
        try:
            event = asyncio.run(
                stt_plugin._recognize_impl(
                    _silence_frame(), conn_options=DEFAULT_API_CONNECT_OPTIONS
                )
            )
        finally:
            asyncio.run(stt_plugin.aclose())

    assert event.alternatives[0].text == "hello world"
    assert server.last_request_path == "/transcribe"


def test_transcribe_sends_wav_bytes_as_base64_json_body() -> None:
    with _RecordingTranscribeServer(200, {"text": "ok"}) as server:
        stt_plugin = SidecarWhisperSTT(base_url=server.base_url)
        try:
            asyncio.run(
                stt_plugin._recognize_impl(
                    _silence_frame(), conn_options=DEFAULT_API_CONNECT_OPTIONS
                )
            )
        finally:
            asyncio.run(stt_plugin.aclose())

    assert server.last_request_body is not None
    assert server.last_request_body["filename"] == "utterance.wav"
    decoded = base64.b64decode(server.last_request_body["content_base64"])
    # a real WAV container starts with the RIFF magic bytes
    assert decoded[:4] == b"RIFF"
    assert decoded[8:12] == b"WAVE"


def test_transcribe_non_200_raises_api_status_error() -> None:
    with _RecordingTranscribeServer(400, {"detail": "text produced no readable content"}) as server:
        stt_plugin = SidecarWhisperSTT(base_url=server.base_url)
        try:
            with pytest.raises(APIStatusError) as exc_info:
                asyncio.run(
                    stt_plugin._recognize_impl(
                        _silence_frame(), conn_options=DEFAULT_API_CONNECT_OPTIONS
                    )
                )
        finally:
            asyncio.run(stt_plugin.aclose())

    assert exc_info.value.status_code == 400


def test_transcribe_connection_failure_raises_api_connection_error() -> None:
    # nothing listening on this port - a real connection failure, not a mock
    stt_plugin = SidecarWhisperSTT(base_url="http://127.0.0.1:1")
    try:
        with pytest.raises(APIConnectionError):
            asyncio.run(
                stt_plugin._recognize_impl(
                    _silence_frame(), conn_options=DEFAULT_API_CONNECT_OPTIONS
                )
            )
    finally:
        asyncio.run(stt_plugin.aclose())
