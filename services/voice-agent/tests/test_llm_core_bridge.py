"""
Unit tests for CoreChatBridgeLLM (app/llm_core_bridge.py) against a real
local HTTP server (see test_stt_sidecar_whisper.py's module docstring for
why `http.server.ThreadingHTTPServer` rather than a mocking library). These
tests exist specifically to lock in the SSE-parsing contract this file
copies from `web/app/lib/api.ts`'s `streamChat` - the space-preservation
behavior in particular regressed once already on the JS side (see
llm_core_bridge.py's module docstring), so it is worth a real test on the
Python port too.
"""
import asyncio
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from livekit.agents import APIConnectionError, APIConnectOptions, APIStatusError
from livekit.agents import llm as agents_llm

from app.llm_core_bridge import STREAM_ERROR_MARKER, CoreChatBridgeLLM, latest_user_text


class _ScriptedChatServer:
    """Real HTTP server that streams a canned sequence of raw SSE lines back
    for every POST to /api/agents/{id}/chat, and records each request it
    receives (path, headers, JSON body) so tests can assert on the outgoing
    request as well as the parsed response."""

    def __init__(self, *, lines: list[str], status: int = 200, session_id: str | None = None) -> None:
        self.lines = lines
        self.status = status
        self.session_id = session_id
        self.requests: list[dict] = []
        server_self = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.0"  # connection closes at end of response -> clean EOF

            def do_POST(self) -> None:  # noqa: N802
                length = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(length)
                server_self.requests.append(
                    {
                        "path": self.path,
                        "authorization": self.headers.get("Authorization"),
                        "body": json.loads(raw) if raw else None,
                    }
                )

                self.send_response(server_self.status)
                self.send_header("Content-Type", "text/event-stream")
                if server_self.session_id:
                    self.send_header("X-Session-Id", server_self.session_id)
                self.end_headers()
                for line in server_self.lines:
                    self.wfile.write((line + "\n").encode("utf-8"))
                self.wfile.flush()

            def log_message(self, *args: object) -> None:
                pass

        self._httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._thread = threading.Thread(target=self._httpd.serve_forever, daemon=True)

    @property
    def base_url(self) -> str:
        host, port = self._httpd.server_address
        return f"http://{host}:{port}"

    def __enter__(self) -> "_ScriptedChatServer":
        self._thread.start()
        return self

    def __exit__(self, *exc: object) -> None:
        self._httpd.shutdown()
        self._httpd.server_close()


def _user_ctx(text: str) -> agents_llm.ChatContext:
    ctx = agents_llm.ChatContext.empty()
    ctx.add_message(role="user", content=text)
    return ctx


async def _collect(stream: agents_llm.LLMStream) -> str:
    text = ""
    async with stream:
        async for chunk in stream:
            if chunk.delta and chunk.delta.content:
                text += chunk.delta.content
    return text


async def _chat_and_collect(
    bridge: CoreChatBridgeLLM,
    ctx: agents_llm.ChatContext,
    *,
    conn_options: APIConnectOptions | None = None,
) -> str:
    # `LLM.chat()` constructs an `LLMStream`, whose base `__init__`
    # (livekit/agents/llm/llm.py) immediately does `asyncio.create_task(...)`
    # - it MUST be called from inside a running event loop, not handed one
    # after the fact, so every call site below builds and drains the stream
    # in the same `asyncio.run(...)`.
    kwargs = {"conn_options": conn_options} if conn_options is not None else {}
    return await _collect(bridge.chat(chat_ctx=ctx, **kwargs))


# The base LLMStream retries every APIError up to conn_options.max_retry
# (default 3, i.e. 4 attempts total - see livekit/agents/llm/llm.py's
# _main_task) before giving up, wrapping a still-retryable error into a
# generic APIConnectionError that loses the original status_code/message.
# That default retry behavior is correct/desired in production (a transient
# 500 from core SHOULD be retried), but these tests want to assert on the
# ORIGINAL error from a single attempt, so error-path tests pass this to
# fail fast instead of waiting through real backoff sleeps.
_FAIL_FAST = APIConnectOptions(max_retry=0)


