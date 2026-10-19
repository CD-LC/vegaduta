"""
Tests for app/captions.py (Video Pipeline Stage 2, caption half).

Pure logic throughout - no Whisper, no network. The word timings these
functions consume are the only thing Whisper contributes, and they are
supplied here as plain dicts, which is exactly the boundary that makes
caption grouping testable without a model load.
"""
import pytest

from app import captions


def _words(*triples) -> list[dict]:
    return [{"word": w, "start": s, "end": e} for w, s, e in triples]


class TestFormatTimestamp:
    @pytest.mark.parametrize(
        "seconds,expected",
        [
            (0.0, "00:00:00,000"),
            (1.5, "00:00:01,500"),
            (61.25, "00:01:01,250"),
            (3661.007, "01:01:01,007"),
            # Negative is clamped rather than rendered as a negative
            # timestamp: ffmpeg's subtitle parser would reject the cue and
            # drop every caption after it.
            (-2.0, "00:00:00,000"),
        ],
    )
    def test_srt_timestamp_shape(self, seconds, expected):
        assert captions._format_timestamp(seconds) == expected

    def test_uses_a_comma_not_a_period(self):
        # SRT wants a comma; WebVTT wants a period. Getting this wrong makes
        # every cue silently invalid.
        assert "," in captions._format_timestamp(1.234)
        assert captions._format_timestamp(1.234).count(".") == 0


class TestGroupWords:
    def test_short_run_is_one_cue(self):
        cues = captions.group_words(_words(("Hello", 0.0, 0.4), ("there", 0.4, 0.8)))
        assert len(cues) == 1
        assert cues[0]["text"] == "Hello there"
        assert cues[0]["start"] == 0.0
        assert cues[0]["end"] == 0.8

    def test_splits_on_the_character_ceiling(self):
        words = _words(*[(f"word{i}", i * 0.3, i * 0.3 + 0.25) for i in range(20)])
        cues = captions.group_words(words, max_chars=20)
        assert len(cues) > 1
        assert all(len(c["text"]) <= 20 for c in cues)

    def test_splits_on_the_duration_ceiling(self):
        words = _words(*[(f"w{i}", i * 1.0, i * 1.0 + 0.5) for i in range(8)])
        cues = captions.group_words(words, max_chars=999, max_duration_s=2.0)
        assert len(cues) > 1
        assert all((c["end"] - c["start"]) <= 2.5 for c in cues)

    def test_a_long_pause_ends_the_cue_even_with_room_left(self):
        # Without this a cue spans the pause and sits on screen through
        # silence, which reads as a stuck caption rather than as a beat.
        cues = captions.group_words(
            _words(("before", 0.0, 0.4), ("after", 5.0, 5.4)),
            max_chars=999,
            max_duration_s=999,
            max_gap_s=0.6,
        )
        assert [c["text"] for c in cues] == ["before", "after"]

    def test_overlapping_cues_are_trimmed_not_emitted(self):
        # Whisper can hand back an end a hair past the next word's start.
        # ffmpeg's subtitle filter renders both cues at once, which looks
        # like a rendering bug and is not one.
        cues = captions.group_words(
            _words(("one", 0.0, 1.2), ("two", 1.0, 2.0)),
            max_chars=3,
        )
        assert len(cues) == 2
        assert cues[0]["end"] <= cues[1]["start"]

    def test_blank_and_whitespace_words_are_dropped(self):
        cues = captions.group_words(_words(("", 0.0, 0.1), ("  ", 0.1, 0.2), ("real", 0.2, 0.5)))
        assert [c["text"] for c in cues] == ["real"]

    def test_no_words_is_no_cues(self):
        assert captions.group_words([]) == []


class TestToSrt:
    def test_renders_numbered_blocks(self):
        srt = captions.to_srt(
            [
                {"start": 0.0, "end": 1.0, "text": "first"},
                {"start": 1.0, "end": 2.0, "text": "second"},
            ]
        )
        assert srt.splitlines()[0] == "1"
        assert srt.splitlines()[1] == "00:00:00,000 --> 00:00:01,000"
        assert srt.splitlines()[2] == "first"
        assert "2" in srt.splitlines()
        assert "00:00:01,000 --> 00:00:02,000" in srt

    def test_no_cues_is_an_empty_document(self):
        assert captions.to_srt([]) == ""

    def test_words_to_srt_is_the_two_steps_together(self):
        words = _words(("hello", 0.0, 0.5), ("world", 0.5, 1.0))
        assert captions.words_to_srt(words) == captions.to_srt(captions.group_words(words))


class TestShiftWords:
    def test_offsets_every_timing(self):
        # Stage 2 synthesizes one beat at a time, so each beat's timings start
        # at zero; the episode timeline needs the cumulative MEASURED duration
        # of every preceding beat added on.
        shifted = captions.shift_words(_words(("a", 0.0, 0.5), ("b", 0.5, 1.0)), 10.0)
        assert [(w["start"], w["end"]) for w in shifted] == [(10.0, 10.5), (10.5, 11.0)]

    def test_does_not_mutate_the_input(self):
        original = _words(("a", 0.0, 0.5))
        captions.shift_words(original, 5.0)
        assert original[0]["start"] == 0.0
