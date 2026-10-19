"""Optional cross-encoder reranker for store.py's hybrid_search.

Audit item #22 (2026-08-09, sequenced after the KB reranker fix - the
sibling fix in the same batch flips KNOWLEDGE_RERANK_ENABLED=true for
core/knowledge's Java RAG pipeline; this is that fix's code-intel
equivalent): store.py's `_rerank` was a keyword/substring-boost heuristic
only. This module adds the real thing - same model, same backpressure
pattern as sidecar/app/reranking.py's CrossEncoder - but kept OPT-IN
(CODE_INTEL_RERANK_ENABLED, default false) and import-guarded like
embedders.py's SentenceTransformersEmbedder, NOT a new hard dependency.

Why opt-in here but not in the sidecar: this service's Dockerfile
deliberately stays on fastembed (ONNX, no torch) to keep the image light
- see requirements.txt's `# sentence-transformers>=3.0   # embedder:
sentence-transformers` staying commented out on purpose. sentence-
transformers pulls in torch, which is a large, meaningfully heavier image
for every code-intel deployment, most of which don't need it. Making the
cross-encoder pass mandatory would silently regress every existing
deployment's image size and cold-start; making it opt-in (install
sentence-transformers + set the env var) gets the accuracy win without
that regression. store._rerank() falls back to the heuristic whenever
this is disabled OR the dependency isn't installed OR inference raises,
so a deployment that never opts in behaves exactly as before.
"""
from __future__ import annotations

import contextlib
import os
import threading

RERANK_ENABLED = os.environ.get("CODE_INTEL_RERANK_ENABLED", "false").lower() == "true"
RERANK_MODEL_NAME = os.environ.get(
    "CODE_INTEL_RERANK_MODEL", "cross-encoder/ms-marco-MiniLM-L-6-v2")

# Same rationale as sidecar/app/reranking.py's _RERANK_MAX_CONCURRENT: the
# cross-encoder is small but each predict() is a full transformer forward
# pass per candidate, and this sits on the interactive search path
# (hybrid_search is called per user/agent query). Default 1 - raise via env
# on deployments with real CPU headroom.
_RERANK_MAX_CONCURRENT = int(os.environ.get("CODE_INTEL_RERANK_MAX_CONCURRENT", "1"))

# Bounded wait, not unbounded: callers of hybrid_search (the MCP bridge,
# the HTTP /search route) have their own timeouts upstream, and a fast
# failure that store._rerank can degrade from (heuristic reorder is still
# a usable ranking) beats a search request hung on a saturated queue.
_RERANK_QUEUE_TIMEOUT_S = float(
    os.environ.get("CODE_INTEL_RERANK_QUEUE_TIMEOUT_S", "30"))

_predict_semaphore = threading.Semaphore(_RERANK_MAX_CONCURRENT)

_reranker = None
_reranker_lock = threading.Lock()


@contextlib.contextmanager
def _acquire_predict_slot():
    sem = _predict_semaphore
    if not sem.acquire(timeout=_RERANK_QUEUE_TIMEOUT_S):
        raise RuntimeError(
            "rerank capacity saturated: waited "
            f"{_RERANK_QUEUE_TIMEOUT_S:g}s for one of "
            f"{_RERANK_MAX_CONCURRENT} predict slots "
            "(CODE_INTEL_RERANK_MAX_CONCURRENT / "
            "CODE_INTEL_RERANK_QUEUE_TIMEOUT_S)."
        )
    try:
        yield
    finally:
        sem.release()


def _get_reranker():
    global _reranker
    if _reranker is not None:
        return _reranker
    with _reranker_lock:
        if _reranker is None:
            try:
                from sentence_transformers import CrossEncoder  # type: ignore
            except ImportError as e:
                raise RuntimeError(
                    "cross-encoder rerank requires: "
                    "pip install sentence-transformers") from e
            _reranker = CrossEncoder(RERANK_MODEL_NAME)
    return _reranker


def score(query: str, documents: list[str]) -> list[float]:
    """Raw cross-encoder scores, same order as `documents`. Raises
    RuntimeError if sentence-transformers isn't installed or the predict
    queue is saturated - callers (store._rerank) are expected to catch
    this and fall back to the heuristic, not propagate it to the request."""
    if not documents:
        return []
    model = _get_reranker()
    pairs = [[query, doc] for doc in documents]
    with _acquire_predict_slot():
        scores = model.predict(pairs)
    return scores.tolist()