def test_latest_user_text_finds_newest_user_turn() -> None:
    ctx = agents_llm.ChatContext.empty()
    ctx.add_message(role="user", content="first")
    ctx.add_message(role="assistant", content="reply")
    ctx.add_message(role="user", content="second")
    assert latest_user_text(ctx) == "second"


def test_stream_reassembles_tokens_and_preserves_space_only_tokens() -> None:
    # "data: " (5-char prefix + a single space, no more) must decode to a
    # ONE-SPACE content token, not be treated as "prefix + eaten space" -
    # exactly the bug web/app/lib/api.ts's own comment documents fixing.
    with _ScriptedChatServer(lines=["data:Hello", "data: ", "data:World"]) as server:
        bridge = CoreChatBridgeLLM(agent_id="agent-1", auth_token="tok-123", base_url=server.base_url)
        try:
            text = asyncio.run(_chat_and_collect(bridge, _user_ctx("hi")))
        finally:
            asyncio.run(bridge.aclose())

    assert text == "Hello World"
    assert server.requests[0]["authorization"] == "Bearer tok-123"
    assert server.requests[0]["body"] == {"message": "hi", "forceFastDepth": True}


def test_session_id_is_captured_and_resent_on_next_turn() -> None:
    # Both turns run inside ONE asyncio.run(...) - the bridge's aiohttp
    # ClientSession is created lazily and cached on first use (_ensure_session),
    # bound to whichever event loop was running at that point, same as it
    # would be for the one long-lived event loop a real call runs inside
    # (main.py's entrypoint). Splitting this across two separate
    # asyncio.run() calls would hand the cached session a second, already-
    # closed loop - a test-harness artifact, not a real production scenario.
    async def _two_turns(bridge: CoreChatBridgeLLM) -> None:
        await _chat_and_collect(bridge, _user_ctx("first turn"))
        assert bridge.session_id == "sess-abc"
        await _chat_and_collect(bridge, _user_ctx("second turn"))
        await bridge.aclose()

    with _ScriptedChatServer(lines=["data:ok"], session_id="sess-abc") as server:
        bridge = CoreChatBridgeLLM(agent_id="agent-1", auth_token="tok-123", base_url=server.base_url)
        asyncio.run(_two_turns(bridge))

    assert server.requests[0]["body"] == {"message": "first turn", "forceFastDepth": True}
    assert server.requests[1]["body"] == {
        "message": "second turn",
        "sessionId": "sess-abc",
        "forceFastDepth": True,
    }


def test_stream_error_marker_raises_api_status_error() -> None:
    with _ScriptedChatServer(lines=[f"data:{STREAM_ERROR_MARKER}provider timed out"]) as server:
        bridge = CoreChatBridgeLLM(agent_id="agent-1", auth_token="tok-123", base_url=server.base_url)
        try:
            with pytest.raises(APIStatusError, match="provider timed out"):
                asyncio.run(_chat_and_collect(bridge, _user_ctx("hi"), conn_options=_FAIL_FAST))
        finally:
            asyncio.run(bridge.aclose())


def test_non_200_response_raises_api_status_error() -> None:
    with _ScriptedChatServer(lines=[], status=500) as server:
        bridge = CoreChatBridgeLLM(agent_id="agent-1", auth_token="tok-123", base_url=server.base_url)
        try:
            with pytest.raises(APIStatusError) as exc_info:
                asyncio.run(_chat_and_collect(bridge, _user_ctx("hi"), conn_options=_FAIL_FAST))
        finally:
            asyncio.run(bridge.aclose())
    assert exc_info.value.status_code == 500


def test_missing_auth_token_fails_loudly_without_any_http_call() -> None:
    with _ScriptedChatServer(lines=["data:should never be reached"]) as server:
        bridge = CoreChatBridgeLLM(agent_id="agent-1", auth_token=None, base_url=server.base_url)
        try:
            # not retryable (see llm_core_bridge.py's retryable=False comment)
            # so this raises immediately even without _FAIL_FAST.
            with pytest.raises(APIConnectionError, match="no bearer token"):
                asyncio.run(_chat_and_collect(bridge, _user_ctx("hi")))
        finally:
            asyncio.run(bridge.aclose())

    assert server.requests == []  # never actually called core unauthenticated


