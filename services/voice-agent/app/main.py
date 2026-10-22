"""
voice-agent worker entrypoint.

Registers with the self-hosted LiveKit server (same server
`deploy/docker-compose.yml`'s `livekit` service and core/voice's
`LiveKitTokenService` already point at) and, for every call room a browser
joins via `CallPanel.tsx`/`VoiceCallController#issueToken`, runs an
`AgentSession` wiring together:
  - Silero VAD (turn detection - instant utterance boundaries instead of a
    push-to-talk button release)
  - SidecarWhisperSTT (stt_sidecar_whisper.py - self-hosted transcription)
  - CoreChatBridgeLLM (llm_core_bridge.py - bridges to core's existing
    /api/agents/{agentId}/chat pipeline; NOT a real local LLM)
  - ElevenLabs TTS (livekit-plugins-elevenlabs, this platform's preferred
    TTS provider - see SpeakToolCallback's javadoc)

Rooms named `sight-<agentId>-<8hex>` (VoiceCallController#issueToken with
`{"mode":"sight"}`) additionally run PERCEPTION mode: the same audio session
plus a camera frame watcher that samples the caller's video track and chats
about what it sees - see perception.py.

See README.md for the full picture and for exactly which pieces of this
pipeline could and could not be verified without live LiveKit/ElevenLabs
credentials.
"""

from __future__ import annotations

import asyncio
import dataclasses
import logging
import re

import aiohttp
from livekit import rtc
from livekit.agents import (
    Agent,
    AgentSession,
    AutoSubscribe,
    JobContext,
    JobProcess,
    WorkerOptions,
    cli,
)
from livekit.agents.voice.turn import TurnHandlingOptions
from livekit.plugins import elevenlabs, silero

from . import config, edge_sight, perception
from .llm_core_bridge import CoreChatBridgeLLM, _publish_stage
from .stt_sidecar_whisper import SidecarWhisperSTT
from .tts_sarvam import SarvamTTS

logging.basicConfig(level=config.LOG_LEVEL)
logger = logging.getLogger("voice-agent.main")

# Matches VoiceCallController#issueToken's room-naming convention EXACTLY:
#   String roomName = mode + "-" + agentId + "-" + shortId();
# where mode is "voice" (audio-only call) or "sight" (perception mode - same
# audio pipeline PLUS the camera frame watcher, see perception.py), agentId
# is a UUID's canonical 36-char form and shortId() is 8 hex chars
# (UUID.randomUUID().toString().substring(0, 8)). This is the ONLY place the
# agent id (and now the mode) travels from browser -> LiveKit -> this worker
# today - JobContext carries a room name, not a custom payload - so this
# regex is load-bearing. If VoiceCallController's room-naming ever changes,
# this worker will start failing loudly (see entrypoint below) rather than
# silently misrouting calls.
_ROOM_NAME_RE = re.compile(
    r"^(?P<mode>voice|sight)-"
    r"(?P<agent_id>[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
    r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12})-[0-9a-fA-F]{8}$"
)

# Anonymous agent-share rooms (AgentShareVoiceController#token, core/voice's
# public/no-login counterpart of VoiceCallController#issueToken) - same mode
# vocabulary, "share-" prefixed so this worker can tell a share room from an
# authenticated one by NAME ALONE, before it even reads participant metadata.
# shareId shape matches AgentShareService.SHARE_ID_SHAPE exactly (16-64 chars
# of [A-Za-z0-9_-]). See RoomTarget/_extract_share_context below for the rest
# of this contract.
_SHARE_ROOM_NAME_RE = re.compile(
    r"^share-(?P<mode>voice|sight)-"
    r"(?P<share_id>[A-Za-z0-9_-]{16,64})-[0-9a-fA-F]{8}$"
)


@dataclasses.dataclass(frozen=True)
class RoomTarget:
    """What `_parse_room_name` extracted - exactly one of `agent_id`/`share_id`
    is set, discriminating an authenticated call room from an anonymous
    agent-share room."""

    mode: str  # "voice" | "sight"
    agent_id: str | None
    share_id: str | None

    @property
    def is_share(self) -> bool:
        return self.share_id is not None

    @property
    def display_id(self) -> str:
        """Cosmetic id for log lines only - never used to build a request URL."""
        return self.agent_id if self.agent_id else f"share:{self.share_id}"


