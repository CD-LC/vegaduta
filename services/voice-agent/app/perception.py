"""
Perception ("sight") mode for the voice-agent worker - S1 of the live-vision
program (docs/LIVE-VISION-SENSE-RESEARCH-2026-08-05.md section 4.3).

A room named `sight-<agentId>-<8hex>` (minted by VoiceCallController#issueToken
when the browser asks for `{"mode":"sight"}`) keeps the ENTIRE existing audio
pipeline (Silero VAD -> SidecarWhisperSTT -> CoreChatBridgeLLM -> TTS, wired
in main.py) and adds one thing: a frame-watcher task that subscribes to the
caller's camera track, samples frames through a scene-change gate, and sends
accepted frames to core's existing `POST /api/agents/{agentId}/chat` as
inline image attachments (`source: "camera"`, the shared vision contract) -
so the agent *talks about what it sees* with voice-call latency, using the
same server-side brain/guardrails/memory as every other channel. Substantive
replies are spoken through the session's existing TTS leg; a bare "." reply
is the agent's silent-equivalent ("nothing changed meaningfully") and is not
spoken.

Frames are ephemeral by design (research doc section 4.5): analyzed, never
written anywhere - the only state kept here is one downscaled grayscale
thumbnail (the scene-gate reference) per room, in memory.

Quota/entitlement is core's job, not this worker's: a vision session is
opened at room start (`POST /api/vision/sessions`, mode LIVE) and every frame
turn carries `visionSessionId` + a `"camera"`-sourced attachment so
VisionUsageService counts it. A 403/429/503 from the session endpoint - or a
403/429 mid-call - degrades the room to audio-only, never crashes it. The
per-frame chat call goes through the SAME general endpoint
(`POST /api/agents/{agentId}/chat`) as every other turn, so it is also
subject to FreeTierUsageLimiterService's platform-hosted-LLM message/token
cap - which responds 403 ("<plan> daily limit reached"), not 429. Bug found
2026-08-07 (live "call+camera... no response" report, reproduced on
a production deployment): `_send_frame` only recognized 429 as quota-exhausted, so every
403 fell through to the generic RuntimeError path, which `_analyze` logs and
swallows silently (no spoken notice) AND does not stop the watcher - the
loop kept re-sending frames into the same exhausted daily cap every
~2 seconds for the rest of the call. Both status codes now map to
PerceptionQuotaExhaustedError.
"""
from __future__ import annotations

import asyncio
import base64
import io
import logging
import os
import re
import tempfile
import time

import aiohttp
import numpy as np
from livekit import rtc
from PIL import Image

from . import config
from .llm_core_bridge import STREAM_ERROR_MARKER, CoreChatBridgeLLM

logger = logging.getLogger("voice-agent.perception")

# The standing per-frame instruction (shared-contract wording): asks for a
# terse delta report, with "." as the explicit nothing-to-say sentinel so the
# agent isn't forced to narrate an unchanged scene every 2 seconds.
PERCEPTION_INSTRUCTION = (
    "The user's camera shows this frame. Briefly note anything new or "
    "important; stay silent-equivalent (reply with just '.') if nothing "
    "changed meaningfully."
)

# --- scene-change gate constants ---------------------------------------------
# ~64x36 grayscale (16:9) is deliberately tiny: the gate runs on EVERY decoded
# frame (up to camera fps), so its cost must be trivial next to the JPEG
# encode + chat call it protects. Mean absolute pixel difference vs. the
# LAST-SENT frame's thumbnail, normalized to [0,1] (divide by 255), compared
# against config.PERCEPTION_SCENE_DIFF_THRESHOLD - the research doc's
# "mean-abs-diff ~8-12%" gate, server-side edition.
_GATE_SIZE = (64, 36)
# Analyzed-frame encoding: long edge capped at 1024px, JPEG q80 - big enough
# for a vision model to read a whiteboard, small enough (~100-200 KB) to stay
# far under InlineAttachmentProcessor's 10 MB decoded cap.
_JPEG_MAX_LONG_EDGE = 1024
_JPEG_QUALITY = 80

