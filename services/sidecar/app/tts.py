"""
Sarvam text-to-speech for the video pipeline (Video Pipeline Stage 2).

Lifted from voice-agent/app/tts_sarvam.py rather than re-derived: that file
already wraps core/sarvam/.../SarvamTextToSpeechClient.java's exact wire
contract (POST https://api.sarvam.ai/text-to-speech, header
`api-subscription-key`, body {text, target_language_code, model, speaker},
response {"audios": ["<base64 WAV>"]} - Sarvam never streams). Three
deliberate differences from that file, each because this caller is a
render pipeline and not a live phone call:

1. `requests` (already a sidecar dependency for the Google Vision path),
   not aiohttp+LiveKit. Every sidecar endpoint is a sync `def` run in
   Starlette's thread pool - see main.py. Importing livekit.agents here
   would drag the whole LiveKit SDK into this image for nothing.
2. Segment-level synthesis with real measured durations. The voice-agent
   pushes one WAV at a speaker and never needs to know how long it was;
   Stage 3's `edit_list` has to align a B-roll clip to each beat, so the
   duration of each beat's audio is the actual product of this module.
   Measured from the returned WAV's own frame count, never from the
   `duration_sec` estimate in episode.json - the estimate is an LLM's
   guess and the render must not inherit it.
3. Chunking at sentence boundaries. Sarvam rejects text past a
   per-request ceiling, and a 20-second narration beat is comfortably
   past it. voice-agent never hit this because a conversational turn is
   short. Chunks are synthesized separately and concatenated, which is
   safe only because every chunk comes back at the same sample rate and
   channel count from the same model (asserted, not assumed - see
   concat_wav).

The markdown-stripping normalizer is deliberately NOT copied. Its reason
for existing in voice-agent is that the text arrives as raw markdown from
core's /chat endpoint; narration here comes from episode.json's
`narration` field, which is written as speech. Pasting a partial
reimplementation of core/speech's SpeechTextNormalizer (~800 lines, its
own test suite) into a third location is how the two existing copies
drift apart. If narration ever does arrive with markdown in it, the fix
is to expose that class over HTTP from core, which its own comment in
voice-agent/app/tts_sarvam.py already names as the correct follow-up.
"""
from __future__ import annotations

import base64
import binascii
import io
import logging
import os
import re
import threading
import wave

import requests

log = logging.getLogger("sidecar.tts")

_SARVAM_URL = "https://api.sarvam.ai/text-to-speech"

# Same four var names voice-agent/app/config.py and deploy/docker-compose.yml's
# `voice-agent` service already use - one value in deploy/.env covers both
# callers, which is the same reasoning that file applies to SIDECAR_BASE_URL.
_API_KEY = os.environ.get("SARVAM_API_KEY", "")
DEFAULT_LANGUAGE = os.environ.get("SARVAM_TARGET_LANGUAGE_CODE", "en-IN")
# bulbul:v3, NOT v2. Sarvam DEPRECATED v2 server-side and now rejects it
# outright: POST /text-to-speech returns 400 "Model 'bulbul:v2' has been
# deprecated. Please use 'bulbul:v3' instead." Confirmed live 2026-09-17,
# not inferred. core/sarvam's SarvamTextToSpeechClient.DEFAULT_MODEL,
# core/providers' TtsModelProvider and V196__agent_tts_provider.sql had all
# moved to v3 already - the Python services and deploy/docker-compose.yml
# were missed, which left every Sarvam TTS call from them failing.
# NOTE: speaker names are per-model (anushka is v2, shubh is v3), so an
# explicit SARVAM_SPEAKER carried over from v2 will also 400. Leaving it
# unset lets Sarvam apply its own per-model default.
DEFAULT_MODEL = os.environ.get("SARVAM_MODEL", "bulbul:v3")
DEFAULT_SPEAKER = os.environ.get("SARVAM_SPEAKER", "")

# Sarvam's /text-to-speech rejects text past a per-request character ceiling.
# 1400 leaves headroom under the documented 1500 for bulbul without
# needing to track which model has which limit - a chunk boundary costs
# nothing (the pieces are concatenated losslessly), an over-limit request
# costs the whole beat.
MAX_CHARS = int(os.environ.get("SARVAM_MAX_CHARS_PER_REQUEST", "1400"))

