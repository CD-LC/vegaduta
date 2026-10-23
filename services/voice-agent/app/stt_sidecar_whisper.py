"""
Custom LiveKit Agents STT plugin wrapping `sidecar`'s existing
`POST /transcribe` (self-hosted faster-whisper - see
sidecar/app/transcription.py). No official LiveKit STT plugin talks to a
plain self-hosted Whisper HTTP endpoint like this one, so this is a small,
from-scratch implementation of the SDK's real `stt.STT` interface (verified
against the installed `livekit-agents` 1.6.8 package's
`livekit/agents/stt/stt.py` - the only abstract method is `_recognize_impl`,
confirmed via `STT.__abstractmethods__`).

Non-streaming by design (`STTCapabilities(streaming=False)`): sidecar's
/transcribe is a single request/response call, not a streaming API. This is
fine and is the SDK's own documented pattern - when an `Agent`/`AgentSession`
has a VAD configured (Silero, wired in main.py) and the STT doesn't support
native streaming, the framework automatically wraps this class in
`stt.StreamAdapter(stt=this, vad=vad)`, which buffers audio between the
VAD's start-of-speech/end-of-speech events and calls `recognize()` (which
calls `_recognize_impl` below) exactly once per utterance. That VAD-driven
utterance boundary - not a manual button release - is the actual latency win
over today's push-to-talk `VoiceCallController#turn`.
"""

from __future__ import annotations

import base64
import logging
import os

import aiohttp
from livekit import rtc
from livekit.agents import APIConnectionError, APIStatusError, stt
from livekit.agents.types import NOT_GIVEN, APIConnectOptions, NotGivenOr
from livekit.agents.utils import AudioBuffer

from . import config
from .llm_core_bridge import _publish_stage

logger = logging.getLogger("voice-agent.stt_sidecar_whisper")

# sidecar/app/transcription.py's _SUPPORTED_EXTENSIONS includes .wav, and
# rtc.AudioFrame.to_wav_bytes() (a real method on the installed SDK's
# AudioFrame, confirmed by reading livekit/rtc/audio_frame.py directly)
# gives us a correctly-framed mono 16-bit PCM WAV with zero manual header
# construction.
_UPLOAD_FILENAME = "utterance.wav"

# Sidecar internal auth (2026-08-07): sidecar/app/main.py now requires
# X-Internal-Token on every endpoint except /health. Same env var name the
# `core` and `sidecar` services read (see deploy/compose/docker-compose.yml), so one
# deploy/compose/.env value covers all three - mirroring how SIDECAR_BASE_URL is
# shared. Read here rather than config.py because this file is the ONLY
# sidecar caller in this worker; empty means "send no header", which only
# works against a sidecar running with SIDECAR_AUTH_REQUIRED=false.
_SIDECAR_SHARED_SECRET = os.environ.get("SIDECAR_SHARED_SECRET", "").strip()