# --- cross-process concurrency slots -----------------------------------------
# livekit-agents runs each job in its OWN child process (JobProcess - that is
# what main.py's prewarm hook is per-process for), so a plain module-level
# counter would reset to zero in every room and never enforce anything. The
# cap is instead a directory of per-room slot files, each containing its
# owner's PID: acquire = prune slots whose PID is gone (a crashed job never
# runs its shutdown callback), count survivors, refuse at the cap. The
# check-then-create window between two simultaneously-starting rooms is not
# locked - worst case the cap overshoots by one room for its duration, which
# is acceptable for what is a cost/load ceiling, not a security boundary.
_SLOT_DIR = os.path.join(tempfile.gettempdir(), "voice-agent-perception-slots")
_SLOT_NAME_UNSAFE_RE = re.compile(r"[^A-Za-z0-9_.-]")


def acquire_perception_slot(room_name: str) -> str | None:
    """Claims one of the PERCEPTION_MAX_ROOMS concurrency slots for this
    room. Returns the slot file's path (pass to release_perception_slot on
    shutdown), or None if the worker is already at capacity - callers must
    then run the room audio-only.
    """
    try:
        os.makedirs(_SLOT_DIR, exist_ok=True)
        active = 0
        for name in os.listdir(_SLOT_DIR):
            path = os.path.join(_SLOT_DIR, name)
            try:
                with open(path, encoding="ascii") as f:
                    pid = int(f.read().strip())
                os.kill(pid, 0)  # signal 0 = existence check, sends nothing
                active += 1
            except (ValueError, OSError):
                # unreadable or owner process gone - stale, reclaim it
                try:
                    os.unlink(path)
                except OSError:
                    pass
        if active >= config.PERCEPTION_MAX_ROOMS:
            logger.warning(
                "perception slot refused for room %s: %d/%d rooms already active",
                room_name, active, config.PERCEPTION_MAX_ROOMS,
            )
            return None
        slot_path = os.path.join(
            _SLOT_DIR, _SLOT_NAME_UNSAFE_RE.sub("_", room_name) + ".slot"
        )
        with open(slot_path, "w", encoding="ascii") as f:
            f.write(str(os.getpid()))
        return slot_path
    except OSError:
        # Filesystem trouble must not take the room down - fail open to
        # audio+video for THIS room (the cap may briefly under-enforce, same
        # fail-open posture core's LlmGatewayRateLimiterService takes when
        # Redis is unreachable).
        logger.exception("perception slot bookkeeping failed for room %s - proceeding", room_name)
        return os.devnull  # truthy sentinel; release_perception_slot ignores it


def release_perception_slot(slot_path: str | None) -> None:
    if not slot_path or slot_path == os.devnull:
        return
    try:
        os.unlink(slot_path)
    except OSError:
        pass


# --- core vision-session lifecycle -------------------------------------------


async def start_vision_session(
    http: aiohttp.ClientSession,
    agent_id: str | None = None,
    auth_token: str | None = None,
    share_id: str | None = None,
) -> str | None:
    """`POST /api/vision/sessions` (shared vision contract) - opens the
    audit/quota session this room's frames are counted against. Returns the
    sessionId, or None on ANY failure (403 plan lacks live-vision, 429 quota
    exhausted, 503 platform flag off, network trouble) - the caller then runs
    the room audio-only; a failure here must never crash the call.

    Exactly one of (`agent_id` + `auth_token`) or `share_id` must be set -
    the authenticated pair calls the endpoint above; `share_id` calls its
    anonymous counterpart, `POST /api/share/agents/{shareId}/vision/sessions`
    (`AgentShareVisionController`, core/agents) instead - no `agentId` body
    field (implied by the URL), no `Authorization` header (permitAll,
    shareId is the only credential).
    """
    if share_id:
        url = f"{config.CORE_API_BASE_URL.rstrip('/')}/api/share/agents/{share_id}/vision/sessions"
        json_body = {"mode": "LIVE", "source": "CAMERA"}
        headers = {}
        log_subject = f"share:{share_id}"
    else:
        url = f"{config.CORE_API_BASE_URL.rstrip('/')}/api/vision/sessions"
        json_body = {"agentId": agent_id, "mode": "LIVE", "source": "CAMERA"}
        headers = {"Authorization": f"Bearer {auth_token}"}
        log_subject = f"agent {agent_id}"
    try:
        async with http.post(
            url,
            json=json_body,
            headers=headers,
            timeout=aiohttp.ClientTimeout(total=15, sock_connect=10),
        ) as resp:
            if resp.status != 200:
                text = await resp.text()
                logger.warning(
                    "vision session refused for %s (%d): %s - running audio-only",
                    log_subject, resp.status, text[:500],
                )
                return None
            body = await resp.json(content_type=None)
            session_id = body.get("sessionId") if isinstance(body, dict) else None
            if not session_id:
                logger.warning("vision session response for %s had no sessionId", log_subject)
                return None
            logger.info("vision session %s opened for %s", session_id, log_subject)
            return session_id
    except (aiohttp.ClientError, asyncio.TimeoutError):
        logger.exception("vision session start failed for %s - running audio-only", log_subject)
        return None