def _parse_room_name(room_name: str) -> RoomTarget | None:
    """RoomTarget from the room name, or None if it matches neither the
    authenticated nor the anonymous agent-share shape."""
    share_match = _SHARE_ROOM_NAME_RE.fullmatch(room_name)
    if share_match:
        return RoomTarget(
            mode=share_match.group("mode"), agent_id=None, share_id=share_match.group("share_id")
        )
    match = _ROOM_NAME_RE.fullmatch(room_name)
    if match:
        return RoomTarget(mode=match.group("mode"), agent_id=match.group("agent_id"), share_id=None)
    return None


def _extract_tts_voice(participant: rtc.RemoteParticipant) -> tuple[str | None, str | None]:
    """Optional per-call reply voice, carried on the SAME participant metadata
    JSON `_extract_auth_token`/`_extract_share_context` already read
    (`{"ttsVoice": "<id>", "ttsVoiceProvider": "elevenlabs"|"sarvam"}` -
    VoiceCallController#issueToken adds it when the caller picked one on the
    call panel; see VoiceTokenRequest). Anonymous share callers have no way to
    list voices, so this only ever arrives on authenticated calls today, but
    the field is honoured on share metadata identically for forward-compat.

    Returns (voice, provider) - both None when absent/unparseable. `provider`
    tags which backend the id belongs to: the caller cannot know which TTS
    plugin this worker will resolve (auto order), so a mismatched override
    (e.g. an ElevenLabs voice id while this worker is on Sarvam) is ignored
    rather than misapplied - see _build_tts_plugin.
    """
    metadata = (participant.metadata or "").strip()
    if not metadata.startswith("{"):
        return None, None
    import json

    try:
        parsed = json.loads(metadata)
    except (ValueError, AttributeError):
        return None, None
    if not isinstance(parsed, dict):
        return None, None
    voice = parsed.get("ttsVoice")
    provider = parsed.get("ttsVoiceProvider")
    # Voice ids are opaque provider strings; cap them so a junk field can't
    # bloat metadata handling, and keep them printable for logs.
    voice = voice.strip() if isinstance(voice, str) else None
    provider = provider.strip() if isinstance(provider, str) else None
    if voice and len(voice) > 120:
        voice = None
    if provider and len(provider) > 40:
        provider = None
    return voice or None, provider or None


def _tts_voiced() -> bool:
    """Whether _build_tts_plugin below can produce a WORKING plugin on this
    deployment - same provider resolution, plus the key check. Reported to
    the browser on agent transcript lines (the `voiced` field): False means
    "no server-side voice exists", and the caller speaks replies through
    its own on-device/browser voice chain instead - a keyless install still
    answers in voice.
    """
    provider = config.TTS_PROVIDER
    if provider not in ("auto", "elevenlabs", "sarvam"):
        provider = "auto"
    use_elevenlabs = provider == "elevenlabs" or (
        provider == "auto" and bool(config.ELEVENLABS_API_KEY)
    )
    if use_elevenlabs:
        return bool(config.ELEVENLABS_API_KEY)
    return bool(config.SARVAM_API_KEY)