# One Sarvam request at a time. This is network-bound, not CPU-bound, so the
# cap is about not stampeding a rate-limited third-party API from a service
# that also serves live RAG traffic - not about protecting this container's
# CPU the way embeddings.py's semaphore is. A 12-beat episode is 12 requests;
# serialized at ~2s each that is well inside any workflow step timeout.
_MAX_CONCURRENT = int(os.environ.get("SARVAM_MAX_CONCURRENT", "1"))
_semaphore = threading.Semaphore(_MAX_CONCURRENT)

_TIMEOUT_S = float(os.environ.get("SARVAM_TIMEOUT_S", "60"))

# Sentence-ish split that keeps the terminator attached to the sentence it
# ends, and treats Devanagari danda/double-danda as terminators too - Sarvam's
# whole reason for being in this stack is the Indic languages, so splitting
# only on "." would leave Hindi/Marathi narration as one unsplittable blob.
_SENTENCE_END = re.compile(r"(?<=[.!?।॥])\s+")


def _require_api_key() -> str:
    if not _API_KEY:
        # ValueError, not RuntimeError: main.py maps ValueError from a worker
        # module to a 400 and lets everything else 500. A missing platform
        # credential is neither, strictly - but a 400 naming the missing var
        # is actionable by the operator reading the workflow run, and a raw
        # 500 is not. See the endpoint's own docstring.
        raise ValueError(
            "SARVAM_API_KEY is not configured on the sidecar. Locally: set it in "
            "deploy/.env and recreate the sidecar container. On Azure: add the secret "
            "to Key Vault, reference it from the sidecar Container App, and roll a new "
            "revision - a container restart alone will not pick it up."
        )
    return _API_KEY


def split_for_synthesis(text: str, max_chars: int = MAX_CHARS) -> list[str]:
    """Split text into synthesizable pieces, preferring sentence boundaries.

    Falls back to a hard word-boundary split for a single sentence longer
    than the ceiling (a run-on narration line), and to a hard character
    split for a single word longer than it (a URL pasted into narration -
    degenerate, but must not loop forever or raise).
    """
    text = text.strip()
    if not text:
        return []
    if len(text) <= max_chars:
        return [text]

    pieces: list[str] = []
    buffer = ""
    for sentence in _SENTENCE_END.split(text):
        if not sentence:
            continue
        if len(sentence) > max_chars:
            if buffer:
                pieces.append(buffer)
                buffer = ""
            pieces.extend(_split_hard(sentence, max_chars))
            continue
        candidate = f"{buffer} {sentence}".strip() if buffer else sentence
        if len(candidate) <= max_chars:
            buffer = candidate
        else:
            pieces.append(buffer)
            buffer = sentence
    if buffer:
        pieces.append(buffer)
    return pieces


def _split_hard(sentence: str, max_chars: int) -> list[str]:
    out: list[str] = []
    buffer = ""
    for word in sentence.split():
        if len(word) > max_chars:
            if buffer:
                out.append(buffer)
                buffer = ""
            out.extend(word[i : i + max_chars] for i in range(0, len(word), max_chars))
            continue
        candidate = f"{buffer} {word}".strip() if buffer else word
        if len(candidate) <= max_chars:
            buffer = candidate
        else:
            out.append(buffer)
            buffer = word
    if buffer:
        out.append(buffer)
    return out


