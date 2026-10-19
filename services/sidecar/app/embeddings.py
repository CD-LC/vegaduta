"""
Stage 5: local embedding model wrapper.
Multi-model ingestion: extended from a single hardcoded model to a small
registry of local sentence-transformers models a tenant can choose between
per knowledge collection (see core's EmbeddingModelCatalog.java, which is
the canonical list - these two must be changed together).

All models here stay local/CPU (sentence-transformers), never a paid hosted
embeddings API. Two reasons: (1) this platform is BYOK for chat models, but
requiring a paid API key just to embed text for RAG would be a needless
cost/dependency for something this cheap to run locally; (2) keeps the
ingestion path usable even before a user has activated any LLM provider
(Stage 2).

Trade-off, documented in the README same as Stage 4's one-time npx download:
the first call to *any* model here downloads it (tens to ~400MB) from
Hugging Face Hub, so it needs network access once per model actually used -
after that it's cached locally (~/.cache/huggingface) for every run. Only
models a tenant actually picks are ever downloaded/loaded - MODEL_CACHE below
is populated lazily per key, not eagerly for the whole registry at startup.

STORAGE_DIMENSIONS (768) is the width every returned embedding is padded to,
regardless of which model produced it - see core's V26__knowledge_multi_model.sql
for why zero-padding is safe (it doesn't change a vector's direction or
norm, so cosine similarity between two same-model, identically-padded
vectors is unaffected). This keeps one pgvector column serving every model
in the registry; if a future model exceeds 768 native dimensions, both this
constant and a new migration widening the column need to change together.
"""
import contextlib
import os
import threading

from sentence_transformers import SentenceTransformer

STORAGE_DIMENSIONS = 768

DEFAULT_MODEL_KEY = "minilm-l6-v2"

# key -> (HuggingFace repo id, native output dimensions). Keys match
# EmbeddingModelCatalog.java's ModelOption.key exactly.
# "multilingual-minilm" (RAG audit fix, 2026-07-29): the first four are all
# English-trained, contradicting the platform's Indic-language positioning -
# paraphrase-multilingual-MiniLM-L12-v2 covers 50+ languages (Hindi, Tamil,
# Telugu, Bengali, ...), outputs 384 native dims (fits the existing
# zero-pad-to-768 scheme; bge-m3's 1024 dims would need a column widening),
# and stays CPU-friendly. Not the default - see EmbeddingModelCatalog.java's
# entry for why the default stays minilm-l6-v2.
MODEL_REGISTRY: dict[str, tuple[str, int]] = {
    "minilm-l6-v2": ("sentence-transformers/all-MiniLM-L6-v2", 384),
    "mpnet-base-v2": ("sentence-transformers/all-mpnet-base-v2", 768),
    "bge-small-en": ("BAAI/bge-small-en-v1.5", 384),
    "multi-qa-minilm": ("sentence-transformers/multi-qa-MiniLM-L6-cos-v1", 384),
    "multilingual-minilm": ("sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2", 384),
}

# Kept for backward-compat with anything still importing the old single-model
# constants directly (there shouldn't be any left after this change, but
# costs nothing to keep pointing at the same default model).
MODEL_NAME = MODEL_REGISTRY[DEFAULT_MODEL_KEY][0]
EMBEDDING_DIMENSIONS = STORAGE_DIMENSIONS

# Backpressure (F-16, 2026-08-07): bound how many model.encode() calls may
# run concurrently, with an explicit queue timeout instead of unbounded
# waiting. Motivation is transcription.py's MEASURED 2026-08-05 incident:
# with no cap, 4 concurrent Whisper inferences on this container's 2-vCPU
# cgroup each took 9-14s vs ~4s sequential - the calls' internal threads
# fought each other for the same cores ("thrashing", a real consumer-visible
# latency regression, not a deadlock). sentence-transformers' encode() is
# CPU-bound on those SAME rationed cores (torch intra-op threads), so an
# uncapped burst of /embed calls recreates exactly that incident - and also
# steals the cores Whisper's own carefully-budgeted workers are counting on.
# Default 2 mirrors the "realistically target 2 concurrent on a 4 vCPU
# shared box" ceiling that incident's research converged on; override via
# env if a deployment's real core budget differs.
_EMBED_MAX_CONCURRENT = int(os.environ.get("EMBED_MAX_CONCURRENT", "2"))

# How long a caller waits for a free encode slot before giving up. Bounded
# on purpose: the Java caller (KnowledgeIngestionService et al.) has its own
# HTTP timeouts, so a fast, clearly-labeled failure it can turn into a
# 503-shaped retry beats a request that sits in an invisible queue until the
# caller's socket times out anyway - by then the work is wasted even if a
# slot finally frees up.
_EMBED_QUEUE_TIMEOUT_S = float(os.environ.get("EMBED_QUEUE_TIMEOUT_S", "30"))

# Deliberately lives in THIS module (acquired inside embed_texts), not in
# main.py's endpoint: every future caller of embed_texts - new endpoints,
# internal pipelines - gets the same protection without having to remember
# it exists.
_encode_semaphore = threading.Semaphore(_EMBED_MAX_CONCURRENT)

