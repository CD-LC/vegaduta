"""
First-ever tests for transcription.py - previously zero coverage despite
carrying the same class of concurrency-safety logic embeddings.py/
reranking.py already have dedicated tests for (test_backpressure.py), PLUS
its own extra piece neither of those has: a priority split between a
"general" worker pool and a small pool reserved exclusively for live voice
calls (`_acquire_inference_slot`, added 2026-08-06 so an ingestion backlog
can never starve a live caller). That split is exactly the kind of logic
that looks obviously correct on a read-through but is easy to silently
invert - a real regression here means either live calls get no priority
(voice quality regression, but only under load) or ingestion starts
draining the reserved pool (defeats the reservation entirely) - so it is
worth locking down with real concurrent-thread tests, not just a read.

Same posture as test_backpressure.py: no real WhisperModel is ever loaded
(no multi-hundred-MB download, no network). `_model` and the two semaphores
are monkeypatched with cheap fakes/fresh Semaphore instances, then driven
with real threads.

Run from sidecar/:  pytest tests/
"""
import threading

import pytest

from app import transcription

# --- _suffix_of: pure extension resolution ----------------------------------

class TestSuffixOf:
    @pytest.mark.parametrize(
        "filename,expected",
        [
            ("clip.mp3", ".mp3"),
            ("clip.wav", ".wav"),
            ("clip.m4a", ".m4a"),
            ("clip.ogg", ".ogg"),
            ("recording.mp4", ".mp4"),
            ("recording.mov", ".mov"),
            ("recording.webm", ".webm"),
        ],
    )
    def test_every_supported_extension_resolves(self, filename, expected):
        assert transcription._suffix_of(filename) == expected

    def test_extension_matching_is_case_insensitive(self):
        assert transcription._suffix_of("Voicemail.MP3") == ".mp3"
        assert transcription._suffix_of("CALL.WAV") == ".wav"

    def test_unsupported_extension_raises_value_error(self):
        with pytest.raises(ValueError, match="Unsupported audio extension"):
            transcription._suffix_of("malware.exe")

    def test_extensionless_filename_raises_value_error(self):
        with pytest.raises(ValueError, match="Unsupported audio extension"):
            transcription._suffix_of("no_extension_at_all")


# --- _acquire_inference_slot: reserved-pool priority split -------------------

class TestAcquireInferenceSlotPrioritySplit:
    def test_ingestion_call_blocks_on_a_full_general_pool_never_touching_reserved(self, monkeypatch):  # noqa: E501
        monkeypatch.setattr(transcription, "_general_semaphore", threading.Semaphore(1))
        monkeypatch.setattr(transcription, "_live_reserved_semaphore", threading.Semaphore(1))

        holder_entered = threading.Event()
        release = threading.Event()

        def hold_general_slot():
            with transcription._acquire_inference_slot(is_live_call=False):
                holder_entered.set()
                release.wait(timeout=5)

        holder = threading.Thread(target=hold_general_slot)
        holder.start()
        assert holder_entered.wait(timeout=2), "holder never entered the general slot"

        second_acquired = threading.Event()

        def second_ingestion_call():
            with transcription._acquire_inference_slot(is_live_call=False):
                second_acquired.set()

        second = threading.Thread(target=second_ingestion_call, daemon=True)
        second.start()
        # Ingestion never draws from the reserved pool (which is free) - it
        # must stay blocked on the full general pool.
        assert not second_acquired.wait(timeout=0.3), (
            "a second ingestion call acquired a slot while the general pool "
            "was full - it must never draw from the reserved pool"
        )

        release.set()
        holder.join(timeout=5)
        assert second_acquired.wait(timeout=2), "second ingestion call never proceeded once freed"
        second.join(timeout=5)

    def test_live_call_falls_back_to_reserved_pool_when_general_is_full(self, monkeypatch):
        monkeypatch.setattr(transcription, "_general_semaphore", threading.Semaphore(1))
        monkeypatch.setattr(transcription, "_live_reserved_semaphore", threading.Semaphore(1))

        holder_entered = threading.Event()
        release = threading.Event()

        def hold_general_via_ingestion():
            with transcription._acquire_inference_slot(is_live_call=False):
                holder_entered.set()
                release.wait(timeout=5)

        holder = threading.Thread(target=hold_general_via_ingestion)
        holder.start()
        assert holder_entered.wait(timeout=2)

        live_acquired = threading.Event()

        def live_call():
            with transcription._acquire_inference_slot(is_live_call=True):
                live_acquired.set()

        live = threading.Thread(target=live_call)
        live.start()
        # This is the whole point of the reservation: a live call must NOT
        # queue behind an ingestion job holding the general pool.
        assert live_acquired.wait(timeout=2), (
            "live call queued behind ingestion instead of using the reserved pool"
        )

        release.set()
        holder.join(timeout=5)
        live.join(timeout=5)

    def test_live_call_prefers_general_pool_over_reserved_when_general_is_free(self, monkeypatch):
        general = threading.Semaphore(1)
        reserved = threading.Semaphore(1)
        monkeypatch.setattr(transcription, "_general_semaphore", general)
        monkeypatch.setattr(transcription, "_live_reserved_semaphore", reserved)

        with transcription._acquire_inference_slot(is_live_call=True):
            # General slot should now be consumed...
            assert general.acquire(blocking=False) is False
            # ...but the reserved slot must be untouched, so it stays
            # available for a SECOND live call arriving while this one runs.
            assert reserved.acquire(blocking=False) is True
            reserved.release()

    def test_slot_is_released_even_when_the_wrapped_work_raises(self, monkeypatch):
        monkeypatch.setattr(transcription, "_general_semaphore", threading.Semaphore(1))
        monkeypatch.setattr(transcription, "_live_reserved_semaphore", threading.Semaphore(0))

        with pytest.raises(ValueError, match="boom"):
            with transcription._acquire_inference_slot(is_live_call=False):
                raise ValueError("boom")

        # The slot must have been released on the way out via `finally`.
        assert transcription._general_semaphore.acquire(blocking=False) is True