def _build_tts_plugin(voice_override: str | None = None, provider_override: str | None = None):
    """TTS provider selection - see config.TTS_PROVIDER's comment for why
    this exists instead of hardcoding one backend. "auto" prefers ElevenLabs
    (matches SpeakToolCallback's platform-wide preference order) but falls
    back to Sarvam if no ElevenLabs key is set at all; it can NOT detect an
    ElevenLabs key that's present but unusable (e.g. a free-tier 402) - use
    an explicit VOICE_AGENT_TTS_PROVIDER=sarvam for that case.

    `voice_override`/`provider_override` come from _extract_tts_voice: the
    caller's own per-call voice pick. Applied only when provider_override is
    absent or names the provider this worker actually resolved to - an
    ElevenLabs voice id fed to Sarvam's `speaker` field would just fail
    server-side, which is why the override is provider-tagged.

    Returns None when the resolved provider has no credential at all: a
    session with a guaranteed-failing TTS node is strictly worse than one
    with tts=None (text-only turns still stream, the caller's browser speaks
    them locally - see _tts_voiced's comment).
    """
    provider = config.TTS_PROVIDER
    if provider not in ("auto", "elevenlabs", "sarvam"):
        logger.warning("unknown VOICE_AGENT_TTS_PROVIDER=%r, falling back to auto", provider)
        provider = "auto"

    use_elevenlabs = provider == "elevenlabs" or (
        provider == "auto" and bool(config.ELEVENLABS_API_KEY)
    )
    if use_elevenlabs:
        if not config.ELEVENLABS_API_KEY:
            logger.warning(
                "TTS provider 'elevenlabs' has no ELEVENLABS_API_KEY - "
                "no plugin; callers speak replies in the browser instead"
            )
            return None
        voice_id = config.ELEVENLABS_VOICE_ID
        if voice_override and provider_override in (None, "elevenlabs"):
            logger.info("caller picked ElevenLabs voice %s for this call", voice_override)
            voice_id = voice_override
        logger.info("TTS provider: elevenlabs (voice=%s)", voice_id)
        return elevenlabs.TTS(
            voice_id=voice_id,
            model=config.ELEVENLABS_MODEL,
            api_key=config.ELEVENLABS_API_KEY or None,
        )

    if not config.SARVAM_API_KEY:
        logger.warning(
            "no SARVAM_API_KEY (and TTS_PROVIDER did not resolve to elevenlabs) - "
            "no plugin; callers speak replies in the browser instead"
        )
        return None
    speaker = config.SARVAM_SPEAKER or None
    if voice_override and provider_override in (None, "sarvam"):
        logger.info("caller picked Sarvam speaker %s for this call", voice_override)
        speaker = voice_override
    logger.info("TTS provider: sarvam (speaker=%s)", speaker or "<model default>")
    return SarvamTTS(
        api_key=config.SARVAM_API_KEY,
        target_language_code=config.SARVAM_TARGET_LANGUAGE_CODE,
        model=config.SARVAM_MODEL,
        speaker=speaker,
    )


def _extract_auth_token(participant: rtc.RemoteParticipant) -> str | None:
    """Resolves the caller's own Keycloak access token so CoreChatBridgeLLM
    can call core's /api/agents/{agentId}/chat AS that user - reusing their
    real identity/tenant/permissions instead of building new auth
    infrastructure (per the build brief).

    CONFIRMED CONTRACT (reconciled against the actual parallel implementation -
    LiveKitTokenService.issueToken(String,String,String,String) in
    core/voice, which landed after this worker was first written):

      1. `participant.attributes["authToken"]`/`["coreAccessToken"]` - LiveKit
         access-token `attributes` claim, visible on every participant with no
         parsing needed. core/voice does NOT currently set this (only
         `metadata`, #2 below) - kept as a check for forward-compatibility if
         that ever changes, costs nothing to also check.
      2. `participant.metadata` (LiveKit access-token `metadata` claim, a
         plain string) - THIS is what core/voice actually populates:
         `LiveKitTokenService#buildParticipantMetadata` serializes
         `Map.of("coreAccessToken", callerAccessToken)` via Jackson, i.e. the
         real wire shape is `{"coreAccessToken": "<jwt>"}`. `authToken`/
         `auth_token` are also checked for the same forward-compatibility
         reason as #1. A bare-JWT metadata string (no JSON) is accepted too,
         in case a future caller sets it directly.
      3. VOICE_AGENT_DEV_STATIC_BEARER_TOKEN env var - standalone/local
         testing only, see config.py's comment on why this must never be set
         in a real deployment.

    Returns None (never raises) when nothing is found - CoreChatBridgeLLM
    fails LOUDLY on the first turn that actually needs the token (a clear
    log line + APIConnectionError, not a silent unauthenticated call), per
    the build brief's explicit instruction not to proceed silently.
    """
    attr_token = (
        participant.attributes.get("coreAccessToken")
        or participant.attributes.get("authToken")
        or participant.attributes.get("auth_token")
    )
    if attr_token:
        return attr_token

    metadata = (participant.metadata or "").strip()
    if metadata:
        if metadata.startswith("{"):
            import json

            try:
                parsed = json.loads(metadata)
                token = (
                    parsed.get("coreAccessToken")
                    or parsed.get("authToken")
                    or parsed.get("auth_token")
                )
                if token:
                    return token
            except (ValueError, AttributeError):
                logger.warning(
                    "participant %s metadata looked like JSON but failed to parse",
                    participant.identity,
                )
        elif metadata.count(".") == 2:
            # looks like a raw JWT (header.payload.signature) - use as-is
            return metadata

    if config.DEV_STATIC_BEARER_TOKEN:
        logger.warning(
            "using VOICE_AGENT_DEV_STATIC_BEARER_TOKEN for participant %s - "
            "this must NEVER be set in a real deployment",
            participant.identity,
        )
        return config.DEV_STATIC_BEARER_TOKEN

    return None