def test_empty_user_turn_speaks_a_canned_apology_and_makes_no_http_call() -> None:
    # Reliability blueprint Phase 4 Part 5c#1: an empty/whitespace-only STT
    # transcript must NOT end the turn with zero chunks (that was
    # indistinguishable from "the LLM legitimately had nothing to say" to
    # AgentSession's TTS node - dead silence, no signal anything went
    # wrong). It should push a canned, audible apology through the same
    # event-channel mechanism real replies use, and still never call core
    # (there's nothing real to send).
    with _ScriptedChatServer(lines=["data:should never be reached"]) as server:
        bridge = CoreChatBridgeLLM(agent_id="agent-1", auth_token="tok-123", base_url=server.base_url)
        ctx = agents_llm.ChatContext.empty()
        ctx.add_message(role="assistant", content="only an assistant turn, no user text")
        try:
            text = asyncio.run(_chat_and_collect(bridge, ctx))
        finally:
            asyncio.run(bridge.aclose())

    assert text == "Sorry, I didn't catch that - could you repeat it?"
    assert server.requests == []


class _FakeLocalParticipant:
    """Records every publish_data() call - stands in for
    rtc.LocalParticipant so these tests don't need a real LiveKit room.
    _publish_stage only ever calls `room.local_participant.publish_data(...)`
    (duck-typed, see llm_core_bridge.py), so this is a faithful substitute."""

    def __init__(self) -> None:
        self.calls: list[dict] = []

    async def publish_data(self, payload: bytes, *, reliable: bool, topic: str) -> None:
        self.calls.append(
            {"payload": json.loads(payload.decode("utf-8")), "reliable": reliable, "topic": topic}
        )


class _FakeRoom:
    def __init__(self) -> None:
        self.local_participant = _FakeLocalParticipant()


def test_stage_signals_fire_understanding_reasoning_generating_in_order() -> None:
    # Reliability blueprint Phase 4 Part 5b: the three stages this file can
    # observe first-hand, in the order a real turn produces them, all on the
    # "agent-stage" topic with reliable=True.
    room = _FakeRoom()
    with _ScriptedChatServer(lines=["data:Hello"]) as server:
        bridge = CoreChatBridgeLLM(
            agent_id="agent-1", auth_token="tok-123", base_url=server.base_url, room=room
        )
        try:
            asyncio.run(_chat_and_collect(bridge, _user_ctx("hi")))
        finally:
            asyncio.run(bridge.aclose())

    # The room's data channel now also carries "call-transcript" lines (the
    # retained per-turn text) - filter to the stage topic before asserting.
    stage_calls = [c for c in room.local_participant.calls if c["topic"] == "agent-stage"]
    stages = [c["payload"]["stage"] for c in stage_calls]
    assert stages == ["understanding", "reasoning", "generating"]
    assert all(c["reliable"] is True for c in room.local_participant.calls)
    # Same turnId threaded through every stage of the one turn.
    turn_ids = {c["payload"]["turnId"] for c in stage_calls}
    assert len(turn_ids) == 1

    # The retained transcript: the user line is exactly what was sent to
    # core, published before the request; the agent line is the accumulated
    # reply. Both on the "call-transcript" topic with a unique event id.
    transcript_calls = [c for c in room.local_participant.calls if c["topic"] == "call-transcript"]
    assert [c["payload"]["role"] for c in transcript_calls] == ["user", "agent"]
    assert transcript_calls[0]["payload"]["text"] == "hi"
    assert transcript_calls[1]["payload"]["text"] == "Hello"
    assert all(c["payload"]["id"] and c["payload"]["ts"] for c in transcript_calls)
    assert transcript_calls[0]["payload"]["id"] != transcript_calls[1]["payload"]["id"]
    # `voiced` rides only on agent lines (the bridge was built with the
    # default tts_voiced=True - a working server-side TTS). User lines have
    # no such field.
    assert "voiced" not in transcript_calls[0]["payload"]
    assert transcript_calls[1]["payload"]["voiced"] is True


