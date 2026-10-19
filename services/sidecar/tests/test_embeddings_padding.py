"""
embed_texts()'s zero-padding contract - untested by test_pipeline.py (which
only covers the model registry's static invariants) and untested by
test_backpressure.py (whose fake models/results only exercise the
model_key=None, no-padding branch: `_FakeEncodeResult` implements a flat
`.tolist()` but never needs the per-row `.tolist()` the padding branch calls
during `[row.tolist() + ... for row in vectors]`).

This is real, load-bearing logic: every multi-model knowledge-ingestion
vector is padded here before it ever reaches Postgres's shared vector(768)
column (see embeddings.py's module docstring and
core's V26__knowledge_multi_model.sql). A padding-width bug would silently
corrupt every cosine-similarity search against a non-768-native model - i.e.
exactly the kind of bug that degrades retrieved context quality without
throwing an error anywhere.

Same posture as test_backpressure.py: no real SentenceTransformer is ever
constructed. `_FakeEncodeMatrix`/`_FakeRow` mimic just enough of numpy's
array interface for embed_texts to work against - flat `.tolist()` for the
no-pad path, and per-row iteration + per-row `.tolist()` for the pad path.

Run from sidecar/:  pytest tests/
"""
import pytest

from app import embeddings


class _FakeRow:
    """Mimics one row of a numpy 2D array: supports .tolist()."""

    def __init__(self, values):
        self._values = list(values)

    def tolist(self):
        return list(self._values)


class _FakeEncodeMatrix:
    """
    Mimics just enough of numpy.ndarray for embed_texts: iterating yields
    row objects (each with .tolist()), and the whole object has a flat
    .tolist() too - covers both embed_texts branches (should_pad True/False).
    """

    def __init__(self, rows):
        self._rows = [list(r) for r in rows]

    def __iter__(self):
        return iter(_FakeRow(r) for r in self._rows)

    def tolist(self):
        return [list(r) for r in self._rows]


class _FakeModel:
    """Returns a distinct, index-based vector per input text, so tests can
    verify order is preserved through the padding step."""

    def __init__(self, dim: int):
        self.dim = dim
        self.calls: list[list[str]] = []

    def encode(self, texts, **_kwargs):
        self.calls.append(list(texts))
        return _FakeEncodeMatrix([[float(i)] * self.dim for i in range(len(texts))])


@pytest.fixture(autouse=True)
def _fast_queue_timeout(monkeypatch):
    # Irrelevant to padding logic, but keeps any accidental real contention
    # from making a broken test hang instead of failing fast.
    monkeypatch.setattr(embeddings, "_EMBED_QUEUE_TIMEOUT_S", 5.0)


class TestPaddingAppliedWhenModelKeyIsExplicit:
    def test_narrower_than_storage_model_is_zero_padded_to_768(self, monkeypatch):
        # minilm-l6-v2 is registered at 384 native dims; explicit model_key
        # means should_pad=True.
        fake = _FakeModel(dim=384)
        monkeypatch.setitem(embeddings._model_cache, "minilm-l6-v2", fake)

        vectors, key, native_dims = embeddings.embed_texts(["a", "b"], "minilm-l6-v2")

        assert key == "minilm-l6-v2"
        assert native_dims == 384
        assert len(vectors) == 2
        for vector in vectors:
            assert len(vector) == embeddings.STORAGE_DIMENSIONS == 768

        # Row 0 was [0.0]*384 -> padded with 384 zeros; row 1 was [1.0]*384.
        assert vectors[0][:384] == [0.0] * 384
        assert vectors[0][384:] == [0.0] * 384
        assert vectors[1][:384] == [1.0] * 384
        assert vectors[1][384:] == [0.0] * 384

    def test_order_of_input_texts_is_preserved_through_padding(self, monkeypatch):
        fake = _FakeModel(dim=384)
        monkeypatch.setitem(embeddings._model_cache, "minilm-l6-v2", fake)

        vectors, _, _ = embeddings.embed_texts(["first", "second", "third"], "minilm-l6-v2")

        assert fake.calls == [["first", "second", "third"]]
        # Row i's fake value is i - padded output must still reflect that
        # per-row identity in input order, not resorted or interleaved.
        assert [v[0] for v in vectors] == [0.0, 1.0, 2.0]

    def test_model_at_exactly_storage_dimensions_gets_zero_pad_width(self, monkeypatch):
        # mpnet-base-v2 is registered at 768 native dims == STORAGE_DIMENSIONS,
        # so pad_width is 0 - the values must pass through unchanged, not
        # accidentally truncated or doubled.
        fake = _FakeModel(dim=768)
        monkeypatch.setitem(embeddings._model_cache, "mpnet-base-v2", fake)

        vectors, key, native_dims = embeddings.embed_texts(["only"], "mpnet-base-v2")

        assert key == "mpnet-base-v2"
        assert native_dims == 768
        assert len(vectors[0]) == 768
        assert vectors[0] == [0.0] * 768

    def test_model_wider_than_storage_dimensions_raises_instead_of_truncating(self, monkeypatch):
        # Simulates a future registry entry that outgrew STORAGE_DIMENSIONS
        # without the column/constant being widened - embed_texts must fail
        # loudly (ValueError) rather than silently truncate the vector,
        # which would corrupt cosine similarity for every row written.
        monkeypatch.setitem(embeddings.MODEL_REGISTRY, "oversized-model", ("fake/oversized", 800))
        fake = _FakeModel(dim=800)
        monkeypatch.setitem(embeddings._model_cache, "oversized-model", fake)

        with pytest.raises(ValueError, match="exceeding STORAGE_DIMENSIONS"):
            embeddings.embed_texts(["a"], "oversized-model")


class TestNoPaddingWhenModelKeyIsOmitted:
    def test_omitted_model_key_returns_native_dimensions_unpadded(self, monkeypatch):
        # core's AgentMemoryService path (predates multi-model ingestion,
        # its own table is vector(384)) - padding this would break its insert.
        fake = _FakeModel(dim=384)
        monkeypatch.setitem(embeddings._model_cache, embeddings.DEFAULT_MODEL_KEY, fake)

        vectors, key, native_dims = embeddings.embed_texts(["hello"])

        assert key == embeddings.DEFAULT_MODEL_KEY
        assert native_dims == 384
        assert len(vectors[0]) == 384
        assert vectors[0] == [0.0] * 384

    def test_omitted_model_key_still_resolves_default_even_for_a_wide_model_registered_elsewhere(
        self, monkeypatch
    ):
        # Sanity: the should_pad decision is driven purely by "was model_key
        # passed at all", not by which model ends up resolved.
        fake = _FakeModel(dim=384)
        monkeypatch.setitem(embeddings._model_cache, embeddings.DEFAULT_MODEL_KEY, fake)

        vectors, key, native_dims = embeddings.embed_texts(["x"], model_key=None)

        assert key == embeddings.DEFAULT_MODEL_KEY
        assert len(vectors[0]) == native_dims == 384