def _extract_share_context(participant: rtc.RemoteParticipant) -> tuple[str, str] | None:
    """Resolves the anonymous agent-share identity for a `share-` room -
    the counterpart of `_extract_auth_token` for `target.is_share` rooms.

    `AgentShareVoiceController#token` (core/voice) mints the LiveKit token's
    `metadata` claim as JSON: `{"shareId": "<shareId>", "shareSessionKey":
    "share:<shareId>:<ipHash>"}` (see that controller's javadoc for the full
    wire contract - this is the anonymous-viewer counterpart of
    `LiveKitTokenService#buildParticipantMetadata`'s `{"coreAccessToken":
    ...}` shape `_extract_auth_token` reads). `shareId` is this surface's
    entire credential (possession of an unguessable id, same posture as
    every other agent-share endpoint) - `shareSessionKey` was computed
    server-side, once, at token-issuance time (where the visitor's real IP
    was visible) and is echoed back verbatim on every turn so
    AgentShareController#chatStream's per-visitor session/rate-limit
    bookkeeping stays consistent with the push-to-talk surface's.

    Returns None (never raises) when metadata is absent, not JSON, or
    missing either field - the caller then refuses the call the same
    audible way a missing auth_token does for an authenticated room.
    """
    metadata = (participant.metadata or "").strip()
    if not metadata or not metadata.startswith("{"):
        return None
    import json

    try:
        parsed = json.loads(metadata)
    except (ValueError, AttributeError):
        logger.warning(
            "participant %s metadata looked like JSON but failed to parse (share room)",
            participant.identity,
        )
        return None
    if not isinstance(parsed, dict):
        return None
    share_id = parsed.get("shareId")
    share_session_key = parsed.get("shareSessionKey")
    if share_id and share_session_key:
        return share_id, share_session_key
    return None


def prewarm(proc: JobProcess) -> None:
    # Silero's own documented pattern (see livekit-plugins-silero's
    # VAD.load() docstring): loading the ONNX model is blocking and should
    # happen once per worker process, not per call.
    # min_silence_duration tuned down from the plugin's 0.55s default - see
    # config.VAD_MIN_SILENCE_DURATION_S's comment for why this is one of the
    # highest-leverage per-turn latency wins available with zero
    # infrastructure change.
    proc.userdata["vad"] = silero.VAD.load(min_silence_duration=config.VAD_MIN_SILENCE_DURATION_S)


