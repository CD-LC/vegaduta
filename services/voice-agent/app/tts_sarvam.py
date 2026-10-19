"""
Custom Sarvam TTS plugin for LiveKit Agents.

No official LiveKit plugin exists for Sarvam (same situation as STT - see
stt_sidecar_whisper.py's own header). Built as the fallback/default TTS
provider after ElevenLabs' free-tier API restriction blocked library-voice
synthesis entirely during live testing ("Free users cannot use library
voices via the API" - a real, observed 402, not a guess) - see
core/agents/.../SpeakToolCallback.java's own "ElevenLabs first... Sarvam
otherwise" fallback order for the same reasoning applied here.

Wraps core/sarvam/.../SarvamTextToSpeechClient.java's exact wire contract:
POST https://api.sarvam.ai/text-to-speech with header
`api-subscription-key`, body {text, target_language_code, model, speaker} -
response is {"audios": ["<base64 WAV>"]} (one entry per input text, Sarvam
never streams). Like SidecarWhisperSTT, this is a real request/response
call wrapped to satisfy LiveKit's streaming TTS interface - the SDK's own
`tts.TTS` base class (non-streaming capability) plus `SynthesizeStream`
handle the "not actually a stream" case natively, no StreamAdapter needed
here (that's an STT-side concept for turning a batch STT into VAD-segmented
"streaming").
"""
from __future__ import annotations

import base64
import logging
import re

import aiohttp
from livekit.agents import DEFAULT_API_CONNECT_OPTIONS, APIConnectionError, APIStatusError, tts

from . import config

logger = logging.getLogger("voice-agent.tts_sarvam")

_SARVAM_URL = "https://api.sarvam.ai/text-to-speech"
_SAMPLE_RATE = 22050  # Sarvam's bulbul models synthesize 22.05kHz mono WAV - confirmed against a real API response, not assumed.
_NUM_CHANNELS = 1

# Confirmed live 2026-08-04: Sarvam rejects a real agent reply with 400
# "Text must contain at least one character from the allowed languages" -
# the reply came from core's /api/agents/{agentId}/chat, which returns raw
# markdown for ON-SCREEN display (bold, bullets, code, links), not the
# voice-safe text the EXISTING push-to-talk pipeline already produces via
# core/speech's SpeechTextNormalizer.normalizeForSpeech() before every TTS
# call (see VoiceCallController#turn). That class is ~800 lines of
# Unicode/Indic-safe, ReDoS-hardened rules with its own test suite - not
# something to re-derive here. This is a deliberately narrow SUBSET (strip
# the markdown syntax most likely to trip Sarvam's language-content check;
# nowhere near full parity - tables/HTML/entities/emoji are NOT handled)
# good enough to unblock real replies tonight. Porting the real normalizer
# (or exposing it from core over HTTP so every TTS caller shares one
# implementation) is the correct follow-up, not this function.
_MD_EMPHASIS = re.compile(r"[*_`~]{1,3}")
_MD_LINK = re.compile(r"\[([^\]]*)\]\([^)]*\)")
_MD_BULLET = re.compile(r"(?m)^[ \t]*[-*+][ \t]+")
_MD_HEADING = re.compile(r"(?m)^#{1,6}[ \t]+")
_WHITESPACE = re.compile(r"\s+")


def _normalize_for_speech(raw: str) -> str:
    if not raw:
        return raw
    text = _MD_LINK.sub(r"\1", raw)
    text = _MD_HEADING.sub("", text)
    text = _MD_BULLET.sub("", text)
    text = _MD_EMPHASIS.sub(" ", text)
    text = _WHITESPACE.sub(" ", text).strip()
    # Never return empty for non-empty input - an empty TTS request fails
    # outright, same "never empty" contract SpeechTextNormalizer documents.
    return text or raw.strip()


class SarvamTTS(tts.TTS):
    def __init__(
        self,
        *,
        api_key: str | None = None,
        target_language_code: str = "en-IN",
        model: str = "bulbul:v3",  # v2 is deprecated server-side - see config.py
        speaker: str | None = None,
    ) -> None:
        # streaming=False: Sarvam has no incremental/streaming synthesis API -
        # every call is one full text-in, one full WAV-out round trip. The
        # base class still lets this participate in the SDK's normal
        # tts_node pipeline; it just won't emit partial audio mid-sentence
        # the way the ElevenLabs plugin does.
        super().__init__(
            capabilities=tts.TTSCapabilities(streaming=False),
            sample_rate=_SAMPLE_RATE,
            num_channels=_NUM_CHANNELS,
        )
        self._api_key = api_key or config.SARVAM_API_KEY
        self._target_language_code = target_language_code
        self._model = model
        self._speaker = speaker
        self._session: aiohttp.ClientSession | None = None

    def _ensure_session(self) -> aiohttp.ClientSession:
        if self._session is None:
            self._session = aiohttp.ClientSession()
        return self._session

    def synthesize(
        self, text: str, *, conn_options=DEFAULT_API_CONNECT_OPTIONS
    ) -> "ChunkedStream":
        return ChunkedStream(tts=self, input_text=text, conn_options=conn_options)


class ChunkedStream(tts.ChunkedStream):
    """One-shot synthesis - matches Sarvam's actual request/response shape
    rather than pretending to stream. Decodes the returned WAV and pushes it
    as a single frame; the SDK's own AudioEmitter chunks it for playback."""

    def __init__(self, *, tts: SarvamTTS, input_text: str, conn_options) -> None:
        super().__init__(tts=tts, input_text=input_text, conn_options=conn_options)
        self._tts: SarvamTTS = tts

    async def _run(self, output_emitter: tts.AudioEmitter) -> None:
        if not self._tts._api_key:
            raise APIConnectionError("SARVAM_API_KEY is not configured - cannot synthesize speech")

        speech_text = _normalize_for_speech(self.input_text)
        logger.debug("Sarvam TTS request text=%r (raw=%r)", speech_text, self.input_text)

        body: dict = {
            "text": speech_text,
            "target_language_code": self._tts._target_language_code,
            "model": self._tts._model,
        }
        if self._tts._speaker:
            body["speaker"] = self._tts._speaker

        session = self._tts._ensure_session()
        try:
            async with session.post(
                _SARVAM_URL,
                json=body,
                headers={"api-subscription-key": self._tts._api_key},
                timeout=aiohttp.ClientTimeout(total=self._conn_options.timeout, sock_connect=10),
            ) as resp:
                payload = await resp.json(content_type=None)
                if resp.status != 200:
                    detail = payload.get("error", payload) if isinstance(payload, dict) else payload
                    raise APIStatusError(
                        f"Sarvam /text-to-speech returned {resp.status}: {detail}",
                        status_code=resp.status,
                        request_id=payload.get("request_id") if isinstance(payload, dict) else None,
                        body=payload,
                    )
        except aiohttp.ClientError as e:
            raise APIConnectionError(f"failed to reach Sarvam TTS: {e}") from e

        audios = payload.get("audios") if isinstance(payload, dict) else None
        if not audios:
            raise APIStatusError(
                f"Sarvam /text-to-speech returned no audio for text: {self.input_text!r}",
                status_code=resp.status,
                request_id=payload.get("request_id") if isinstance(payload, dict) else None,
                body=payload,
            )

        wav_bytes = base64.b64decode(audios[0])

        output_emitter.initialize(
            request_id=payload.get("request_id", ""),
            sample_rate=_SAMPLE_RATE,
            num_channels=_NUM_CHANNELS,
            mime_type="audio/wav",
        )
        output_emitter.push(wav_bytes)
        output_emitter.flush()
