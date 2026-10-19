"""
Master-Roadmap-2026.md item #2 (hybrid search + reranking for RAG).

Cross-encoder reranking, as a deliberately separate optional pass from
embeddings.py's bi-encoder models: a bi-encoder embeds query and chunk
independently (fast, index-able, what makes vector search possible at all),
while a cross-encoder scores a (query, chunk) pair jointly (much more
accurate at judging *this specific pair's* relevance, but cannot be
precomputed/indexed - it has to run once per candidate at query time). That
tradeoff is exactly why this only ever runs on a small top-N shortlist
AFTER hybrid (vector + keyword) search has already narrowed the field down
from "every chunk" to "the ~20 most promising ones" - see
KnowledgeSearchService's javadoc on the Java side.

Same "local, CPU, sentence-transformers, no paid API" posture as
embeddings.py - ms-marco-MiniLM-L-6-v2 is a small (~80MB), well-established
cross-encoder trained specifically for passage reranking, and
sentence-transformers already bundles CrossEncoder (no new pip dependency -
see requirements.txt's existing sentence-transformers==3.0.1 pin).
"""
import contextlib
import os
import threading

from sentence_transformers import CrossEncoder

RERANK_MODEL_NAME = "cross-encoder/ms-marco-MiniLM-L-6-v2"

# Backpressure (F-16, 2026-08-07): bound concurrent CrossEncoder.predict()
# calls, same motivation as embeddings.py's cap and transcription.py's
# measured 2026-08-05 thrashing incident (4 uncapped concurrent inferences
# on a 2-vCPU cgroup: 9-14s each vs ~4s sequential). The cross-encoder is
# small (~80MB) but each predict() scores every (query, doc) pair through a
# full transformer forward pass - CPU-hungry out of proportion to the model's
# size, on the same rationed cores Whisper and the embedders share.
#
# Default 1, deliberately tighter than embeddings' 2, and a real tradeoff:
# rerank sits on the INTERACTIVE search path (KnowledgeSearchService calls
# it per user query), so two concurrent searches serialize their rerank
# passes - the second waits. That wait is short (a top-~20 shortlist, small
# model) and predictable; letting them run concurrently instead degrades
# BOTH queries' latency via core contention AND slows any in-flight
# embed/transcribe work, which is the worse interactive outcome. Raise via
# env on deployments with real CPU headroom.
_RERANK_MAX_CONCURRENT = int(os.environ.get("RERANK_MAX_CONCURRENT", "1"))

# Bounded queue wait, not unbounded: the Java caller has its own HTTP
# timeout, and a fast 503-shaped failure it can degrade gracefully from
# (hybrid-search order is still a usable ranking without the rerank pass)
# beats a search request hung until the caller's socket gives up.
_RERANK_QUEUE_TIMEOUT_S = float(os.environ.get("RERANK_QUEUE_TIMEOUT_S", "30"))

# Lives in this module (acquired inside rerank()), not main.py's endpoint,
# so every future caller inherits the protection - same reasoning as
# embeddings.py's _encode_semaphore.
_predict_semaphore = threading.Semaphore(_RERANK_MAX_CONCURRENT)

_reranker: CrossEncoder | None = None
# Double-checked-locking guard for the lazy singleton below, same pattern
# as transcription.py's _model_lock: without it, two requests racing the
# very first load both observe `_reranker is None` and both construct a
# CrossEncoder (duplicate load work, plus a race on the global assignment).
# Held only on the one-time construction path - see the lock-free fast path
# in _get_reranker().
_reranker_lock = threading.Lock()


@contextlib.contextmanager
def _acquire_predict_slot():
    """
    Take one of the _RERANK_MAX_CONCURRENT predict slots, or fail fast.

    Captures the semaphore object once so acquire/release always pair
    against the same instance even if the module global is swapped (which
    tests do to exercise this path without real models).
    """
    sem = _predict_semaphore
    if not sem.acquire(timeout=_RERANK_QUEUE_TIMEOUT_S):
        raise RuntimeError(
            "rerank capacity saturated: waited "
            f"{_RERANK_QUEUE_TIMEOUT_S:g}s for one of {_RERANK_MAX_CONCURRENT} "
            "predict slots (RERANK_MAX_CONCURRENT / RERANK_QUEUE_TIMEOUT_S). "
            "Retry later or raise the limits if this deployment has more CPU."
        )
    try:
        yield
    finally:
        sem.release()


def _get_reranker() -> CrossEncoder:
    global _reranker
    if _reranker is not None:
        return _reranker
    with _reranker_lock:
        if _reranker is None:
            _reranker = CrossEncoder(RERANK_MODEL_NAME)
    return _reranker


def rerank(query: str, documents: list[str]) -> list[float]:
    """
    Scores every (query, document) pair and returns raw cross-encoder
    scores in the SAME order as `documents` was passed in - the caller
    (KnowledgeSearchService) is responsible for zipping these back onto
    their originating chunk ids and re-sorting, not this function.

    Returns [] for an empty input list rather than erroring - mirrors
    embed_texts()'s same empty-input handling in embeddings.py, and keeps
    /rerank a no-op rather than a 400 if a caller ever passes an
    already-empty candidate set.
    """
    if not documents:
        return []
    model = _get_reranker()
    pairs = [[query, doc] for doc in documents]
    # Only the inference itself holds a slot - pair construction above is
    # trivial and shouldn't count against the concurrency budget.
    with _acquire_predict_slot():
        scores = model.predict(pairs)
    return scores.tolist()