async def entrypoint(ctx: JobContext) -> None:
    target = _parse_room_name(ctx.room.name)
    if target is None:
        logger.error(
            "room name %r does not match the expected "
            "'(voice|sight)-<agentId>-<shortId>' or 'share-(voice|sight)-<shareId>-<shortId>' "
            "shape (see VoiceCallController#issueToken / AgentShareVoiceController#token) - "
            "refusing this job",
            ctx.room.name,
        )
        return
    # Anonymous agent-share voice/sight (previously a stalled TODO here,
    # dated 2026-08-07 - now wired end-to-end). `target.is_share` selects
    # between two mutually exclusive identities for the rest of this job:
    # `agent_id`/`auth_token` (authenticated room, forwards the caller's own
    # Keycloak token) or `share_id`/`share_session_key` (anonymous share
    # room, forwards the shareId AgentShareVoiceController#token minted -
    # possession of it is the only credential, per that controller's
    # javadoc). Both pairs flow into CoreChatBridgeLLM/perception.py/
    # edge_sight.py identically from here on - see _extract_share_context
    # below for where share_id/share_session_key actually come from.
    mode, agent_id = target.mode, target.agent_id

    # Perception concurrency cap - claimed BEFORE connect so the subscribe
    # decision below can honor it: a sight room past the cap never subscribes
    # to video at all (it still gets the full audio pipeline + a spoken
    # refusal, see the greeting branch below).
    perception_slot: str | None = None
    if mode == "sight":
        perception_slot = perception.acquire_perception_slot(ctx.room.name)

    # Video is only subscribed when this room actually gets a perception
    # slot - an audio room (or an over-cap sight room) keeps the original
    # AUDIO_ONLY subscription and pays nothing for the caller's camera track.
    await ctx.connect(
        auto_subscribe=AutoSubscribe.SUBSCRIBE_ALL if perception_slot else AutoSubscribe.AUDIO_ONLY
    )

    # Perception-mode state, populated further down once the caller's auth
    # token is known. The cleanup callback is registered NOW - before
    # anything below can raise - so the concurrency slot and the core-side
    # vision session are released however this job ends. It only dereferences
    # names that are guaranteed bound by the time their guard is truthy
    # (vision_session_id is only ever set after auth_token is).
    watcher_task: asyncio.Task | None = None
    perception_http: aiohttp.ClientSession | None = None
    vision_session_id: str | None = None

    async def _perception_cleanup() -> None:
        if watcher_task is not None and not watcher_task.done():
            watcher_task.cancel()
        if perception_http is not None:
            if vision_session_id:
                await perception.end_vision_session(
                    perception_http, vision_session_id, auth_token=auth_token, share_id=share_id
                )
            await perception_http.close()
        perception.release_perception_slot(perception_slot)

    if mode == "sight":
        ctx.add_shutdown_callback(_perception_cleanup)

    participant = await ctx.wait_for_participant()
    auth_token: str | None = None
    share_id: str | None = None
    share_session_key: str | None = None
    if target.is_share:
        share_context = _extract_share_context(participant)
        if share_context:
            share_id, share_session_key = share_context
        else:
            logger.error(
                "no shareId/shareSessionKey found on participant %s (room=%s) - "
                "see main.py's _extract_share_context docstring for the assumed "
                "metadata contract this depends on",
                participant.identity,
                ctx.room.name,
            )
    else:
        auth_token = _extract_auth_token(participant)
        if not auth_token:
            logger.error(
                "no bearer token found on participant %s (room=%s, agent=%s) - "
                "see main.py's _extract_auth_token docstring for the assumed "
                "metadata/attributes contract this depends on",
                participant.identity,
                ctx.room.name,
                agent_id,
            )

    vad = ctx.proc.userdata["vad"]
    stt_plugin = SidecarWhisperSTT(room=ctx.room)
    llm_plugin = CoreChatBridgeLLM(
        agent_id=agent_id,
        auth_token=auth_token,
        share_id=share_id,
        share_session_key=share_session_key,
        room=ctx.room,
        # Whether this deployment's TTS plugin can actually speak - reported
        # on agent transcript lines so the browser speaks them locally when
        # the server has no voice (no ElevenLabs/Sarvam key installed).
        tts_voiced=_tts_voiced(),
    )
    # The caller's own voice pick for THIS call, if they made one on the
    # panel (ttsVoice/ttsVoiceProvider on the participant metadata - see
    # _extract_tts_voice). Absent = config defaults, unchanged.
    tts_voice, tts_voice_provider = _extract_tts_voice(participant)
    tts_plugin = _build_tts_plugin(tts_voice, tts_voice_provider)

    session = AgentSession(
        stt=stt_plugin,
        vad=vad,
        llm=llm_plugin,
        tts=tts_plugin,
        # Explicit "vad" turn detection rather than the SDK's default
        # (LiveKit Cloud's semantic `inference.TurnDetector()`, confirmed by
        # reading agent_session.py directly) - this deployment is fully
        # self-hosted with no LiveKit Cloud inference credential, and VAD
        # turn detection is exactly what the build brief asked for.
        turn_handling=TurnHandlingOptions(turn_detection="vad"),
    )
    if tts_plugin is None:
        # No server-side voice on this install: keep the session text-only -
        # default.tts_node raises when tts is None AND audio output is still
        # enabled (livekit/agents/voice/agent.py). Turns keep streaming text;
        # the caller's browser speaks each reply from the `voiced:false`
        # transcript events instead.
        session.output.set_audio_enabled(False)

    # Reliability blueprint Phase 4 Part 5b step 3 (Call AI 7-stage
    # lifecycle): the two SDK-native state events this session already
    # emits internally are the honest source for "Listening"/"Speaking"/
    # "Completed" - "Understanding"/"Reasoning"/"Generating" come from
    # llm_core_bridge.py, "Transcribing" from stt_sidecar_whisper.py (both
    # already wired via the room=ctx.room kwargs above). Fire-and-forget via
    # asyncio.create_task so a stage-signal hiccup can never block turn
    # handling - _publish_stage itself is also non-fatal on failure.
    def _on_agent_state_changed(ev) -> None:
        if ev.new_state == "speaking":
            asyncio.create_task(_publish_stage(ctx.room, "speaking"))
        elif ev.old_state == "speaking":
            # Left "speaking" - the agent has finished talking, so from the
            # caller's perspective this turn is over.
            asyncio.create_task(_publish_stage(ctx.room, "completed"))

    def _on_user_state_changed(ev) -> None:
        if ev.new_state == "speaking":
            asyncio.create_task(_publish_stage(ctx.room, "listening"))

    session.on("agent_state_changed", _on_agent_state_changed)
    session.on("user_state_changed", _on_user_state_changed)

    # The Agent's `instructions` are NOT what drives replies - core's own
    # AgentChatService owns the system prompt/persona/tools entirely
    # server-side (see llm_core_bridge.py's module docstring). This string
    # only satisfies the SDK's required constructor field; it is never sent
    # anywhere by CoreChatBridgeLLM.chat().
    agent = Agent(
        instructions=(
            "Not used for generation. The LLM leg of this pipeline is "
            "bridged to core's own /api/agents/{agentId}/chat, which owns "
            "the real system prompt, tools, memory, and guardrails - see "
            "llm_core_bridge.py."
        )
    )

    def _on_participant_disconnected(p: rtc.RemoteParticipant) -> None:
        if p.identity == participant.identity:
            logger.info("caller %s left room %s, shutting down job", p.identity, ctx.room.name)
            ctx.shutdown(reason="caller disconnected")

    ctx.room.on("participant_disconnected", _on_participant_disconnected)

    # S-edge live-call bridge (edge_sight.py): the browser may analyze camera
    # frames ON-DEVICE and publish only text reports over the "edge-sight"
    # data topic - raw frames never reach this worker or any hosted vision
    # model. Registered for BOTH room modes (a client running edge sight
    # requests a plain voice room, since no video track is ever published);
    # it costs nothing until a packet actually arrives.
    edge_bridge = edge_sight.EdgeSightBridge(
        room=ctx.room,
        session=session,
        bridge=llm_plugin,
        agent_id=agent_id,
        auth_token=auth_token,
        share_id=share_id,
        share_session_key=share_session_key,
        participant_identity=participant.identity,
    )
    ctx.room.on("data_received", edge_bridge.handle_packet)
    ctx.add_shutdown_callback(edge_bridge.aclose)

    logger.info("session.start() beginning (room=%s, agent=%s)", ctx.room.name, agent_id)
    try:
        await session.start(agent=agent, room=ctx.room)
    except Exception:
        logger.exception(
            "session.start() raised - call is dead in the water (room=%s, agent=%s)",
            ctx.room.name,
            agent_id,
        )
        raise
    logger.info("session.start() returned cleanly (room=%s, agent=%s)", ctx.room.name, agent_id)

    # Perception ("sight") mode - see perception.py's module docstring for
    # the full pipeline. Requires all three of: a sight room, a concurrency
    # slot (checked before connect above), and the caller's auth token. The
    # core-side vision session is the entitlement/quota/audit gate: a
    # 403/429/503 there (plan lacks live-vision, quota spent, platform flag
    # off) degrades this room to audio-only - logged and reflected in the
    # greeting below, never a crash.
    if mode == "sight" and perception_slot and (auth_token or share_id):
        perception_http = aiohttp.ClientSession()
        vision_session_id = await perception.start_vision_session(
            perception_http, agent_id=agent_id, auth_token=auth_token, share_id=share_id
        )
        if vision_session_id:
            watcher = perception.FrameWatcher(
                room=ctx.room,
                session=session,
                bridge=llm_plugin,
                http=perception_http,
                agent_id=agent_id,
                auth_token=auth_token,
                share_id=share_id,
                share_session_key=share_session_key,
                vision_session_id=vision_session_id,
                participant=participant,
            )
            watcher_task = asyncio.create_task(watcher.run())
            logger.info(
                "perception watcher started (room=%s, agent=%s, visionSession=%s)",
                ctx.room.name,
                agent_id,
                vision_session_id,
            )

    if auth_token or share_id:
        # session.generate_reply(instructions=...) was silently producing
        # NOTHING - confirmed live 2026-08-04 across multiple real calls
        # (worker logs show TTS provider selection, then complete silence,
        # no error, no LLM bridge call, no TTS synthesis, until the caller
        # gave up and hung up). Root cause: generate_reply's `instructions`
        # is a system-level directive, not a user chat turn, and
        # CoreChatBridgeLLM only knows how to forward a real user message to
        # core's /chat endpoint - with no user turn to extract, it correctly
        # no-ops (see test_llm_core_bridge.py's
        # test_empty_user_turn_produces_no_chunks_and_no_http_call - that's
        # tested, intentional behavior, not a bug in the bridge itself). A
        # scripted opening line doesn't need the real agent brain anyway, so
        # speak it directly via TTS instead, bypassing the LLM bridge
        # entirely for just this one line - same session.say(...) pattern
        # the auth-failure branch right below already uses.
        if mode == "sight" and watcher_task is not None:
            greeting = (
                "Hi! I can hear you, and I'm watching your camera - "
                "show me anything and I'll tell you what I notice."
            )
        elif mode == "sight":
            # The polite over-cap/entitlement refusal: a sight room that got
            # no perception slot (PERCEPTION_MAX_ROOMS reached) or whose
            # vision session was refused by core still runs the full audio
            # pipeline - the caller keeps a working call either way, and the
            # specific reason is already in the logs.
            greeting = (
                "Hi! I can hear you, but I can't watch your camera on this "
                "call right now, so we'll go audio-only. How can I help?"
            )
        else:
            greeting = "Hi! How can I help you today?"
        try:
            await session.say(greeting)
            logger.info("greeting session.say() returned cleanly (room=%s)", ctx.room.name)
        except Exception:
            logger.exception("greeting session.say() raised (room=%s)", ctx.room.name)
            raise
    else:
        # Fail loudly and audibly rather than silently sitting in the room -
        # every subsequent turn would fail the same way inside
        # CoreChatBridgeLLM anyway (see its _run method).
        try:
            await session.say(
                "Voice calling isn't fully set up for this session yet - the call "
                "couldn't be authenticated. Please try the chat instead for now."
            )
        except Exception:
            logger.exception("auth-failure session.say() raised (room=%s)", ctx.room.name)
            raise


if __name__ == "__main__":
    import sys

    # SEC-044: refuse to start without real LiveKit credentials (config.py).
    try:
        config.check_startup_config(sys.argv)
    except config.ConfigError as e:
        logger.error("voice-agent startup aborted: %s", e)
        sys.exit(f"voice-agent: {e}")
    cli.run_app(
        WorkerOptions(
            entrypoint_fnc=entrypoint,
            prewarm_fnc=prewarm,
            agent_name=config.AGENT_NAME,
            ws_url=config.LIVEKIT_URL,
            api_key=config.LIVEKIT_API_KEY,
            api_secret=config.LIVEKIT_API_SECRET,
        )
    )
