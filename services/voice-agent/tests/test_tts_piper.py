"""
Unit tests for PiperTTS (app/tts_piper.py) - no real piper binary or ONNX
model needed: the test writes a stub `piper` executable that prints raw PCM
the same way `piper --output_raw -` would, plus a stub .onnx file so the
model-exists gate passes. Same asyncio.run-in-sync-test convention as
test_stt_sidecar_whisper.py (no pytest-asyncio dependency).
"""

import asyncio
import stat

import pytest
from livekit.agents import DEFAULT_API_CONNECT_OPTIONS, APIConnectionError

from app import config
from app.tts_piper import PiperTTS


def _fake_piper(tmp_path, pcm: bytes) -> tuple[str, str]:
    """A stub piper binary (writes PCM to stdout) + stub voice file.
    Returns (model_path, bin_path)."""
    model = tmp_path / "fake-voice.onnx"
    model.write_bytes(b"onnx")
    bin_path = tmp_path / "piper"
    bin_path.write_text(f"#!/bin/sh\ncat >/dev/null\nprintf '%s' '{pcm.hex()}' | xxd -r -p\n")
    bin_path.chmod(bin_path.stat().st_mode | stat.S_IXUSR)
    return str(model), str(bin_path)


def test_missing_model_fails_loud(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(config, "PIPER_MODEL_DIR", str(tmp_path))
    plugin = PiperTTS(voice="nonexistent-voice")

    async def go():
        async for _ in plugin.synthesize("hello", conn_options=DEFAULT_API_CONNECT_OPTIONS):
            pass

    with pytest.raises(APIConnectionError, match="not found"):
        asyncio.run(go())


def test_synthesize_emits_wav_frame(tmp_path, monkeypatch) -> None:
    pcm = b"\x01\x02" * 1600  # ~0.07s of 22050Hz s16le mono
    model, bin_path = _fake_piper(tmp_path, pcm)
    monkeypatch.setattr(config, "PIPER_BIN", bin_path)
    monkeypatch.setattr(config, "PIPER_SPEAKER_ID", -1)
    plugin = PiperTTS(voice=model)  # .onnx path form bypasses PIPER_MODEL_DIR

    async def go():
        return [
            audio
            async for audio in plugin.synthesize("hello", conn_options=DEFAULT_API_CONNECT_OPTIONS)
        ]

    chunks = asyncio.run(go())
    assert chunks, "piper subprocess produced no audio"
    # The SDK decodes the pushed WAV back into PCM AudioFrames - so what comes
    # out must be exactly the s16le mono bytes the stub piper printed.
    data = b"".join(a.frame.data.tobytes() for a in chunks)
    assert data == pcm


def test_binary_missing_fails_loud(tmp_path, monkeypatch) -> None:
    model, _ = _fake_piper(tmp_path, b"\x00" * 320)
    monkeypatch.setattr(config, "PIPER_BIN", str(tmp_path / "no-such-piper"))
    monkeypatch.setattr(config, "PIPER_SPEAKER_ID", -1)
    plugin = PiperTTS(voice=model)

    async def go():
        async for _ in plugin.synthesize("hello", conn_options=DEFAULT_API_CONNECT_OPTIONS):
            pass

    with pytest.raises(APIConnectionError, match="not found"):
        asyncio.run(go())
