"""
Custom LiveKit Agents "LLM" plugin that is not a language model at all - it's
an HTTP/SSE bridge into `core`'s EXISTING agent chat pipeline
(`POST /api/agents/{agentId}/chat`), the same endpoint/pipeline
`web/app/lib/api.ts`'s `streamChat` drives for the text Playground. This is a
deliberate architecture decision from the build brief: full tool-calling,
memory, guardrails, and the tenant's configured LLM already live there - this
file's only job is protocol translation, not reimplementing any of that in
Python.

Verified against the installed `livekit-agents` 1.6.8 package (see
`livekit/agents/llm/llm.py`): the only abstract method on `LLM` is `chat()`,
which must return an `LLMStream` whose only abstract method is `_run()`.
`_run()` pushes `llm.ChatChunk` objects into `self._event_ch` (confirmed
against LiveKit's own reference implementation, `livekit/agents/inference/
llm.py`'s `LLMStream._run`, which streams an OpenAI-compatible SSE response
the exact same way). Chunk-by-chunk `ChatChunk(delta=ChoiceDelta(content=...))`
is what lets `Agent.default.tts_node` begin synthesizing speech before the
full reply has arrived - the actual point of bridging a stream instead of
waiting for `AgentChatService#chat`'s full text.

SSE parsing mirrors `web/app/lib/api.ts`'s `streamChat` byte-for-byte:
  - each event is a raw `data:<token>` line - EXACTLY a 5-char prefix, no
    framing space eaten (core's writer never emits one of its own; see
    api.ts's own comment on this - a stray space-eating slice(6) previously
    ate real space-only tokens and ran replies together with no word
    boundaries. Copied here verbatim to avoid reintroducing that bug in the
    Python port).
  - a line starting with `data:@@AGENT_CHAT_STREAM_ERROR_9f2b1c@@` is a
    mid-stream provider failure (mirrors `AgentController.STREAM_ERROR_MARKER`
    in core/agents) - raised as an `APIStatusError` instead of being spoken.
  - the `X-Session-Id` response header carries the session id back once per
    call; kept on `CoreChatBridgeLLM` (not per-stream) and resent on every
    subsequent turn so conversation memory persists for the call's whole
    duration - the same `sessionId`/`onSessionId` pattern
    `AgentPlayground.tsx`/`CallPanel.tsx` already use, just carried across
    many turns server-side instead of React state.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid

import aiohttp
from livekit import rtc
from livekit.agents import (
    DEFAULT_API_CONNECT_OPTIONS,
    NOT_GIVEN,
    APIConnectionError,
    APIConnectOptions,
    APIStatusError,
    NotGivenOr,
)
from livekit.agents import llm as agents_llm

from . import config

logger = logging.getLogger("voice-agent.llm_core_bridge")

# Mirror of AgentController.STREAM_ERROR_MARKER (core/agents) and
# web/app/lib/api.ts's STREAM_ERROR_MARKER constant - keep all three in sync
# if this ever changes.
STREAM_ERROR_MARKER = "@@AGENT_CHAT_STREAM_ERROR_9f2b1c@@"

# --- Reliability blueprint Phase 4 Part 5b: Call AI 7-stage lifecycle -----
#
# Uniform stage-transition signal for the 7-stage Call AI lifecycle UI
# (Listening / Transcribing / Understanding / Reasoning / Generating /
# Speaking / Completed). No app-level signaling pattern for this existed
# anywhere in this codebase before this change (confirmed by grep - zero
# hits for publish_data/DataReceived under voice-agent/) - this uses the
# livekit-rtc SDK's own generic data-channel
# (`rtc.LocalParticipant.publish_data`), which is already open on every
# call's room and already imported for this job (main.py imports `rtc`
# directly). A single topic/schema is used for all 7 stages rather than
# mixing this with the SDK's separate `agent_state_changed`
# listening/thinking/speaking attribute mechanism (too coarse - "thinking"
# alone bundles Understanding+Reasoning+Generating), so the browser only
# needs one `RoomEvent.DataReceived` listener.
#
# "Understanding", "Reasoning" and "Generating" are the three stages this
# file can genuinely observe first-hand (see _CoreChatBridgeStream._run /
# _handle_sse_line below). "Listening", "Speaking" and "Completed" belong to
# main.py's SDK-native agent_state_changed/user_state_changed hooks, and
# "Transcribing" belongs to stt_sidecar_whisper.py - wiring those up is out
# of scope for this file (this change is scoped to llm_core_bridge.py only;
# see CoreChatBridgeLLM's `room` kwarg docstring below for the resulting gap
# and the one-line follow-up it still needs in main.py).
_STAGE_SIGNAL_TOPIC = "agent-stage"

# Same generic data channel, a second topic: the per-turn TEXT of the call.
# "agent-stage" only ever carried the lifecycle label; the caller's live-call
# transcript panel (CallPanel.tsx's CallTranscriptPanel / the share page's
# ShareCallPanel) additionally retains every completed exchange - the same
# running record push-to-talk already keeps on screen. Payload:
#   {"id": <uuid>, "role": "user"|"agent", "text": str,
#    "turnId": str|None, "ts": <epoch>}
# `id` is per-event unique so the browser can dedupe; `turnId` correlates a
# user line with the agent line it produced (the bridge's per-turn
# request_id). User lines are published here in _run (the message actually
# sent to core = what latest_user_text() returned), agent lines when this
# stream's accumulated reply is known - see _CoreChatBridgeStream.
_TRANSCRIPT_TOPIC = "call-transcript"


async def _publish_transcript(
    room: rtc.Room | None,
    role: str,
    text: str,
    *,
    turn_id: str | None = None,
    voiced: bool | None = None,
) -> None:
    """Best-effort transcript line over the room's data channel.

    Same fire-and-forget posture as _publish_stage - a data-channel hiccup
    must never take the call down; the text is retained client-side, audio
    already played regardless. Empty/whitespace text is never published.

    `voiced` (agent lines only): whether this worker is actually producing
    TTS audio for the turn - main.py's _tts_voiced() reports False on an
    install with no ElevenLabs/Sarvam key. The caller's browser reads the
    flag and speaks the line through its own on-device/browser voice chain
    instead, so a keyless deployment still answers in voice (just not with
    the server voice). Omitted entirely for user lines - "voiced" means
    nothing there.
    """
    if room is None or not text or not text.strip():
        return
    message: dict[str, object] = {
        "id": str(uuid.uuid4()),
        "role": role,
        "text": text.strip(),
        "turnId": turn_id,
        "ts": time.time(),
    }
    if role == "agent" and voiced is not None:
        message["voiced"] = voiced
    payload = json.dumps(message).encode("utf-8")
    try:
        await room.local_participant.publish_data(
            payload, reliable=True, topic=_TRANSCRIPT_TOPIC
        )
    except Exception:
        logger.debug("transcript publish failed (non-fatal): %s", role, exc_info=True)


async def _publish_stage(
    room: rtc.Room | None,
    stage: str,
    *,
    turn_id: str | None = None,
    error: str | None = None,
) -> None:
    """Best-effort stage-transition signal over the room's data channel.

    Fire-and-forget/non-fatal on failure by design - matches this
    codebase's existing posture everywhere else in perception.py/main.py
    ("a signal failing must never take the call down"). `room` is None
    until whatever constructs `CoreChatBridgeLLM` passes one in - a silent
    no-op until then, not an error (see the `room` kwarg's docstring).
    """
    if room is None:
        return
    payload = json.dumps(
        {"stage": stage, "ts": time.time(), "turnId": turn_id, "error": error}
    ).encode("utf-8")
    try:
        await room.local_participant.publish_data(
            payload, reliable=True, topic=_STAGE_SIGNAL_TOPIC
        )
    except Exception:
        # Broad by design (see docstring) - a stage-signal hiccup (room not
        # connected yet, data-channel error, etc.) must never surface as a
        # call failure or interrupt the actual reply.
        logger.debug("stage signal publish failed (non-fatal): %s", stage, exc_info=True)


def latest_user_text(chat_ctx: agents_llm.ChatContext) -> str | None:
    """The transcript of the turn that just finished, i.e. what the caller
    said. core owns the rest of the conversation history server-side via
    sessionId (see CoreChatBridgeLLM's docstring) so - unlike a real LLM
    bridge - only the newest user utterance is ever sent, never the full
    chat_ctx.
    """
    for item in reversed(chat_ctx.items):
        if isinstance(item, agents_llm.ChatMessage) and item.role == "user":
            text = item.text_content
            if text and text.strip():
                return text.strip()
    return None


class CoreChatBridgeLLM(agents_llm.LLM):
    """One instance per call (constructed fresh per job in main.py's
    entrypoint), so `session_id`/`agent_id`/`auth_token` naturally scope to
    that one call's lifetime - `session_id` starts empty and is filled in
    from the first response's `X-Session-Id` header, then reused for every
    later turn in the same call.
    """

    def __init__(
        self,
        *,
        agent_id: str | None = None,
        auth_token: str | None = None,
        share_id: str | None = None,
        share_session_key: str | None = None,
        base_url: str | None = None,
        http_session: aiohttp.ClientSession | None = None,
        room: rtc.Room | None = None,
        tts_voiced: bool = True,
    ) -> None:
        super().__init__()
        self.agent_id = agent_id
        self.auth_token = auth_token
        # Anonymous agent-share rooms (main.py's `_parse_room_name`/
        # `_extract_share_context`): `share_id`/`share_session_key` replace
        # `agent_id`/`auth_token` entirely for this call's lifetime - a
        # share-mode instance never has an auth_token and never will (an
        # anonymous visitor has no Keycloak identity to forward). See
        # `_CoreChatBridgeStream._run`'s branch on `bridge.share_id` for the
        # request-shape difference this drives (AgentShareController#chatStream
        # vs AgentController's authenticated /chat).
        self.share_id = share_id
        self.share_session_key = share_session_key
        self.session_id: str | None = None
        self._base_url = (base_url or config.CORE_API_BASE_URL).rstrip("/")
        self._session = http_session
        self._owns_session = http_session is None
        # Reliability blueprint Phase 4 Part 5b (see _publish_stage above).
        # Optional and defaults to None ON PURPOSE: this change's scope is
        # llm_core_bridge.py only, so main.py's construction call
        # (`CoreChatBridgeLLM(agent_id=agent_id, auth_token=auth_token)`,
        # main.py:249 as of this change) has NOT been updated to pass
        # `room=ctx.room` - until it is, every stage signal from this bridge
        # is a silent, harmless no-op (see _publish_stage's `room is None`
        # guard). Flagged in docs/AGENT-COORDINATION-2026-08-06.md as the
        # required one-line follow-up for whoever next owns main.py.
        self.room = room
        # Whether this deployment's TTS plugin can actually produce audio -
        # main.py's _tts_voiced() mirrors _build_tts_plugin's resolution. The
        # flag rides on agent transcript lines (the `voiced` field) so a
        # browser can fall back to its own voice chain on a keyless install.
        self._tts_voiced = tts_voiced

    @property
    def model(self) -> str:
        return "core-agent-chat-bridge"

    @property
    def provider(self) -> str:
        return "vegaduta-core"

    def _ensure_session(self) -> aiohttp.ClientSession:
        if self._session is None:
            self._session = aiohttp.ClientSession()
        return self._session

    def chat(
        self,
        *,
        chat_ctx: agents_llm.ChatContext,
        tools: list[agents_llm.Tool] | None = None,
        conn_options: APIConnectOptions = DEFAULT_API_CONNECT_OPTIONS,
        parallel_tool_calls: NotGivenOr[bool] = NOT_GIVEN,
        tool_choice: NotGivenOr[agents_llm.ToolChoice] = NOT_GIVEN,
        extra_kwargs: NotGivenOr[dict] = NOT_GIVEN,
    ) -> agents_llm.LLMStream:
        # tools/tool_choice/parallel_tool_calls are deliberately ignored -
        # core's own AgentChatService resolves the agent's configured tools
        # server-side; this bridge has no local tool-calling story of its
        # own (see module docstring).
        return _CoreChatBridgeStream(
            self, chat_ctx=chat_ctx, tools=tools or [], conn_options=conn_options
        )

    async def aclose(self) -> None:
        await super().aclose()
        if self._owns_session and self._session is not None:
            await self._session.close()
            self._session = None


class _CoreChatBridgeStream(agents_llm.LLMStream):
    def __init__(
        self,
        llm_v: CoreChatBridgeLLM,
        *,
        chat_ctx: agents_llm.ChatContext,
        tools: list[agents_llm.Tool],
        conn_options: APIConnectOptions,
    ) -> None:
        super().__init__(llm_v, chat_ctx=chat_ctx, tools=tools, conn_options=conn_options)
        self._bridge = llm_v
        # Reliability blueprint Phase 4 Part 5b: guards the one-shot
        # "generating" stage signal in _handle_sse_line below so it fires on
        # the FIRST real content chunk of this turn only, not every token.
        self._first_token_sent = False
        # Every content token this stream pushed to the SDK's TTS node,
        # accumulated so the full reply text can be published over the
        # "call-transcript" data topic when the turn settles - this is what
        # the caller's transcript panel retains while the audio plays.
        self._reply_parts: list[str] = []

    async def _run(self) -> None:
        bridge = self._bridge
        message = latest_user_text(self._chat_ctx)
        # Moved up from its previous position (just before the old
        # `body: dict[str, str] = {"message": message}` line) so it's
        # available to the empty-message canned-reply branch below too, and
        # to every stage signal in this method - see Part 5b/5c#1.
        request_id = str(uuid.uuid4())

        if not message:
            # Reliability blueprint Phase 4 Part 5c#1 (silent STT failure,
            # LiveKit path). Root cause: this branch previously just
            # `return`-ed, producing ZERO ChatChunks - indistinguishable to
            # AgentSession's TTS node from "the LLM legitimately had nothing
            # to say": no exception, no log-worthy event, no audio - the
            # caller hears dead silence with no idea anything went wrong.
            # Push-to-talk's equivalent (VoiceTurnAudioEmptyException,
            # VoiceCallController.java) is a Java exception on a separate,
            # synchronous, pre-stream code path that can't be ported
            # literally into an async LiveKit LLMStream._run() - but its
            # INTENT ("tell the person to repeat themselves, out loud")
            # ports directly, using the exact mechanism this file already
            # uses for real replies: push a canned ChatChunk into
            # self._event_ch (same call _handle_sse_line makes for real
            # content below), so AgentSession's normal TTS node speaks it
            # exactly like any other reply - no new plumbing.
            canned = "Sorry, I didn't catch that - could you repeat it?"
            self._event_ch.send_nowait(
                agents_llm.ChatChunk(
                    id=request_id,
                    delta=agents_llm.ChoiceDelta(
                        role="assistant",
                        content=canned,
                    ),
                )
            )
            # Retained-text parity with a real reply: the transcript panel
            # shows this same canned line once the TTS node has spoken it.
            await _publish_transcript(
                bridge.room, "agent", canned, turn_id=request_id,
                voiced=bridge._tts_voiced,
            )
            return

        if not bridge.share_id and not bridge.auth_token:
            # Fail loudly rather than call core unauthenticated, per the
            # build brief - see main.py's _extract_auth_token for why this
            # can legitimately be empty (the browser-side metadata wiring
            # is a parallel, possibly-not-yet-landed change). A share-mode
            # bridge (bridge.share_id set) never has an auth_token and never
            # needs one - it is exempt from this guard entirely, see below.
            raise APIConnectionError(
                "voice-agent has no bearer token for this call; refusing to "
                "call core unauthenticated (see main.py _extract_auth_token).",
                # Not retryable: a missing token won't appear on retry, and
                # the base LLMStream._main_task (livekit/agents/llm/llm.py)
                # retries every APIError up to conn_options.max_retry by
                # default (APIConnectionError.retryable defaults to True) -
                # without this, every utterance in an unauthenticated call
                # would burn 3 retries (~4s of backoff) before failing.
                retryable=False,
            )

        # Reliability blueprint Phase 4 Part 5b: "understanding" fires here,
        # right after we know there's a real message to send and the
        # auth-token guard has passed - the earliest point this file can
        # honestly say the call has moved past "still listening".
        await _publish_stage(bridge.room, "understanding", turn_id=request_id)

        # The caller's transcript panel shows what they were heard saying -
        # published here (not in stt_sidecar_whisper) so the retained text is
        # exactly the message sent to core, and only for turns that actually
        # reach the agent. Fires before the request goes out: a failed turn
        # still keeps the "You said" line, same as push-to-talk does.
        await _publish_transcript(bridge.room, "user", message, turn_id=request_id)

        session = bridge._ensure_session()

        if bridge.share_id:
            # Anonymous agent-share room (see __init__'s comment and
            # main.py's _extract_share_context): calls the PUBLIC,
            # unauthenticated counterpart of the endpoint below -
            # AgentShareController#chatStream, core/agents' share module.
            # No Authorization header (this route is permitAll - possession
            # of shareId is the only credential), no forceFastDepth (that
            # field doesn't exist on ShareChatStreamRequest - a share
            # viewer's turn always runs the agent's own configured
            # ReasoningDepth), and no X-Session-Id to read back: unlike the
            # authenticated endpoint (which MINTS a session id on first use
            # and expects the caller to remember it), the share endpoint's
            # session id - shareSessionKey - is fixed for this whole call's
            # lifetime and was already minted server-side at token-issuance
            # (AgentShareVoiceController#token), so there is nothing to
            # latch here.
            body_share: dict[str, object] = {
                "message": message,
                "sessionKey": bridge.share_session_key,
            }
            headers_share = {"Content-Type": "application/json"}
            url = f"{bridge._base_url}/api/share/agents/{bridge.share_id}/chat/stream"
            body = body_share
            headers = headers_share
        else:
            body = {"message": message}
            if bridge.session_id:
                body["sessionId"] = bridge.session_id

            headers = {
                "Authorization": f"Bearer {bridge.auth_token}",
                "Content-Type": "application/json",
            }
            url = f"{bridge._base_url}/api/agents/{bridge.agent_id}/chat"
            # Cross-agent contract (2026-08-06, see docs/AGENT-COORDINATION-2026-08-06.md):
            # AgentChatService gained a `forceFastDepth` turn-scoped override (new
            # 8-arg overload; the existing 6-arg overload delegates with
            # forceFastDepth=false, so every caller that doesn't know about this
            # is unaffected) that pins the turn to FAST reasoning depth regardless
            # of the agent's own configured ReasoningDepth, without touching that
            # stored configuration. AgentController binds this endpoint's request
            # via `@RequestBody ChatRequest` only (no @RequestParam) - it must be
            # a field in the JSON body, NOT a query string parameter, or Spring
            # silently drops it and Jackson defaults the record's boolean to
            # false. This bridge is a voice (LiveKit) call site, one of the two
            # call sites the contract designates to always force it: a live voice
            # call cannot tolerate CAREFUL/RIGOROUS/PLANNED-depth latency without
            # dead air on the line.
            body["forceFastDepth"] = True

        # Reliability blueprint Phase 4 Part 5b: "reasoning" fires here,
        # immediately before the request goes out - the request has been
        # sent, awaiting the LLM's first byte, genuinely "thinking before
        # speaking". ("Understanding" vs "reasoning" is a real, if coarse,
        # distinction this file CAN observe first-hand, unlike CallPanel.tsx's
        # push-to-talk path - see Part 5a caveat 1.)
        await _publish_stage(bridge.room, "reasoning", turn_id=request_id)

        # Reliability blueprint Phase 4 Part 5d (timeout budget rework).
        # Same class of bug as SidecarWhisperSTT's timeout fix - the SDK's
        # default APIConnectOptions.timeout=10.0 is tuned for a typical LLM
        # API call, not core's real agent pipeline (guardrails, tool calls,
        # memory, the tenant's actual model) behind a full SSE stream -
        # observed live: a real call timed out here even though it's the
        # SAME endpoint the text-chat playground streams from successfully.
        # Previously this was a single `total`-only (absolute) timeout,
        # floored at 60s - an aiohttp `total` has NO reset-on-activity, so a
        # legitimately slow-but-actively-streaming reply running past 60s
        # got killed even though core's own 90s MID-STREAM IDLE timeout
        # (application.yml, AgentChatService's Flux#timeout) would have let
        # it keep going. Split into the same idle-vs-absolute two-tier shape
        # core already uses: `sock_read` is aiohttp's per-read (idle) timeout
        # - resets on every chunk received - set to 100s (core's own 90s
        # idle bound + margin, so core's timeout fires first and this is
        # purely a backstop); `total` is the absolute ceiling regardless of
        # progress, set to 130s (requestLifecycle.ts's existing
        # DEFAULT_TIMEOUT_POLICY.absoluteTimeoutMs=120s default + margin, so
        # the idle timer - not this one - is what normally fires first).
        # `finally` publishes the accumulated reply: a mid-stream failure (the
        # STREAM_ERROR_MARKER APIStatusError, a dropped connection, a barge-in
        # cancellation) still retains the partial text that was actually spoken
        # - losing it would leave the caller hearing words the transcript
        # never showed.
        try:
            async with session.post(
                url,
                json=body,
                headers=headers,
                timeout=aiohttp.ClientTimeout(
                    total=130.0, sock_connect=10, sock_read=100.0
                ),
            ) as resp:
                if resp.status >= 400:
                    text = await resp.text()
                    raise APIStatusError(
                        f"core chat endpoint returned {resp.status}: {text}",
                        status_code=resp.status,
                        request_id=request_id,
                        body=text,
                    )

                # Share mode never sends this header (see the branch above) -
                # bridge.session_id stays None for the whole call, which is
                # correct: shareSessionKey is the fixed identity instead.
                if not bridge.share_id:
                    new_session_id = resp.headers.get("X-Session-Id")
                    if new_session_id and new_session_id != bridge.session_id:
                        logger.debug(
                            "core chat session id for agent %s: %s", bridge.agent_id, new_session_id
                        )
                        bridge.session_id = new_session_id

                buffer = ""
                async for raw_chunk in resp.content.iter_any():
                    buffer += raw_chunk.decode("utf-8", errors="ignore")
                    lines = buffer.split("\n")
                    buffer = lines.pop()
                    for line in lines:
                        self._handle_sse_line(line.rstrip("\r"), request_id)
                if buffer:
                    self._handle_sse_line(buffer.rstrip("\r"), request_id)
        except aiohttp.ClientError as e:
            raise APIConnectionError(f"failed to reach core chat endpoint at {url}: {e}") from e
        finally:
            reply = "".join(self._reply_parts).strip()
            if reply:
                await _publish_transcript(
                    bridge.room, "agent", reply, turn_id=request_id,
                    voiced=bridge._tts_voiced,
                )

    def _handle_sse_line(self, line: str, request_id: str) -> None:
        if not line.startswith("data:"):
            return
        # EXACTLY a 5-char prefix, no framing space eaten - see module
        # docstring / web/app/lib/api.ts's streamChat comment on why a 6-char
        # slice silently ate real space-only tokens.
        content = line[5:]
        if content.startswith(STREAM_ERROR_MARKER):
            message = content[len(STREAM_ERROR_MARKER):] or (
                "The response was interrupted. Please try again."
            )
            raise APIStatusError(message, status_code=200, request_id=request_id, body=None)
        if not content:
            return
        if not self._first_token_sent:
            # Reliability blueprint Phase 4 Part 5b: "generating" fires once,
            # on the FIRST real content token of this turn - fire-and-forget
            # (asyncio.create_task, not awaited) since this runs on the hot
            # per-token path and must not add latency to token delivery; the
            # publish itself is already non-fatal/best-effort (see
            # _publish_stage), so a lost task here is no worse than a lost
            # stage signal anywhere else in this file.
            self._first_token_sent = True
            asyncio.create_task(
                _publish_stage(self._bridge.room, "generating", turn_id=request_id)
            )
        self._reply_parts.append(content)
        self._event_ch.send_nowait(
            agents_llm.ChatChunk(
                id=request_id,
                delta=agents_llm.ChoiceDelta(role="assistant", content=content),
            )
        )
