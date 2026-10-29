"""
Local Piper TTS plugin for LiveKit Agents - the platform's first fully
offline TTS provider.

Both existing providers are hosted and key-gated: ElevenLabs (preferred,
needs ELEVENLABS_API_KEY) and Sarvam (fallback, needs SARVAM_API_KEY). A
deployment with no paid TTS key had no working voice path at all - an audio
call would silently fall back to browser-side speech. Piper runs a
small ONNX voice model in a subprocess on this container's CPU,
needs no API key and no egress, and is fast enough for live calls
(~50-150ms per sentence of audio on a 2-core container).

Shape: one `piper` subprocess per synthesize() call feeding text on stdin
and emitting raw s16le PCM on stdout - the same "one-shot, not actually
streaming" contract as tts_sarvam.py (Piper has no incremental API either),
wrapped in WAV before push so the emitter's audio/wav handling is identical
across providers.

Voice model: PIPER_VOICE names a voice shipped under PIPER_MODEL_DIR (the
image bakes en_US-lessac-medium so a default deployment works offline); an
absolute/relative .onnx path overrides the name form entirely.
"""

from __future__ import annotations

import asyncio
import io
import logging
import os
import wave

from livekit.agents import APIConnectionError, tts
from livekit.agents.types import DEFAULT_API_CONNECT_OPTIONS

from . import config
from .tts_sarvam import _normalize_for_speech

logger = logging.getLogger("voice-agent.tts_piper")


def _model_path(voice: str) -> str:
    if voice.endswith(".onnx") or os.sep in voice:
        return voice if os.path.isabs(voice) else os.path.join(config.PIPER_MODEL_DIR, voice)
    return os.path.join(config.PIPER_MODEL_DIR, f"{voice}.onnx")


class PiperTTS(tts.TTS):
    def __init__(
        self,
        *,
        voice: str | None = None,
        speaker_id: int | None = None,
    ) -> None:
        super().__init__(
            capabilities=tts.TTSCapabilities(streaming=False),
            sample_rate=config.PIPER_SAMPLE_RATE,
            num_channels=1,
        )
        self._model = _model_path(voice or config.PIPER_VOICE)
        self._speaker_id = speaker_id if speaker_id is not None else config.PIPER_SPEAKER_ID

    def synthesize(
        self, text: str, *, conn_options=DEFAULT_API_CONNECT_OPTIONS
    ) -> "PiperChunkedStream":
        return PiperChunkedStream(tts=self, input_text=text, conn_options=conn_options)


class PiperChunkedStream(tts.ChunkedStream):
    """One-shot synthesis via a `piper --output_raw -` subprocess; the raw
    s16le mono PCM it prints is wrapped in a minimal WAV header before the
    push so output_emitter's audio/wav path matches SarvamTTS's exactly."""

    def __init__(self, *, tts: PiperTTS, input_text: str, conn_options) -> None:
        super().__init__(tts=tts, input_text=input_text, conn_options=conn_options)
        self._tts: PiperTTS = tts

    async def _run(self, output_emitter: tts.AudioEmitter) -> None:
        speech_text = _normalize_for_speech(self.input_text)
        if not os.path.isfile(self._tts._model):
            raise APIConnectionError(
                f"piper voice model not found at {self._tts._model} - bake it into the "
                "image (Dockerfile's download_voices step) or point PIPER_VOICE at an "
                "existing .onnx under PIPER_MODEL_DIR"
            )

        argv = [config.PIPER_BIN, "--model", self._tts._model, "--output_raw", "-"]
        if self._tts._speaker_id >= 0:
            argv += ["--speaker", str(self._tts._speaker_id)]
        try:
            proc = await asyncio.create_subprocess_exec(
                *argv,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
            )
            pcm, _ = await asyncio.wait_for(
                proc.communicate(speech_text.encode("utf-8")), timeout=self._conn_options.timeout
            )
        except FileNotFoundError as e:
            raise APIConnectionError(
                f"piper binary not found ({config.PIPER_BIN}) - is the piper release tarball baked into this image?"
            ) from e
        except asyncio.TimeoutError as e:
            raise APIConnectionError("piper synthesis timed out") from e
        if proc.returncode != 0 or not pcm:
            raise APIConnectionError(f"piper exited {proc.returncode} with no audio")

        buf = io.BytesIO()
        with wave.open(buf, "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(config.PIPER_SAMPLE_RATE)
            wav.writeframes(pcm)

        output_emitter.initialize(
            request_id="",
            sample_rate=config.PIPER_SAMPLE_RATE,
            num_channels=1,
            mime_type="audio/wav",
        )
        output_emitter.push(buf.getvalue())
        output_emitter.flush()
