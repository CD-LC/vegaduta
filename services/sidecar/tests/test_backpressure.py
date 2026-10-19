"""
F-16 backpressure tests for embeddings.py and reranking.py - pure-logic,
same posture as test_pipeline.py: no model downloads, no network. The real
SentenceTransformer/CrossEncoder constructors are never invoked; tests
monkeypatch the module-level model cache/singleton (and, for the
double-checked-locking tests, the constructor symbols themselves) with
cheap fakes, then drive the semaphore/lock logic with real threads.

Timeouts in these tests are deliberately tiny (50ms) - they exercise the
"give up and raise" branch, so a short wait is the point, not a flake risk:
the slot-holder thread is guaranteed to still be blocked (it waits on an
Event the test only sets afterwards).

Run from sidecar/:  pytest tests/
"""
import threading

import pytest

from app import embeddings, reranking


class _FakeEncodeResult:
    """Mimics just enough of the numpy array embed_texts touches (.tolist())."""

    def __init__(self, rows):
        self._rows = rows

    def tolist(self):
        return self._rows


class _BlockingModel:
    """Fake SentenceTransformer whose encode() parks on an Event."""

    def __init__(self, started: threading.Event, release: threading.Event):
        self.started = started
        self.release = release

    def encode(self, texts, **_kwargs):
        self.started.set()
        assert self.release.wait(timeout=5), "test never released the blocked encode"
        return _FakeEncodeResult([[0.0] * 384 for _ in texts])


class _InstantModel:
    def encode(self, texts, **_kwargs):
        return _FakeEncodeResult([[0.0] * 384 for _ in texts])


class _ExplodingModel:
    def encode(self, _texts, **_kwargs):
        raise ValueError("boom")


# --- embeddings: bounded concurrency ---------------------------------------

class TestEmbedBackpressure:
    def test_saturated_pool_raises_runtimeerror_not_unbounded_queue(self, monkeypatch):
        started, release = threading.Event(), threading.Event()
        monkeypatch.setitem(embeddings._model_cache, embeddings.DEFAULT_MODEL_KEY,
                            _BlockingModel(started, release))
        monkeypatch.setattr(embeddings, "_encode_semaphore", threading.Semaphore(1))
        monkeypatch.setattr(embeddings, "_EMBED_QUEUE_TIMEOUT_S", 0.05)
        monkeypatch.setattr(embeddings, "_EMBED_MAX_CONCURRENT", 1)

        holder = threading.Thread(target=embeddings.embed_texts, args=(["a"],))
        holder.start()
        try:
            assert started.wait(timeout=5), "slot-holder thread never entered encode()"
            with pytest.raises(RuntimeError, match="embedding capacity saturated"):
                embeddings.embed_texts(["b"])
        finally:
            release.set()
            holder.join(timeout=5)

    def test_slot_is_released_after_success(self, monkeypatch):
        monkeypatch.setitem(embeddings._model_cache, embeddings.DEFAULT_MODEL_KEY,
                            _InstantModel())
        monkeypatch.setattr(embeddings, "_encode_semaphore", threading.Semaphore(1))
        monkeypatch.setattr(embeddings, "_EMBED_QUEUE_TIMEOUT_S", 0.05)

        # With one slot, back-to-back sequential calls only work if the
        # slot is released each time.
        for _ in range(3):
            vectors, key, dims = embeddings.embed_texts(["hello"])
            assert vectors == [[0.0] * 384]
            assert key == embeddings.DEFAULT_MODEL_KEY
            assert dims == 384

    def test_slot_is_released_when_encode_raises(self, monkeypatch):
        monkeypatch.setitem(embeddings._model_cache, embeddings.DEFAULT_MODEL_KEY,
                            _ExplodingModel())
        monkeypatch.setattr(embeddings, "_encode_semaphore", threading.Semaphore(1))
        monkeypatch.setattr(embeddings, "_EMBED_QUEUE_TIMEOUT_S", 0.05)

        with pytest.raises(ValueError, match="boom"):
            embeddings.embed_texts(["a"])
        # The failed call must not have leaked its slot.
        monkeypatch.setitem(embeddings._model_cache, embeddings.DEFAULT_MODEL_KEY,
                            _InstantModel())
        vectors, _, _ = embeddings.embed_texts(["a"])
        assert vectors == [[0.0] * 384]

    def test_empty_input_never_touches_the_pool(self, monkeypatch):
        # A zero-capacity semaphore would make ANY acquire raise - proving
        # the empty-input early return stays slot-free.
        monkeypatch.setattr(embeddings, "_encode_semaphore", threading.Semaphore(0))
        monkeypatch.setattr(embeddings, "_EMBED_QUEUE_TIMEOUT_S", 0.05)
        vectors, key, _ = embeddings.embed_texts([])
        assert vectors == []
        assert key == embeddings.DEFAULT_MODEL_KEY