async def end_vision_session(
    http: aiohttp.ClientSession,
    vision_session_id: str,
    auth_token: str | None = None,
    share_id: str | None = None,
) -> None:
    """`POST /api/vision/sessions/{id}/end` (or its `share_id` counterpart,
    `POST /api/share/agents/{shareId}/vision/sessions/{id}/end`, no auth
    header) - best-effort; core also has the session's start on record, so a
    lost end call costs accuracy, not correctness.
    """
    if share_id:
        url = (
            f"{config.CORE_API_BASE_URL.rstrip('/')}/api/share/agents/"
            f"{share_id}/vision/sessions/{vision_session_id}/end"
        )
        headers = {}
    else:
        url = f"{config.CORE_API_BASE_URL.rstrip('/')}/api/vision/sessions/{vision_session_id}/end"
        headers = {"Authorization": f"Bearer {auth_token}"}
    try:
        async with http.post(
            url,
            json={},
            headers=headers,
            timeout=aiohttp.ClientTimeout(total=15, sock_connect=10),
        ) as resp:
            if resp.status != 200:
                logger.warning(
                    "vision session %s end returned %d", vision_session_id, resp.status
                )
    except (aiohttp.ClientError, asyncio.TimeoutError):
        logger.warning("vision session %s end call failed", vision_session_id, exc_info=True)


# --- frame plumbing -----------------------------------------------------------


async def first_video_track(
    room: rtc.Room, participant: rtc.RemoteParticipant
) -> rtc.RemoteVideoTrack:
    """The caller's first subscribed camera track - either already there (the
    browser published before this worker got this far) or awaited via the
    room's track_subscribed event. Never times out on its own; the awaiting
    task is cancelled at room shutdown, so a caller who simply never turns
    their camera on costs nothing but this pending future.
    """
    for pub in participant.track_publications.values():
        if pub.kind == rtc.TrackKind.KIND_VIDEO and pub.track is not None:
            return pub.track

    fut: asyncio.Future[rtc.RemoteVideoTrack] = asyncio.get_running_loop().create_future()

    def _on_track_subscribed(
        track: rtc.Track,
        publication: rtc.RemoteTrackPublication,
        p: rtc.RemoteParticipant,
    ) -> None:
        if (
            p.identity == participant.identity
            and track.kind == rtc.TrackKind.KIND_VIDEO
            and not fut.done()
        ):
            fut.set_result(track)

    room.on("track_subscribed", _on_track_subscribed)
    try:
        return await fut
    finally:
        room.off("track_subscribed", _on_track_subscribed)


def _frame_to_image(frame: rtc.VideoFrame) -> Image.Image:
    """Whatever pixel format the camera negotiated (usually I420) -> a PIL
    RGB image, via the SDK's own converter (rtc.VideoFrame.convert produces a
    tightly-packed RGB24 buffer - no stride handling needed here).
    """
    rgb = frame.convert(rtc.VideoBufferType.RGB24)
    return Image.frombuffer("RGB", (rgb.width, rgb.height), bytes(rgb.data), "raw", "RGB", 0, 1)


def _gate_thumbnail(image: Image.Image) -> np.ndarray:
    return np.asarray(image.convert("L").resize(_GATE_SIZE), dtype=np.float32)


def _scene_diff(previous: np.ndarray, current: np.ndarray) -> float:
    """Mean absolute grayscale difference, normalized to [0,1]."""
    return float(np.mean(np.abs(current - previous))) / 255.0