_model_cache: dict[str, SentenceTransformer] = {}
# Double-checked-locking guard for the lazy per-key model cache below - same
# pattern (and same reasoning) as transcription.py's _model_lock. Without
# it, two requests arriving before a given model's first load completes both
# observe the cache miss and both construct the SentenceTransformer
# concurrently: duplicated multi-hundred-MB load work at best, and two
# simultaneous first-time Hugging Face downloads into the same cache dir at
# worst. One lock for the whole cache (not per-key) is fine - it is only
# ever held on the one-time construction path, never per-request; see the
# lock-free fast path in get_model().
_model_cache_lock = threading.Lock()


@contextlib.contextmanager
def _acquire_encode_slot():
    """
    Take one of the _EMBED_MAX_CONCURRENT encode slots, or fail fast.

    Captures the semaphore object once so acquire and release always pair
    against the same instance even if the module global is swapped (which
    tests do to exercise this path without real models).
    """
    sem = _encode_semaphore
    if not sem.acquire(timeout=_EMBED_QUEUE_TIMEOUT_S):
        raise RuntimeError(
            "embedding capacity saturated: waited "
            f"{_EMBED_QUEUE_TIMEOUT_S:g}s for one of {_EMBED_MAX_CONCURRENT} "
            "encode slots (EMBED_MAX_CONCURRENT / EMBED_QUEUE_TIMEOUT_S). "
            "Retry later or raise the limits if this deployment has more CPU."
        )
    try:
        yield
    finally:
        sem.release()


def resolve_model_key(model_key: str | None) -> str:
    if not model_key:
        return DEFAULT_MODEL_KEY
    if model_key not in MODEL_REGISTRY:
        raise ValueError(
            f"Unknown embedding model '{model_key}'. Known models: {', '.join(MODEL_REGISTRY.keys())}"  # noqa: E501
        )
    return model_key


def get_model(model_key: str) -> SentenceTransformer:
    # Lock-free fast path: after a model's first load, every request takes
    # this branch without ever touching the lock (dict reads are atomic
    # under the GIL, and values are only ever inserted, never mutated).
    model = _model_cache.get(model_key)
    if model is not None:
        return model
    with _model_cache_lock:
        model = _model_cache.get(model_key)
        if model is None:
            repo_id, _ = MODEL_REGISTRY[model_key]
            model = SentenceTransformer(repo_id)
            _model_cache[model_key] = model
    return model


def embed_texts(texts: list[str], model_key: str | None = None) -> tuple[list[list[float]], str, int]:  # noqa: E501
    """
    Embeds a batch of texts with the given (or default) model, L2-normalized
    so pgvector's cosine distance operator (<=>) behaves as expected against
    the ivfflat index the Java core creates with vector_cosine_ops.

    Zero-padding up to STORAGE_DIMENSIONS only happens when the caller passes
    an explicit model_key - that's the multi-model knowledge-ingestion path
    (core's KnowledgeIngestionService/KnowledgeSearchService always resolve
    and pass a concrete model per collection, never omit it), which needs
    every model's output to fit knowledge_chunks' one shared vector(768)
    column. A caller that omits model_key entirely (core's AgentMemoryService,
    predating multi-model ingestion and unrelated to knowledge collections)
    gets the plain native-dimension vector exactly as before - its own
    agent_memory table is still vector(384) and was never part of this
    migration, so padding its output would just break the insert.

    Returns (vectors, resolved_model_key, native_dimensions) - callers that
    need to know what was actually used (echoed back to Java in
    EmbedResponse) get it without a second lookup into MODEL_REGISTRY.
    """
    should_pad = model_key is not None
    resolved_key = resolve_model_key(model_key)
    if not texts:
        return [], resolved_key, MODEL_REGISTRY[resolved_key][1]

    model = get_model(resolved_key)
    _, native_dims = MODEL_REGISTRY[resolved_key]
    # Only the actual inference holds a slot - model resolution/loading above
    # and the padding below are cheap and shouldn't count against the budget.
    with _acquire_encode_slot():
        vectors = model.encode(texts, normalize_embeddings=True, convert_to_numpy=True)

    if not should_pad:
        return vectors.tolist(), resolved_key, native_dims

    pad_width = STORAGE_DIMENSIONS - native_dims
    if pad_width < 0:
        # Should only happen if MODEL_REGISTRY grows a model wider than
        # STORAGE_DIMENSIONS without also raising the constant - fail loudly
        # rather than silently truncating (which WOULD corrupt similarity).
        raise ValueError(
            f"Model '{resolved_key}' outputs {native_dims} dims, exceeding STORAGE_DIMENSIONS={STORAGE_DIMENSIONS} - "  # noqa: E501
            "raise STORAGE_DIMENSIONS (and the matching Postgres column width) before using this model."  # noqa: E501
        )

    padded = [row.tolist() + [0.0] * pad_width for row in vectors]
    return padded, resolved_key, native_dims