# --- embeddings: double-checked locking ------------------------------------

class TestEmbedLazySingleton:
    def test_concurrent_cold_start_constructs_model_exactly_once(self, monkeypatch):
        construction_count = [0]
        count_lock = threading.Lock()

        class _CountingModel:
            def __init__(self, _repo_id):
                with count_lock:
                    construction_count[0] += 1
                # Widen the race window: without the cache lock, every
                # thread would sit here mid-construction simultaneously.
                threading.Event().wait(0.05)

        monkeypatch.setattr(embeddings, "SentenceTransformer", _CountingModel)
        monkeypatch.setattr(embeddings, "_model_cache", {})

        results = []
        threads = [
            threading.Thread(
                target=lambda: results.append(embeddings.get_model(embeddings.DEFAULT_MODEL_KEY))
            )
            for _ in range(8)
        ]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=5)

        assert construction_count[0] == 1
        assert len(results) == 8
        assert all(r is results[0] for r in results)


# --- reranking: bounded concurrency -----------------------------------------

class _FakePredictResult:
    def __init__(self, rows):
        self._rows = rows

    def tolist(self):
        return self._rows


class _BlockingReranker:
    def __init__(self, started: threading.Event, release: threading.Event):
        self.started = started
        self.release = release

    def predict(self, pairs):
        self.started.set()
        assert self.release.wait(timeout=5), "test never released the blocked predict"
        return _FakePredictResult([0.5] * len(pairs))


class _InstantReranker:
    def predict(self, pairs):
        return _FakePredictResult([0.5] * len(pairs))


class TestRerankBackpressure:
    def test_saturated_pool_raises_runtimeerror(self, monkeypatch):
        started, release = threading.Event(), threading.Event()
        monkeypatch.setattr(reranking, "_reranker", _BlockingReranker(started, release))
        monkeypatch.setattr(reranking, "_predict_semaphore", threading.Semaphore(1))
        monkeypatch.setattr(reranking, "_RERANK_QUEUE_TIMEOUT_S", 0.05)
        monkeypatch.setattr(reranking, "_RERANK_MAX_CONCURRENT", 1)

        holder = threading.Thread(target=reranking.rerank, args=("q", ["doc"]))
        holder.start()
        try:
            assert started.wait(timeout=5), "slot-holder thread never entered predict()"
            with pytest.raises(RuntimeError, match="rerank capacity saturated"):
                reranking.rerank("q2", ["doc2"])
        finally:
            release.set()
            holder.join(timeout=5)

    def test_slot_is_released_after_success(self, monkeypatch):
        monkeypatch.setattr(reranking, "_reranker", _InstantReranker())
        monkeypatch.setattr(reranking, "_predict_semaphore", threading.Semaphore(1))
        monkeypatch.setattr(reranking, "_RERANK_QUEUE_TIMEOUT_S", 0.05)

        for _ in range(3):
            assert reranking.rerank("q", ["a", "b"]) == [0.5, 0.5]

    def test_empty_documents_never_touch_the_pool(self, monkeypatch):
        monkeypatch.setattr(reranking, "_predict_semaphore", threading.Semaphore(0))
        monkeypatch.setattr(reranking, "_RERANK_QUEUE_TIMEOUT_S", 0.05)
        assert reranking.rerank("q", []) == []


# --- reranking: double-checked locking ---------------------------------------

class TestRerankLazySingleton:
    def test_concurrent_cold_start_constructs_model_exactly_once(self, monkeypatch):
        construction_count = [0]
        count_lock = threading.Lock()

        class _CountingReranker:
            def __init__(self, _model_name):
                with count_lock:
                    construction_count[0] += 1
                threading.Event().wait(0.05)

        monkeypatch.setattr(reranking, "CrossEncoder", _CountingReranker)
        monkeypatch.setattr(reranking, "_reranker", None)

        results = []
        threads = [
            threading.Thread(target=lambda: results.append(reranking._get_reranker()))
            for _ in range(8)
        ]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=5)

        assert construction_count[0] == 1
        assert len(results) == 8
        assert all(r is results[0] for r in results)