def _encode_jpeg(image: Image.Image) -> bytes:
    width, height = image.size
    long_edge = max(width, height)
    if long_edge > _JPEG_MAX_LONG_EDGE:
        scale = _JPEG_MAX_LONG_EDGE / long_edge
        image = image.resize((max(1, round(width * scale)), max(1, round(height * scale))))
    buf = io.BytesIO()
    image.save(buf, format="JPEG", quality=_JPEG_QUALITY)
    return buf.getvalue()


class PerceptionQuotaExhaustedError(Exception):
    """core returned 403 or 429 for a frame turn - the caller's daily message/
    token quota (FreeTierUsageLimiterService, 403) or vision-specific quota
    (429) is spent; the watcher stops analyzing but the audio call continues.
    """


class FrameWatcher:
    """The per-room frame loop: waits for the caller's camera track, then for
    every decoded frame runs (in order, cheapest first) the in-flight check,
    the hard minimum interval, and the scene-change gate - only a frame that
    passes all three is JPEG-encoded and sent to core chat. Analysis runs as
    a spawned task so the frame loop itself never blocks: frames arriving
    while a chat turn is in flight are simply dropped (the natural sampling
    behavior for live perception - always analyze the freshest view, never a
    backlog).
    """

    def __init__(
        self,
        *,
        room: rtc.Room,
        session,  # AgentSession - untyped to avoid importing livekit.agents here
        bridge: CoreChatBridgeLLM,
        http: aiohttp.ClientSession,
        vision_session_id: str,
        participant: rtc.RemoteParticipant,
        agent_id: str | None = None,
        auth_token: str | None = None,
        share_id: str | None = None,
        share_session_key: str | None = None,
    ) -> None:
        self._room = room
        self._session = session
        self._bridge = bridge
        self._http = http
        self._agent_id = agent_id
        self._auth_token = auth_token
        # Anonymous agent-share sight room (see main.py's _extract_share_context) -
        # mutually exclusive with agent_id/auth_token above; _send_frame branches
        # on self._share_id to pick the public vs authenticated chat endpoint.
        self._share_id = share_id
        self._share_session_key = share_session_key
        self._vision_session_id = vision_session_id
        self._participant = participant
        self._quota_exhausted = False
        self.frames_analyzed = 0

    async def run(self) -> None:
        track = await first_video_track(self._room, self._participant)
        logger.info(
            "perception watcher attached to video track %s (room=%s)",
            track.sid, self._room.name,
        )
        stream = rtc.VideoStream(track)
        last_sent_at = 0.0
        last_thumb: np.ndarray | None = None
        inflight: asyncio.Task | None = None
        try:
            async for event in stream:
                if self._quota_exhausted:
                    break
                if inflight is not None and not inflight.done():
                    continue
                inflight = None
                now = time.monotonic()
                if now - last_sent_at < config.PERCEPTION_MIN_FRAME_INTERVAL_S:
                    continue
                image = _frame_to_image(event.frame)
                thumb = _gate_thumbnail(image)
                # First frame always passes (the agent should see the opening
                # scene); afterwards the reference is the last-SENT frame's
                # thumbnail - rejected frames deliberately do NOT move it, so
                # a slow cumulative change still eventually trips the gate.
                if last_thumb is not None and _scene_diff(last_thumb, thumb) < config.PERCEPTION_SCENE_DIFF_THRESHOLD:
                    continue
                last_thumb = thumb
                last_sent_at = now
                inflight = asyncio.create_task(self._analyze(_encode_jpeg(image)))
        finally:
            if inflight is not None and not inflight.done():
                inflight.cancel()
            await stream.aclose()

    async def _analyze(self, jpeg_bytes: bytes) -> None:
        try:
            reply = await self._send_frame(jpeg_bytes)
            self.frames_analyzed += 1
            reply = (reply or "").strip()
            # "." is the instructed silent-equivalent - see
            # PERCEPTION_INSTRUCTION.
            if reply and reply != ".":
                await self._session.say(reply)
        except PerceptionQuotaExhaustedError:
            self._quota_exhausted = True
            logger.warning(
                "frame quota exhausted for room %s after %d frames - perception "
                "stops, audio call continues",
                self._room.name, self.frames_analyzed,
            )
            try:
                await self._session.say(
                    "I've reached today's limit for live viewing, so I'll stop "
                    "watching now - I can still hear you."
                )
            except Exception:
                logger.exception("quota-exhausted announcement failed (room=%s)", self._room.name)
        except asyncio.CancelledError:
            raise
        except Exception:
            # One bad frame turn (provider hiccup, transient network) must not
            # end perception - the min-interval already paces the retry.
            logger.exception("frame analysis failed (room=%s) - continuing", self._room.name)

    async def _send_frame(self, jpeg_bytes: bytes) -> str:
        """One frame turn through core's chat endpoint - same URL/auth/SSE
        contract as CoreChatBridgeLLM._run (which handles the AUDIO leg's
        turns for this same room), same 5-char `data:` prefix rule, same
        mid-stream error marker. The chat session id is SHARED with the audio
        bridge, deliberately: one fused conversation where what the agent
        heard and what it saw share context and memory.

        Share mode (`self._share_id` set) posts to the anonymous
        `AgentShareController#chatStream` instead, with `sessionKey` in
        place of `sessionId` and no `Authorization` header - same branch
        shape as `CoreChatBridgeLLM._run`, see that method's comment for the
        full reasoning (no forceFastDepth, no X-Session-Id to read back).
        """
        attachments = [
            {
                "fileName": "frame.jpg",
                "mimeType": "image/jpeg",
                "dataBase64": base64.b64encode(jpeg_bytes).decode("ascii"),
                "source": "camera",
            }
        ]
        if self._share_id:
            body: dict = {
                "message": PERCEPTION_INSTRUCTION,
                "visionSessionId": self._vision_session_id,
                "attachments": attachments,
                "sessionKey": self._share_session_key,
            }
            url = f"{config.CORE_API_BASE_URL.rstrip('/')}/api/share/agents/{self._share_id}/chat/stream"
            headers = {"Content-Type": "application/json"}
        else:
            body = {
                "message": PERCEPTION_INSTRUCTION,
                "visionSessionId": self._vision_session_id,
                "attachments": attachments,
            }
            if self._bridge.session_id:
                body["sessionId"] = self._bridge.session_id
            url = f"{config.CORE_API_BASE_URL.rstrip('/')}/api/agents/{self._agent_id}/chat"
            headers = {
                "Authorization": f"Bearer {self._auth_token}",
                "Content-Type": "application/json",
            }

        # Same 60s ceiling as CoreChatBridgeLLM._run's floor, for the same
        # reason: a vision turn through the real agent pipeline can
        # legitimately run tools.
        async with self._http.post(
            url,
            json=body,
            headers=headers,
            timeout=aiohttp.ClientTimeout(total=60, sock_connect=10),
        ) as resp:
            if resp.status in (403, 429):
                raise PerceptionQuotaExhaustedError(await resp.text())
            if resp.status >= 400:
                raise RuntimeError(
                    f"core chat endpoint returned {resp.status} for a frame turn: "
                    f"{(await resp.text())[:500]}"
                )

            if not self._share_id:
                new_session_id = resp.headers.get("X-Session-Id")
                if new_session_id and not self._bridge.session_id:
                    self._bridge.session_id = new_session_id

            parts: list[str] = []
            buffer = ""
            async for raw_chunk in resp.content.iter_any():
                buffer += raw_chunk.decode("utf-8", errors="ignore")
                lines = buffer.split("\n")
                buffer = lines.pop()
                for line in lines:
                    self._collect_sse_line(line.rstrip("\r"), parts)
            if buffer:
                self._collect_sse_line(buffer.rstrip("\r"), parts)
            return "".join(parts)

    @staticmethod
    def _collect_sse_line(line: str, parts: list[str]) -> None:
        if not line.startswith("data:"):
            return
        # EXACTLY a 5-char prefix - see llm_core_bridge's module docstring
        # for the space-eating bug this guards against.
        content = line[5:]
        if content.startswith(STREAM_ERROR_MARKER):
            raise RuntimeError(
                "core reported a mid-stream failure for a frame turn: "
                + (content[len(STREAM_ERROR_MARKER):] or "(no detail)")
            )
        if content:
            parts.append(content)
