"""
Edge-sight bridge - the S-edge tier of the live-vision program
(docs/LIVE-VISION-SENSE-RESEARCH-2026-08-05.md section 3, "S-edge" row),
live-call edition.

In this mode the browser analyzes camera frames ON-DEVICE (the SmolVLM-class
local VLM behind web/app/lib/edge/vision.ts) and publishes only the resulting
TEXT over a LiveKit data message on topic "edge-sight" - the raw frames never
leave the caller's device, and no video track is published to the room at
all. This bridge turns each accepted report into a plain TEXT turn through
core's existing `POST /api/agents/{agentId}/chat` (no attachments, no
visionSessionId - there is deliberately no hosted vision model anywhere in
this loop, which is also why it keeps working when the hosted vision provider
is down) and speaks substantive replies through the session's existing TTS
leg. Same "." silent-sentinel contract as perception.py's frame turns.

Cost/entitlement posture, per the research doc's ladder: on-device analysis
is the FREE tier ("FREE = edge-only, 0 server frames"), so no vision session
is opened and VisionUsageService's frame quota is never touched. The text
turn still goes through the same general chat endpoint as every other turn,
so FreeTierUsageLimiterService's message/token caps apply normally - a
403/429 disables this bridge for the rest of the call with one spoken
notice (mirroring perception.py's 2026-08-07 quota-loop fix, which is why
BOTH statuses are handled, not just 429).

Trust rules inherited from the shared contract: packets are only accepted
from the caller this job authenticated (identity-checked), size-clamped, and
paced by the same ~2s floor the server-side frame watcher uses - a
misbehaving page can't turn this into a token pump.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time

import aiohttp
from livekit import rtc

from . import config
from .llm_core_bridge import STREAM_ERROR_MARKER

logger = logging.getLogger("voice-agent.edge_sight")

# The browser publishes on this topic (CallPanel.tsx's edge-sight loop);
# nothing else in the platform uses it. The reverse direction ("agent-stage")
# is llm_core_bridge's - the two topics are deliberately distinct so neither
# side ever has to parse-and-ignore the other's traffic.
EDGE_SIGHT_TOPIC = "edge-sight"

# One on-device report is a sentence or two of scene description - 2000 chars
# is far above any honest payload and low enough that a hostile page can't
# smuggle a giant prompt through this side door.
_MAX_TEXT_CHARS = 2000

# Same cadence floor as the server-side watcher's hard interval
# (config.PERCEPTION_MIN_FRAME_INTERVAL_S) - the client applies its own gate
# too, but this bridge must not trust the client's pacing.
_MIN_INTERVAL_S = config.PERCEPTION_MIN_FRAME_INTERVAL_S

# The standing instruction wrapped around each on-device report. Mirrors
# PERCEPTION_INSTRUCTION's delta-report + "." sentinel design, adapted for
# text input: the agent brain reacts to the DESCRIPTION, it never sees pixels.
EDGE_SIGHT_INSTRUCTION = (
    "The caller's own device analyzed their live camera view locally and "
    "reports: {report}\n"
    "Briefly react only if this is new or worth speaking about; reply with "
    "just '.' if nothing needs saying."
)


class EdgeSightBridge:
    """One per call job (voice OR sight rooms - registering the handler costs
    nothing until a packet actually arrives). Construction never raises and
    performs no I/O; the aiohttp session is created lazily on first use and
    closed by aclose() (wired as a job shutdown callback in main.py)."""

    def __init__(
        self,
        *,
        room: rtc.Room,
        session,
        bridge,
        participant_identity: str,
        agent_id: str | None = None,
        auth_token: str | None = None,
        share_id: str | None = None,
        share_session_key: str | None = None,
    ) -> None:
        self._room = room
        self._session = session
        # CoreChatBridgeLLM - shared so the edge-sight turns join the SAME
        # chat session as the audio leg (one fused conversation, exactly like
        # perception.py's frame turns).
        self._bridge = bridge
        self._agent_id = agent_id
        self._auth_token = auth_token
        # Anonymous agent-share room (see main.py's _extract_share_context) -
        # mutually exclusive with agent_id/auth_token; _send_report branches
        # on self._share_id the same way perception.FrameWatcher does.
        self._share_id = share_id
        self._share_session_key = share_session_key
        self._participant_identity = participant_identity
        self._http: aiohttp.ClientSession | None = None
        self._inflight: asyncio.Task | None = None
        self._last_accepted_at = 0.0
        self._disabled = False
        self.reports_handled = 0

    # -- packet intake (sync, called from the room event dispatch) ------------

    def handle_packet(self, packet: rtc.DataPacket) -> None:
        """Validates and paces one incoming data packet; schedules the async
        turn for accepted ones. Never raises - a malformed packet is dropped
        with a log line, same never-crash-the-call posture as perception's
        per-frame error handling."""
        try:
            if self._disabled or packet.topic != EDGE_SIGHT_TOPIC:
                return
            if not self._auth_token and not self._share_id:
                # Same failure mode as an unauthenticated audio turn - the
                # call already announced it can't reach the agent brain.
                return
            sender = getattr(packet, "participant", None)
            if sender is not None and sender.identity != self._participant_identity:
                logger.warning(
                    "edge-sight packet from unexpected participant %s (room=%s) - dropped",
                    sender.identity, self._room.name,
                )
                return
            if self._inflight is not None and not self._inflight.done():
                return
            now = time.monotonic()
            if now - self._last_accepted_at < _MIN_INTERVAL_S:
                return
            payload = json.loads(bytes(packet.data).decode("utf-8", errors="ignore"))
            text = (payload.get("text") or "").strip() if isinstance(payload, dict) else ""
            if not text:
                return
            self._last_accepted_at = now
            self._inflight = asyncio.create_task(self._analyze(text[:_MAX_TEXT_CHARS]))
        except Exception:
            logger.exception("edge-sight packet handling failed (room=%s) - dropped", self._room.name)

    # -- the turn itself -------------------------------------------------------

    async def _analyze(self, report: str) -> None:
        try:
            reply = (await self._send_report(report) or "").strip()
            self.reports_handled += 1
            # "." is the instructed silent-equivalent - see EDGE_SIGHT_INSTRUCTION.
            if reply and reply != ".":
                await self._session.say(reply)
        except asyncio.CancelledError:
            raise
        except Exception:
            # One bad turn must not end the bridge - the interval floor
            # already paces the retry, same as perception._analyze.
            logger.exception("edge-sight turn failed (room=%s) - continuing", self._room.name)

    async def _send_report(self, report: str) -> str:
        if self._http is None:
            self._http = aiohttp.ClientSession()
        message = EDGE_SIGHT_INSTRUCTION.format(report=report)
        # Share mode: same branch shape as CoreChatBridgeLLM._run /
        # perception.FrameWatcher._send_frame - AgentShareController#chatStream,
        # sessionKey instead of sessionId, no Authorization header.
        if self._share_id:
            body: dict = {"message": message, "sessionKey": self._share_session_key}
            url = f"{config.CORE_API_BASE_URL.rstrip('/')}/api/share/agents/{self._share_id}/chat/stream"
            headers = {"Content-Type": "application/json"}
        else:
            body = {"message": message}
            if self._bridge.session_id:
                body["sessionId"] = self._bridge.session_id
            url = f"{config.CORE_API_BASE_URL.rstrip('/')}/api/agents/{self._agent_id}/chat"
            headers = {
                "Authorization": f"Bearer {self._auth_token}",
                "Content-Type": "application/json",
            }

        async with self._http.post(
            url,
            json=body,
            headers=headers,
            # Same 60s ceiling as perception._send_frame, same reason: the
            # real agent pipeline may legitimately run tools on a turn.
            timeout=aiohttp.ClientTimeout(total=60, sock_connect=10),
        ) as resp:
            if resp.status in (403, 429):
                # Message/token cap hit (this is a plain text turn - there is
                # no frame quota in this path). Disable for the rest of the
                # call with ONE spoken notice - the same loop-prevention
                # shape as perception's PerceptionQuotaExhaustedError.
                self._disabled = True
                logger.warning(
                    "edge-sight disabled for room %s after %d reports - core returned %d",
                    self._room.name, self.reports_handled, resp.status,
                )
                try:
                    await self._session.say(
                        "I've hit today's usage limit, so I'll stop reacting to "
                        "your camera for now - I can still hear you."
                    )
                except Exception:
                    logger.exception("edge-sight quota announcement failed (room=%s)", self._room.name)
                return ""
            if resp.status >= 400:
                raise RuntimeError(
                    f"core chat endpoint returned {resp.status} for an edge-sight turn: "
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
                "core reported a mid-stream failure for an edge-sight turn: "
                + (content[len(STREAM_ERROR_MARKER):] or "(no detail)")
            )
        if content:
            parts.append(content)

    async def aclose(self) -> None:
        if self._inflight is not None and not self._inflight.done():
            self._inflight.cancel()
        if self._http is not None:
            await self._http.close()
