"""
Tests for app/tts.py (Video Pipeline Stage 2).

No network, same rule as every other file in this suite (CI's `python` job
states it outright: no sidecar test may download a model or hit the
network). Sarvam is never called here - `_synthesize_chunk` is monkeypatched
in the one test that needs a synthesis round trip, and everything else
exercises pure functions.

The WAV fixtures are built with the stdlib `wave` module rather than checked
in as binary, so the tests assert against a format they fully describe.
"""
import io
import wave

import pytest

from app import tts


def _wav(seconds: float, *, framerate: int = 22050, channels: int = 1, sampwidth: int = 2) -> bytes:
    frames = int(seconds * framerate)
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(sampwidth)
        w.setframerate(framerate)
        w.writeframes(b"\x00" * frames * channels * sampwidth)
    return out.getvalue()


class TestSplitForSynthesis:
    def test_short_text_is_one_piece(self):
        assert tts.split_for_synthesis("Hello there.") == ["Hello there."]

    def test_blank_text_is_no_pieces(self):
        assert tts.split_for_synthesis("   \n  ") == []

    def test_splits_on_sentence_boundaries_under_the_ceiling(self):
        text = ("Alpha sentence here. " * 20).strip()
        pieces = tts.split_for_synthesis(text, max_chars=100)
        assert len(pieces) > 1
        assert all(len(p) <= 100 for p in pieces)
        # Nothing may be dropped or duplicated - the pieces are concatenated
        # into one audio track, so a lost clause is a lost clause on a
        # published video.
        assert " ".join(pieces) == text

    def test_devanagari_danda_is_a_sentence_terminator(self):
        # Sarvam's whole reason for being in this stack is the Indic
        # languages; splitting only on "." would leave Hindi narration as one
        # unsplittable blob that the API then rejects whole.
        text = "यह पहला वाक्य है। यह दूसरा वाक्य है। यह तीसरा वाक्य है।"
        pieces = tts.split_for_synthesis(text, max_chars=30)
        assert len(pieces) >= 2
        assert all(len(p) <= 30 for p in pieces)

    def test_a_single_over_long_sentence_splits_on_words(self):
        text = "word " * 100
        pieces = tts.split_for_synthesis(text.strip(), max_chars=40)
        assert all(len(p) <= 40 for p in pieces)
        assert " ".join(pieces).split() == text.split()

    def test_a_single_over_long_word_splits_on_characters(self):
        # Degenerate (a URL pasted into narration) but must terminate rather
        # than loop or raise.
        pieces = tts.split_for_synthesis("x" * 250, max_chars=100)
        assert [len(p) for p in pieces] == [100, 100, 50]


class TestConcatWav:
    def test_single_part_is_returned_unchanged(self):
        one = _wav(0.5)
        assert tts.concat_wav([one]) is one

    def test_durations_add_up(self):
        joined = tts.concat_wav([_wav(1.0), _wav(0.5), _wav(0.25)])
        channels, sampwidth, framerate, nframes = tts.wav_params(joined)
        assert (channels, sampwidth, framerate) == (1, 2, 22050)
        assert nframes / framerate == pytest.approx(1.75, abs=0.01)

    def test_mismatched_sample_rates_raise_and_name_the_chunk(self):
        # Asserted rather than trusted: silently concatenating a 22050Hz and
        # a 16000Hz part plays the back half of an episode at the wrong
        # speed, which is far worse than a failed render.
        with pytest.raises(ValueError, match="mismatched audio formats"):
            tts.concat_wav([_wav(1.0, framerate=22050), _wav(1.0, framerate=16000)])

    def test_empty_input_raises(self):
        with pytest.raises(ValueError, match="no audio to concatenate"):
            tts.concat_wav([])

    def test_non_wav_bytes_raise_a_named_error(self):
        with pytest.raises(ValueError, match="cannot read as WAV"):
            tts.wav_params(b"this is not a wav file at all")


class TestSynthesize:
    def test_returns_measured_duration_not_an_estimate(self, monkeypatch):
        # The measured duration is the actual product of this module: Stage 3
        # aligns B-roll against it. Two chunks of 1.0s must report 2.0s.
        monkeypatch.setattr(tts, "_synthesize_chunk", lambda text, **kw: _wav(1.0))
        wav, duration, sample_rate = tts.synthesize("A. " * 60, language="en-IN")
        assert sample_rate == 22050
        assert duration == pytest.approx(len(tts.split_for_synthesis("A. " * 60)) * 1.0, abs=0.01)
        assert wav.startswith(b"RIFF")

    def test_passes_speed_through_as_sarvam_pace(self, monkeypatch):
        seen = {}

        def fake(text, **kwargs):
            seen.update(kwargs)
            return _wav(0.2)

        monkeypatch.setattr(tts, "_synthesize_chunk", fake)
        tts.synthesize("Hello.", speed=1.25, language="hi-IN", speaker="anushka")
        assert seen["pace"] == 1.25
        assert seen["language"] == "hi-IN"
        assert seen["speaker"] == "anushka"

    def test_empty_text_raises_before_any_request(self, monkeypatch):
        monkeypatch.setattr(
            tts,
            "_synthesize_chunk",
            lambda *a, **k: pytest.fail("Sarvam must not be called for empty text"),
        )
        with pytest.raises(ValueError, match="nothing to synthesize"):
            tts.synthesize("   ")

    def test_missing_api_key_names_the_variable(self, monkeypatch):
        # The operator reading a failed workflow run is the person who has to
        # fix this, so the message has to say which var and where.
        monkeypatch.setattr(tts, "_API_KEY", "")
        with pytest.raises(ValueError, match="SARVAM_API_KEY is not configured"):
            tts._require_api_key()