# --- get_model: double-checked locking (same pattern as embeddings/reranking) -

class TestTranscriptionLazySingleton:
    def test_concurrent_cold_start_constructs_model_exactly_once(self, monkeypatch):
        construction_count = [0]
        count_lock = threading.Lock()

        class _CountingModel:
            def __init__(self, *_args, **_kwargs):
                with count_lock:
                    construction_count[0] += 1
                threading.Event().wait(0.05)

        monkeypatch.setattr(transcription, "WhisperModel", _CountingModel)
        monkeypatch.setattr(transcription, "_model", None)

        results = []
        threads = [
            threading.Thread(target=lambda: results.append(transcription.get_model()))
            for _ in range(8)
        ]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=5)

        assert construction_count[0] == 1
        assert len(results) == 8
        assert all(r is results[0] for r in results)


# --- transcribe_audio: end-to-end against a fake model -----------------------

class _FakeSegment:
    def __init__(self, text: str):
        self.text = text


class _FakeWhisperModel:
    def __init__(self, segments):
        self._segments = segments

    def transcribe(self, _path, vad_filter=True):
        return self._segments, {"language": "en"}


class TestTranscribeAudio:
    def test_joins_and_strips_segment_text(self, monkeypatch):
        monkeypatch.setattr(
            transcription, "_model",
            _FakeWhisperModel([_FakeSegment("  hello "), _FakeSegment(" world  ")]),
        )
        monkeypatch.setattr(transcription, "_general_semaphore", threading.Semaphore(1))
        monkeypatch.setattr(transcription, "_live_reserved_semaphore", threading.Semaphore(1))

        result = transcription.transcribe_audio("clip.mp3", b"fake audio bytes")

        assert result == "hello world"

    def test_rejects_unsupported_extension_before_touching_the_model_or_pool(self, monkeypatch):
        # A zero-capacity general pool would make ANY real acquire hang/fail -
        # proving the extension check happens first and short-circuits
        # entirely before get_model()/_acquire_inference_slot are reached.
        monkeypatch.setattr(transcription, "_general_semaphore", threading.Semaphore(0))
        monkeypatch.setattr(transcription, "_live_reserved_semaphore", threading.Semaphore(0))

        with pytest.raises(ValueError, match="Unsupported audio extension"):
            transcription.transcribe_audio("clip.exe", b"data")

    def test_model_exceptions_are_wrapped_in_a_clear_value_error(self, monkeypatch):
        class _ExplodingModel:
            def transcribe(self, _path, vad_filter=True):
                raise RuntimeError("whisper blew up")

        monkeypatch.setattr(transcription, "_model", _ExplodingModel())
        monkeypatch.setattr(transcription, "_general_semaphore", threading.Semaphore(1))
        monkeypatch.setattr(transcription, "_live_reserved_semaphore", threading.Semaphore(1))

        with pytest.raises(ValueError, match="Could not transcribe audio"):
            transcription.transcribe_audio("clip.wav", b"data")

    def test_empty_transcription_returns_empty_string_not_an_error(self, monkeypatch):
        # transcribe_audio itself is silent-on-empty; main.py's /transcribe
        # endpoint is what turns an empty result into a 400 - this function's
        # own contract is just "join whatever segments came back".
        monkeypatch.setattr(transcription, "_model", _FakeWhisperModel([]))
        monkeypatch.setattr(transcription, "_general_semaphore", threading.Semaphore(1))
        monkeypatch.setattr(transcription, "_live_reserved_semaphore", threading.Semaphore(1))

        assert transcription.transcribe_audio("clip.wav", b"data") == ""
