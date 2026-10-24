"""Embedding providers behind one tiny interface.

- fastembed            (default: small, fast, ONNX, no torch)
- sentence-transformers (if you prefer the HF/torch stack)
- openai               (any OpenAI-compatible endpoint: OpenAI, Ollama,
                        vLLM, LM Studio, or your own service)
- hash                 (deterministic, no ML - smoke tests / dry runs only)

The provider + model name are baked into every chunk hash, so switching
models automatically re-embeds everything on the next run.
"""

from __future__ import annotations

import hashlib
import os
import threading
from collections import OrderedDict

import numpy as np

from .config import Config

BATCH_SIZE = 64


class Embedder:
    name: str = "base"
    model: str = ""

    def embed(self, texts: list[str]) -> np.ndarray:  # (n, dim) float32
        raise NotImplementedError

    @property
    def identity(self) -> str:
        return f"{self.name}:{self.model}"


class HashEmbedder(Embedder):
    """Deterministic pseudo-embeddings. Zero retrieval quality - exists so
    the pipeline can be exercised without any ML dependency."""

    name = "hash"
    dim = 256

    def __init__(self, model: str = "sha256-256d"):
        self.model = model

    def embed(self, texts: list[str]) -> np.ndarray:
        out = np.zeros((len(texts), self.dim), dtype=np.float32)
        for i, t in enumerate(texts):
            seed = hashlib.sha256(t.encode("utf-8")).digest()
            rng = np.random.default_rng(int.from_bytes(seed[:8], "little"))
            v = rng.standard_normal(self.dim).astype(np.float32)
            out[i] = v / (np.linalg.norm(v) + 1e-9)
        return out


class FastEmbedEmbedder(Embedder):
    name = "fastembed"

    def __init__(self, model: str):
        try:
            from fastembed import TextEmbedding  # type: ignore
        except ImportError as e:
            raise RuntimeError("embedder 'fastembed' requires: pip install fastembed") from e
        self.model = model
        self._m = TextEmbedding(model_name=model)

    def embed(self, texts: list[str]) -> np.ndarray:
        vecs = list(self._m.embed(texts, batch_size=BATCH_SIZE))
        return np.asarray(vecs, dtype=np.float32)


class SentenceTransformersEmbedder(Embedder):
    name = "sentence-transformers"

    def __init__(self, model: str):
        try:
            from sentence_transformers import SentenceTransformer  # type: ignore
        except ImportError as e:
            raise RuntimeError(
                "embedder 'sentence-transformers' requires: pip install sentence-transformers"
            ) from e
        self.model = model
        self._m = SentenceTransformer(model)

    def embed(self, texts: list[str]) -> np.ndarray:
        return np.asarray(
            self._m.encode(
                texts, batch_size=BATCH_SIZE, show_progress_bar=False, convert_to_numpy=True
            ),
            dtype=np.float32,
        )


class OpenAIEmbedder(Embedder):
    name = "openai"

    def __init__(self, model: str, base_url: str | None):
        try:
            from openai import OpenAI  # type: ignore
        except ImportError as e:
            raise RuntimeError("embedder 'openai' requires: pip install openai") from e
        self.model = model
        self._client = OpenAI(
            base_url=base_url,
            api_key=os.environ.get("OPENAI_API_KEY", "not-needed-for-local"),
        )

    def embed(self, texts: list[str]) -> np.ndarray:
        out: list[list[float]] = []
        for i in range(0, len(texts), BATCH_SIZE):
            resp = self._client.embeddings.create(model=self.model, input=texts[i : i + BATCH_SIZE])
            out.extend(d.embedding for d in resp.data)
        return np.asarray(out, dtype=np.float32)


def _cache_max() -> int:
    try:
        return max(1, int(os.environ.get("CODE_INTEL_EMBEDDER_CACHE", "") or 2))
    except ValueError:
        return 2


_EMBEDDER_CACHE: OrderedDict[tuple, Embedder] = OrderedDict()
_EMBEDDER_LOCK = threading.Lock()  # guards the dict only (never a load)
_BUILD_LOCKS: dict[tuple, threading.Lock] = {}


class _SerializedEmbedder(Embedder):
    """Shares one model instance across threads: encode calls are serialized
    with a lock because fastembed / sentence-transformers / torch do not
    guarantee thread-safe concurrent encode on one instance. Throughput for
    concurrent indexes is therefore bounded by one encode at a time per
    model; callers needing more should run more replicas."""

    def __init__(self, inner: Embedder):
        self._inner = inner
        self._lock = threading.Lock()
        self.name = inner.name
        self.model = inner.model

    def embed(self, texts: list[str]) -> np.ndarray:
        with self._lock:
            return self._inner.embed(texts)


def make_embedder(cfg: Config) -> Embedder:
    """Return a shared embedder per (kind, model, base_url), kept in a small
    LRU (CODE_INTEL_EMBEDDER_CACHE, default 2) so a long-running service does
    not reload a multi-hundred-MB model on every call, yet cannot accumulate
    unbounded models. Callers must only pass allow-listed model names (the
    service enforces CODE_INTEL_ALLOWED_EMBED_MODELS before this point). The
    cache lock is never held during a model load, so a slow load does not
    block searches that need an already-cached embedder."""
    key = (
        cfg.embedder.lower(),
        cfg.embed_model,
        cfg.openai_base_url if cfg.embedder.lower() == "openai" else None,
    )
    with _EMBEDDER_LOCK:
        emb = _EMBEDDER_CACHE.get(key)
        if emb is not None:
            _EMBEDDER_CACHE.move_to_end(key)
            return emb
        build_lock = _BUILD_LOCKS.setdefault(key, threading.Lock())
    with build_lock:  # one load per key; other keys load in parallel
        with _EMBEDDER_LOCK:
            emb = _EMBEDDER_CACHE.get(key)
        if emb is None:
            emb = _SerializedEmbedder(_build_embedder(cfg))
            with _EMBEDDER_LOCK:
                _EMBEDDER_CACHE[key] = emb
                _EMBEDDER_CACHE.move_to_end(key)
                while len(_EMBEDDER_CACHE) > _cache_max():
                    _EMBEDDER_CACHE.popitem(last=False)  # evict LRU
        with _EMBEDDER_LOCK:
            _BUILD_LOCKS.pop(key, None)
    return emb


def _build_embedder(cfg: Config) -> Embedder:
    kind = cfg.embedder.lower()
    if kind == "hash":
        return HashEmbedder()
    if kind == "fastembed":
        return FastEmbedEmbedder(cfg.embed_model)
    if kind in ("sentence-transformers", "st"):
        return SentenceTransformersEmbedder(cfg.embed_model)
    if kind == "openai":
        return OpenAIEmbedder(cfg.embed_model, cfg.openai_base_url)
    raise ValueError(
        f"Unknown embedder '{cfg.embedder}' "
        "(expected fastembed | sentence-transformers | openai | hash)"
    )