def _synthesize_chunk(
    text: str,
    *,
    language: str,
    model: str,
    speaker: str,
    pace: float | None,
) -> bytes:
    body: dict = {"text": text, "target_language_code": language, "model": model}
    if speaker:
        body["speaker"] = speaker
    if pace is not None:
        # Sarvam calls this `pace`; the endpoint exposes it as `speed` because
        # that is what the build plan's request shape names. Mapped here, in
        # one place, rather than renaming Sarvam's own field everywhere.
        body["pace"] = pace

    with _semaphore:
        try:
            response = requests.post(
                _SARVAM_URL,
                json=body,
                headers={"api-subscription-key": _require_api_key()},
                timeout=_TIMEOUT_S,
            )
        except requests.RequestException as e:
            raise ValueError(f"Could not reach Sarvam /text-to-speech: {e}") from e

    if response.status_code != 200:
        # Sarvam's own error text is the useful part (the 400 "Text must
        # contain at least one character from the allowed languages" that
        # voice-agent's header documents is the single most likely failure
        # here, and it names the real problem). Truncated so a hostile or
        # broken upstream cannot push an unbounded body into a log line.
        raise ValueError(
            f"Sarvam /text-to-speech returned {response.status_code}: {response.text[:400]}"
        )

    try:
        payload = response.json()
    except ValueError as e:
        raise ValueError(f"Sarvam /text-to-speech returned a non-JSON body: {e}") from e

    audios = payload.get("audios") if isinstance(payload, dict) else None
    if not audios:
        raise ValueError(f"Sarvam /text-to-speech returned no audio for text: {text[:120]!r}")
    try:
        return base64.b64decode(audios[0], validate=True)
    except (binascii.Error, ValueError) as e:
        raise ValueError(f"Sarvam returned audio that is not valid base64: {e}") from e


def wav_params(wav_bytes: bytes) -> tuple[int, int, int, int]:
    """(channels, sampwidth, framerate, nframes) - and a real parse error, not a guess."""
    try:
        with wave.open(io.BytesIO(wav_bytes), "rb") as w:
            return w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
    except wave.Error as e:
        raise ValueError(f"Sarvam returned audio this service cannot read as WAV: {e}") from e


def concat_wav(parts: list[bytes]) -> bytes:
    """Concatenate same-format PCM WAVs into one WAV.

    Asserts format equality instead of trusting it. Every part here comes
    from the same model in the same request, so a mismatch means an upstream
    change - which must surface as a named error, not as audio that plays at
    the wrong speed for the back half of an episode.
    """
    if not parts:
        raise ValueError("no audio to concatenate")
    if len(parts) == 1:
        return parts[0]

    channels, sampwidth, framerate, _ = wav_params(parts[0])
    out = io.BytesIO()
    with wave.open(out, "wb") as writer:
        writer.setnchannels(channels)
        writer.setsampwidth(sampwidth)
        writer.setframerate(framerate)
        for index, part in enumerate(parts):
            p_channels, p_sampwidth, p_framerate, _ = wav_params(part)
            if (p_channels, p_sampwidth, p_framerate) != (channels, sampwidth, framerate):
                raise ValueError(
                    f"Sarvam returned mismatched audio formats across chunks: chunk 0 is "
                    f"{channels}ch/{sampwidth * 8}bit/{framerate}Hz but chunk {index} is "
                    f"{p_channels}ch/{p_sampwidth * 8}bit/{p_framerate}Hz"
                )
            with wave.open(io.BytesIO(part), "rb") as reader:
                writer.writeframes(reader.readframes(reader.getnframes()))
    return out.getvalue()


def synthesize(
    text: str,
    *,
    language: str | None = None,
    model: str | None = None,
    speaker: str | None = None,
    speed: float | None = None,
) -> tuple[bytes, float, int]:
    """Synthesize one narration segment. Returns (wav_bytes, duration_sec, sample_rate).

    `duration_sec` is measured from the returned WAV's frame count, which is
    the whole point of returning it - Stage 3 aligns B-roll against this
    number, not against episode.json's estimate.
    """
    chunks = split_for_synthesis(text)
    if not chunks:
        raise ValueError("text is empty after trimming - nothing to synthesize")

    parts = [
        _synthesize_chunk(
            chunk,
            language=language or DEFAULT_LANGUAGE,
            model=model or DEFAULT_MODEL,
            speaker=speaker if speaker is not None else DEFAULT_SPEAKER,
            pace=speed,
        )
        for chunk in chunks
    ]
    wav_bytes = concat_wav(parts)
    _, _, framerate, nframes = wav_params(wav_bytes)
    if framerate <= 0:
        raise ValueError("Sarvam returned audio with a zero sample rate")
    duration_sec = nframes / float(framerate)
    log.info(
        "synthesized %d char(s) in %d chunk(s) -> %.3fs at %dHz",
        len(text),
        len(chunks),
        duration_sec,
        framerate,
    )
    return wav_bytes, duration_sec, framerate