class SidecarWhisperSTT(stt.STT):
    """Wraps sidecar's POST /transcribe as a non-streaming LiveKit STT plugin."""

    def __init__(
        self,
        *,
        base_url: str | None = None,
        http_session: aiohttp.ClientSession | None = None,
        room: rtc.Room | None = None,
    ) -> None:
        super().__init__(capabilities=stt.STTCapabilities(streaming=False, interim_results=False))
        self._base_url = (base_url or config.SIDECAR_BASE_URL).rstrip("/")
        self._session = http_session
        self._owns_session = http_session is None
        # Reliability blueprint Phase 4 Part 5b (Call AI 7-stage lifecycle) -
        # optional, defaults to None like CoreChatBridgeLLM's matching kwarg
        # (see llm_core_bridge.py). Wired from main.py's construction call.
        self.room = room

    @property
    def model(self) -> str:
        # Matches sidecar/app/transcription.py's MODEL_SIZE constant.
        return "faster-whisper-base"

    @property
    def provider(self) -> str:
        return "sidecar"

    def _ensure_session(self) -> aiohttp.ClientSession:
        if self._session is None:
            self._session = aiohttp.ClientSession()
        return self._session

    async def _recognize_impl(
        self,
        buffer: AudioBuffer,
        *,
        language: NotGivenOr[str] = NOT_GIVEN,
        conn_options: APIConnectOptions,
    ) -> stt.SpeechEvent:
        # Reliability blueprint Phase 4 Part 5b step 4: fires as soon as the
        # VAD-bounded utterance is handed to sidecar for transcription - the
        # earliest point this plugin can honestly say the call has moved past
        # "still listening". No explicit "transcribing-end" signal is needed;
        # the next stage message ("understanding", from CoreChatBridgeLLM)
        # naturally supersedes it on the frontend's show-latest-stage state
        # machine. No turn id exists yet at this layer (STT has no request-id
        # concept of its own) - omitted, not a bug.
        await _publish_stage(self.room, "transcribing")
        frame = rtc.combine_audio_frames(buffer) if isinstance(buffer, list) else buffer
        wav_bytes = frame.to_wav_bytes()

        # Same base64-over-JSON contract sidecar/app/main.py's
        # TranscribeRequest already defines (byte-identical to how
        # KnowledgeIngestionJobRunner and ExtractRequest talk to sidecar) -
        # not multipart, deliberately matching this sidecar's one existing
        # documented interop quirk rather than introducing a second wire
        # format for this one caller.
        payload = {
            "filename": _UPLOAD_FILENAME,
            "content_base64": base64.b64encode(wav_bytes).decode("ascii"),
            # sidecar/app/transcription.py's live-call worker-pool reservation
            # (2026-08-06 latency audit fix) only ever applies when a caller
            # says so - this IS that caller, a real person waiting mid-call,
            # as opposed to KnowledgeIngestionJobRunner's background jobs.
            "is_live_call": True,
        }

        session = self._ensure_session()
        url = f"{self._base_url}/transcribe"
        # The SDK's own default (APIConnectOptions.timeout=10.0) is tuned for
        # low-latency streaming STT APIs, not a CPU-bound faster-whisper-base
        # call - observed live: ~2.4s warm for a 2s clip, but a cold first
        # call (model not yet loaded into memory) or a longer/noisier real
        # utterance can exceed 10s, causing the exact "Error in _stt_pump...
        # TimeoutError" seen in testing even though sidecar's own logs showed
        # every one of those requests eventually returned 200. Floor at 30s
        # rather than trusting whatever the caller passed, since a caller-
        # supplied 10s would silently reintroduce this failure.
        effective_timeout = max(conn_options.timeout, 30.0)
        try:
            async with session.post(
                url,
                json=payload,
                headers=(
                    {"X-Internal-Token": _SIDECAR_SHARED_SECRET} if _SIDECAR_SHARED_SECRET else None
                ),
                timeout=aiohttp.ClientTimeout(total=effective_timeout, sock_connect=10),
            ) as resp:
                body = await resp.json(content_type=None)
                if resp.status != 200:
                    detail = body.get("detail") if isinstance(body, dict) else body
                    raise APIStatusError(
                        f"sidecar /transcribe returned {resp.status}: {detail}",
                        status_code=resp.status,
                        request_id=None,
                        body=body,
                    )
        except aiohttp.ClientError as e:
            raise APIConnectionError(f"failed to reach sidecar at {url}: {e}") from e

        text = (body.get("text") or "").strip() if isinstance(body, dict) else ""
        if not text:
            logger.debug("sidecar transcribed empty text for a %d-byte utterance", len(wav_bytes))
        resolved_language = language if isinstance(language, str) else "en"
        return stt.SpeechEvent(
            type=stt.SpeechEventType.FINAL_TRANSCRIPT,
            alternatives=[stt.SpeechData(language=resolved_language, text=text)],
        )

    async def aclose(self) -> None:
        if self._owns_session and self._session is not None:
            await self._session.close()
            self._session = None