def test_stage_signal_publish_failure_is_swallowed_not_fatal() -> None:
    # _publish_stage's own contract: a broken data channel must never take
    # the call down or interrupt the actual reply.
    class _BrokenLocalParticipant:
        async def publish_data(self, *a: object, **k: object) -> None:
            raise RuntimeError("data channel exploded")

    class _BrokenRoom:
        local_participant = _BrokenLocalParticipant()

    with _ScriptedChatServer(lines=["data:Hello"]) as server:
        bridge = CoreChatBridgeLLM(
            agent_id="agent-1", auth_token="tok-123", base_url=server.base_url, room=_BrokenRoom()
        )
        try:
            text = asyncio.run(_chat_and_collect(bridge, _user_ctx("hi")))
        finally:
            asyncio.run(bridge.aclose())

    assert text == "Hello"  # the reply itself is unaffected


def test_share_mode_posts_to_share_endpoint_with_session_key_no_auth_header() -> None:
    # Anonymous agent-share room (main.py's _extract_share_context): no
    # agent_id/auth_token at all, share_id/share_session_key instead - must
    # hit AgentShareController#chatStream, not the authenticated /chat
    # endpoint, with sessionKey (not sessionId) and no Authorization header,
    # no forceFastDepth (ShareChatStreamRequest has neither field).
    with _ScriptedChatServer(lines=["data:Hello"]) as server:
        bridge = CoreChatBridgeLLM(
            share_id="share-abc123",
            share_session_key="share:share-abc123:deadbeef0000",
            base_url=server.base_url,
        )
        try:
            text = asyncio.run(_chat_and_collect(bridge, _user_ctx("hi")))
        finally:
            asyncio.run(bridge.aclose())

    assert text == "Hello"
    assert server.requests[0]["path"] == "/api/share/agents/share-abc123/chat/stream"
    assert server.requests[0]["authorization"] is None
    assert server.requests[0]["body"] == {
        "message": "hi",
        "sessionKey": "share:share-abc123:deadbeef0000",
    }


def test_share_mode_ignores_x_session_id_header() -> None:
    # The share endpoint doesn't send this header (shareSessionKey is fixed
    # for the call's whole lifetime, minted once at token-issuance) - but
    # even if a server did send one, share mode must never latch it, since
    # bridge.session_id has no meaning on this path.
    with _ScriptedChatServer(lines=["data:ok"], session_id="sess-should-be-ignored") as server:
        bridge = CoreChatBridgeLLM(
            share_id="share-abc123", share_session_key="share:share-abc123:deadbeef0000",
            base_url=server.base_url,
        )
        try:
            asyncio.run(_chat_and_collect(bridge, _user_ctx("hi")))
        finally:
            asyncio.run(bridge.aclose())

    assert bridge.session_id is None


def test_share_mode_with_no_auth_token_does_not_raise() -> None:
    # share_id alone must satisfy the "do we have a usable identity" guard -
    # a share-mode bridge never has (and never needs) an auth_token.
    with _ScriptedChatServer(lines=["data:Hello"]) as server:
        bridge = CoreChatBridgeLLM(
            share_id="share-abc123", share_session_key="share:share-abc123:deadbeef0000",
            base_url=server.base_url,
        )
        assert bridge.auth_token is None
        try:
            text = asyncio.run(_chat_and_collect(bridge, _user_ctx("hi")))
        finally:
            asyncio.run(bridge.aclose())

    assert text == "Hello"


def test_room_none_is_a_safe_default_no_stage_signals_attempted() -> None:
    # Default CoreChatBridgeLLM(...) construction (no room=) must behave
    # exactly as it did before this change - every existing call site
    # (main.py's current construction, pre-Part-5b) passes no room.
    with _ScriptedChatServer(lines=["data:Hello"]) as server:
        bridge = CoreChatBridgeLLM(agent_id="agent-1", auth_token="tok-123", base_url=server.base_url)
        assert bridge.room is None
        try:
            text = asyncio.run(_chat_and_collect(bridge, _user_ctx("hi")))
        finally:
            asyncio.run(bridge.aclose())

    assert text == "Hello"
