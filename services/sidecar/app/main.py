"""
Agentic AI Platform - ML/ingestion sidecar.

Stage 1: scaffolding (health endpoint).
Stage 5: chunk + embed endpoints, backing the Java core's pgvector
ingestion/search pipeline (see core's com.agenticai.core.knowledge package).
Knowledge Stage F3a: /extract, turning PDF/docx/html bytes into plain text
so they can feed the same chunk+embed pipeline .md uploads already use.

Internal auth (2026-08-07): every route except /health requires
X-Internal-Token to match SIDECAR_SHARED_SECRET - this was the last
unauthenticated service in the stack, and it was published on host port
8001. Same shared-secret shape as tool-sandbox/app/main.py's
require_internal_token; see require_internal_token below for the
fail-closed/escape-hatch semantics.
"""
import base64
import hmac
import logging
import os

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field

from . import captions as captions_module
from . import render as render_module
from . import tts as tts_module
from .chunking import chunk_text
from .embeddings import embed_texts
from .extraction import extract_text
from .reranking import rerank
from .transcription import transcribe_audio, transcribe_words

logging.basicConfig(
    level=os.environ.get("SIDECAR_LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
log = logging.getLogger("sidecar")

# Read once at import, like tool-sandbox's settings module - a rotated
# secret requires a container restart either way (the Java caller resolves
# its copy at bean construction), so per-request env reads would only
# pretend to support live rotation.
_AUTH_REQUIRED = os.environ.get("SIDECAR_AUTH_REQUIRED", "true").strip().lower() != "false"
_SHARED_SECRET = os.environ.get("SIDECAR_SHARED_SECRET", "")

if not _AUTH_REQUIRED:
    # The ONE unauthenticated mode, for bare local dev (uvicorn on a laptop,
    # no compose, no secret distribution). Never set this on a deployment -
    # the compose default keeps it true.
    log.warning(
        "SIDECAR_AUTH_REQUIRED=false - every endpoint is open to any caller "
        "that can reach this port. Bare-local-dev escape hatch only; never "
        "run a deployment like this."
    )
elif not _SHARED_SECRET:
    # Fail closed, matching mcp-stdio-runner: a deployment that
    # forgot the secret serves 503s (visible, diagnosable) rather than
    # silently accepting anonymous traffic.
    log.error(
        "SIDECAR_SHARED_SECRET is not set while SIDECAR_AUTH_REQUIRED=true - "
        "every request except /health will be rejected with 503. Set "
        "SIDECAR_SHARED_SECRET in deploy/.env (openssl rand -hex 32) or, for "
        "bare local dev only, set SIDECAR_AUTH_REQUIRED=false."
    )


def require_internal_token(x_internal_token: str = Header(default="")) -> None:
    """Shared-secret auth, compared in constant time.

    Mirrors tool-sandbox's require_internal_token, with one deliberate
    difference: that service has no unauthenticated mode at all, while this
    one honors SIDECAR_AUTH_REQUIRED=false as a documented bare-local-dev
    escape hatch (warned loudly at startup above). With auth required and no
    secret configured, everything fails closed with a 503.
    """
    if not _AUTH_REQUIRED:
        return
    if not _SHARED_SECRET:
        raise HTTPException(status_code=503, detail="sidecar auth is not configured")
    if not hmac.compare_digest(x_internal_token or "", _SHARED_SECRET):
        raise HTTPException(status_code=401, detail="invalid internal token")


app = FastAPI(
    title="Agentic AI Platform - ML Sidecar",
    version="0.2.0",
    docs_url=None,      # no interactive docs on a service published on a host port
    redoc_url=None,     # (8001) - same posture as tool-sandbox/app/main.py; the
    openapi_url=None,   # schema would map this service's whole surface for free
)


@app.exception_handler(RuntimeError)
def capacity_saturated_handler(request: Request, exc: RuntimeError) -> JSONResponse:
    """Backpressure -> 503 (Wave 3): embeddings.py/reranking.py raise a
    RuntimeError whose message contains "capacity saturated" when their
    bounded inference queues stay full past the acquire timeout. Without
    this handler that surfaced as a generic 500, which the Java caller's
    retry/alerting can't tell apart from a real bug - 503 is the honest
    "overloaded, retry later" signal. Any OTHER RuntimeError is re-raised
    so it still propagates as FastAPI's default 500 (same
    don't-relabel-unexpected-errors posture as /extract's ValueError
    handling below)."""
    if "capacity saturated" in str(exc):
        return JSONResponse(status_code=503, content={"detail": str(exc)})
    raise exc


@app.get("/health")
def health() -> dict:
    """Unauthenticated liveness only - it reveals no tenant data, and both
    the compose healthcheck and SystemStatusController's probe call it
    without credentials."""
    return {"status": "ok", "service": "sidecar"}


class EmbedRequest(BaseModel):
    text: str = Field(min_length=1)
    chunk_size: int = Field(default=150, ge=10, le=200, description="Chunk size in words")
    chunk_overlap: int = Field(default=30, ge=0, description="Overlap between consecutive chunks, in words")  # noqa: E501
    # Multi-model ingestion - one of embeddings.MODEL_REGISTRY's keys, or
    # None/omitted for the default model. Field name "model" matches
    # SidecarEmbedRequest's Java record exactly.
    model: str | None = Field(default=None)


class Chunk(BaseModel):
    index: int
    text: str
    embedding: list[float]


class EmbedResponse(BaseModel):
    model: str
    dimensions: int
    chunk_size: int
    chunk_overlap: int
    chunks: list[Chunk]


@app.post("/embed", response_model=EmbedResponse,
          dependencies=[Depends(require_internal_token)])
def embed(request: EmbedRequest) -> EmbedResponse:
    if request.chunk_overlap >= request.chunk_size:
        raise HTTPException(status_code=400, detail="chunk_overlap must be smaller than chunk_size")

    texts = chunk_text(request.text, request.chunk_size, request.chunk_overlap)
    if not texts:
        raise HTTPException(status_code=400, detail="text produced no chunks after stripping whitespace")  # noqa: E501

    try:
        vectors, resolved_model, _native_dims = embed_texts(texts, request.model)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))  # noqa: B904

    chunks = [
        Chunk(index=i, text=t, embedding=v)
        for i, (t, v) in enumerate(zip(texts, vectors, strict=False))
    ]
    return EmbedResponse(
        model=resolved_model,
        dimensions=len(vectors[0]) if vectors else 0,
        chunk_size=request.chunk_size,
        chunk_overlap=request.chunk_overlap,
        chunks=chunks,
    )


class EmbedQueryRequest(BaseModel):
    text: str = Field(min_length=1)
    model: str | None = Field(default=None)


class EmbedQueryResponse(BaseModel):
    model: str
    dimensions: int
    embedding: list[float]


@app.post("/embed-query", response_model=EmbedQueryResponse,
          dependencies=[Depends(require_internal_token)])
def embed_query(request: EmbedQueryRequest) -> EmbedQueryResponse:
    # Kept unchanged/unused by the Java core - see SidecarClient's javadoc
    # for the documented interop quirk that made it reuse /embed instead.
    # Still updated for model support so direct/future callers aren't stuck
    # on the old single-model behavior.
    try:
        vectors, resolved_model, _native_dims = embed_texts([request.text], request.model)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))  # noqa: B904
    return EmbedQueryResponse(model=resolved_model, dimensions=len(vectors[0]), embedding=vectors[0])  # noqa: E501


class ExtractRequest(BaseModel):
    filename: str = Field(min_length=1)
    # Base64 rather than multipart/form-data - this client (Java's
    # RestClient over a SimpleClientHttpRequestFactory) already has a
    # documented interop quirk with this exact sidecar over chunked
    # transfer-encoding (see SidecarClient's javadoc); staying on the
    # already-proven-working JSON-body pattern every other endpoint here
    # uses avoids risking a second, different interop issue with
    # multipart instead.
    content_base64: str = Field(min_length=1)
    # OCR provider abstraction - both optional/None unless the tenant has
    # activated a cloud OCR provider via OcrProviderConfigService on the
    # Java side. None means "use the sidecar's built-in Tesseract," which is
    # also what happens if ocr_provider is set but has no sidecar
    # implementation yet (see extraction.py's _extract_image).
    ocr_provider: str | None = Field(default=None)
    ocr_api_key: str | None = Field(default=None)


class ExtractResponse(BaseModel):
    text: str


@app.post("/extract", response_model=ExtractResponse,
          dependencies=[Depends(require_internal_token)])
def extract(request: ExtractRequest) -> ExtractResponse:
    try:
        content = base64.b64decode(request.content_base64, validate=True)
    except Exception:
        raise HTTPException(status_code=400, detail="content_base64 is not valid base64")  # noqa: B904

    try:
        text = extract_text(request.filename, content, request.ocr_provider, request.ocr_api_key)
    except ValueError as e:
        # A ValueError from extraction.py means the file itself was the
        # problem (unsupported extension, corrupt/unparseable content) -
        # a 400, not a 500. Any OTHER exception type is left to propagate
        # as FastAPI's default 500 - deliberately not caught here, so an
        # unexpected parser bug is visible as a real error instead of
        # being silently relabeled as a client mistake.
        raise HTTPException(status_code=400, detail=str(e))  # noqa: B904

    if not text.strip():
        raise HTTPException(status_code=400, detail="Extraction produced no readable text content")

    return ExtractResponse(text=text)


class RerankRequest(BaseModel):
    query: str = Field(min_length=1)
    documents: list[str] = Field(default_factory=list)


class RerankResponse(BaseModel):
    scores: list[float]


@app.post("/rerank", response_model=RerankResponse,
          dependencies=[Depends(require_internal_token)])
def rerank_endpoint(request: RerankRequest) -> RerankResponse:
    """
    Master-Roadmap-2026.md item #2. Scores each of `documents` against
    `query` with a cross-encoder (see reranking.py's module docstring for
    why this is a separate, more expensive pass from /embed) and returns
    scores in the same order they were submitted - the Java caller
    (KnowledgeSearchService) owns re-associating them with chunk ids.
    Empty `documents` returns an empty `scores` list rather than a 400,
    same permissive-on-empty-input posture as /embed.
    """
    return RerankResponse(scores=rerank(request.query, request.documents))


class TranscribeRequest(BaseModel):
    filename: str = Field(min_length=1)
    # Same base64-over-JSON reasoning as ExtractRequest above.
    content_base64: str = Field(min_length=1)
    # 2026-08-06 latency audit: signals transcribe_audio's worker-pool
    # priority split (transcription.py's _acquire_inference_slot). Defaults
    # False so KnowledgeIngestionJobRunner (this endpoint's only other
    # caller) is unaffected; voice-agent's SidecarWhisperSTT sets it True.
    is_live_call: bool = False


class TranscribeResponse(BaseModel):
    text: str


@app.post("/transcribe", response_model=TranscribeResponse,
          dependencies=[Depends(require_internal_token)])
def transcribe(request: TranscribeRequest) -> TranscribeResponse:
    """
    Knowledge Stage F3c. Called by KnowledgeIngestionJobRunner (a scheduled
    poller in the Java core), never synchronously from an HTTP upload -
    this call can legitimately take a long time (real-world audio, CPU
    inference), which is exactly why the Java side made this an async job
    in the first place rather than a request/response upload like
    /extract.
    """
    try:
        content = base64.b64decode(request.content_base64, validate=True)
    except Exception:
        raise HTTPException(status_code=400, detail="content_base64 is not valid base64")  # noqa: B904

    try:
        text = transcribe_audio(request.filename, content, is_live_call=request.is_live_call)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))  # noqa: B904

    if not text.strip():
        raise HTTPException(status_code=400, detail="Transcription produced no readable text content")  # noqa: E501

    return TranscribeResponse(text=text)


# --- Video Pipeline Stage 2: narration + captions ---------------------------
#
# /tts and /captions are two endpoints rather than one for a reason worth
# stating: /tts is a network call to Sarvam and /captions is a local Whisper
# inference, so they have completely different failure modes, timeouts and
# capacity limits. Fusing them would make a Sarvam rate-limit and a saturated
# Whisper pool indistinguishable in a workflow run log.


class TtsSegmentRequest(BaseModel):
    # `id` is the beat id from episode.json. Carried through untouched so the
    # caller can line results up with beats without relying on list order.
    id: str = Field(min_length=1)
    text: str = Field(min_length=1)


class TtsRequest(BaseModel):
    segments: list[TtsSegmentRequest] = Field(min_length=1)
    language: str | None = Field(default=None, description="Sarvam target_language_code, hi-IN etc")
    voice: str | None = Field(default=None, description="Sarvam speaker name")
    model: str | None = Field(default=None, description="Sarvam model, default bulbul:v3")
    speed: float | None = Field(default=None, ge=0.3, le=3.0, description="Sarvam pace")
    # The combined track is the whole episode's narration as one base64 WAV,
    # which for a 90-second episode is a few megabytes of JSON. Stage 3 needs
    # it; a caller that only wants per-beat durations does not.
    include_combined: bool = True


class TtsSegmentResult(BaseModel):
    id: str
    duration_sec: float
    sample_rate: int
    audio_base64: str


class TtsResponse(BaseModel):
    language: str
    model: str
    sample_rate: int
    total_duration_sec: float
    segments: list[TtsSegmentResult]
    combined_audio_base64: str | None = None


@app.post("/tts", response_model=TtsResponse,
          dependencies=[Depends(require_internal_token)])
def tts(request: TtsRequest) -> TtsResponse:
    """
    Video Pipeline Stage 2. Synthesizes one narration segment per beat and
    returns MEASURED durations - Stage 3's /render aligns each B-roll clip
    against these numbers, never against episode.json's LLM-estimated
    `duration_sec`.

    Synchronous, unlike /render. A 12-beat episode is 12 serialized Sarvam
    calls at roughly two seconds each, comfortably inside a workflow step;
    there is no reason to pay for a job protocol here.

    A missing SARVAM_API_KEY surfaces as a 400 naming the variable rather
    than a raw 500, because the operator reading a failed workflow run is
    the person who has to fix it. Same reasoning for a Sarvam 4xx: its own
    error text is passed through, since "Text must contain at least one
    character from the allowed languages" names the real problem and a
    generic "TTS failed" does not.
    """
    try:
        results: list[TtsSegmentResult] = []
        raw_parts: list[bytes] = []
        for segment in request.segments:
            wav, duration, sample_rate = tts_module.synthesize(
                segment.text,
                language=request.language,
                model=request.model,
                speaker=request.voice,
                speed=request.speed,
            )
            raw_parts.append(wav)
            results.append(
                TtsSegmentResult(
                    id=segment.id,
                    duration_sec=duration,
                    sample_rate=sample_rate,
                    audio_base64=base64.b64encode(wav).decode("ascii"),
                )
            )
        combined = None
        if request.include_combined:
            combined = base64.b64encode(tts_module.concat_wav(raw_parts)).decode("ascii")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e

    return TtsResponse(
        language=request.language or tts_module.DEFAULT_LANGUAGE,
        model=request.model or tts_module.DEFAULT_MODEL,
        sample_rate=results[0].sample_rate,
        total_duration_sec=sum(r.duration_sec for r in results),
        segments=results,
        combined_audio_base64=combined,
    )


class CaptionsRequest(BaseModel):
    filename: str = Field(min_length=1)
    content_base64: str = Field(min_length=1)
    language: str | None = Field(default=None, description="ISO code 'en'. None autodetects")
    offset_sec: float = Field(default=0.0, description="Added to every timing, for per-beat use")
    # A list rather than a raw string because callers think in terms of "the
    # names in this episode", and joining is this service's job rather than
    # theirs. Capped so a caller cannot push an unbounded prompt into every
    # inference - Whisper only conditions on a limited window anyway.
    vocabulary: list[str] | None = Field(
        default=None,
        max_length=64,
        description="Proper nouns and technical terms to bias transcription toward, "
        "e.g. product and tool names. Whisper mis-hears these more than ordinary "
        "words, and a caption generated from audio inherits the mistake.",
    )
    max_chars: int = Field(default=captions_module.MAX_CHARS, ge=12, le=120)
    max_duration_s: float = Field(default=captions_module.MAX_DURATION_S, gt=0.2, le=10.0)


class CaptionsResponse(BaseModel):
    srt: str
    cue_count: int
    word_count: int
    words: list[dict]


@app.post("/captions", response_model=CaptionsResponse,
          dependencies=[Depends(require_internal_token)])
def captions(request: CaptionsRequest) -> CaptionsResponse:
    """
    Video Pipeline Stage 2, caption half. A SEPARATE endpoint rather than a
    flag on /transcribe, deliberately: KnowledgeIngestionJobRunner on the
    Java side deserializes /transcribe's {"text": ...} response, and adding
    optional fields to a response a Jackson client already parses is a
    compatibility risk taken for no gain. This reuses transcription.py's
    model and semaphores - the same Whisper instance, the same capacity
    limits - and only the response shape is new.

    `words` is returned alongside the SRT because the caller may want to
    re-group cues (a different aspect ratio wants a different line length)
    without paying for a second inference pass.
    """
    try:
        content = base64.b64decode(request.content_base64, validate=True)
    except Exception as e:
        raise HTTPException(status_code=400, detail="content_base64 is not valid base64") from e

    try:
        words = transcribe_words(
            request.filename,
            content,
            language=request.language,
            initial_prompt=", ".join(request.vocabulary) if request.vocabulary else None,
        )
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e

    if request.offset_sec:
        words = captions_module.shift_words(words, request.offset_sec)
    cues = captions_module.group_words(
        words, max_chars=request.max_chars, max_duration_s=request.max_duration_s
    )
    return CaptionsResponse(
        srt=captions_module.to_srt(cues),
        cue_count=len(cues),
        word_count=len(words),
        words=words,
    )


# --- Video Pipeline Stage 3: render -----------------------------------------


class MediaSource(BaseModel):
    """Exactly one of these three. Which one is validated in render.py, not
    here, so the same message is produced whether the request arrives over
    HTTP or the module is driven directly from a test."""

    path: str | None = Field(default=None, description="Workspace-relative path")
    url: str | None = Field(default=None, description="Public http(s) URL")
    content_base64: str | None = Field(default=None)


class EditListEntry(BaseModel):
    beat_id: str = Field(min_length=1)
    duration_sec: float = Field(gt=0, description="MEASURED audio duration from /tts")
    clip: MediaSource


class RenderRequest(BaseModel):
    edit_list: list[EditListEntry] = Field(min_length=1)
    audio: MediaSource
    srt: str | None = None
    burn_captions: bool = True
    width: int = Field(default=1080, ge=128, le=3840)
    height: int = Field(default=1920, ge=128, le=3840)
    fps: int = Field(default=30, ge=1, le=60)
    return_base64: bool = False
    callback_url: str | None = Field(
        default=None,
        description="POSTed the job snapshot on completion. Host must be on "
        "RENDER_CALLBACK_ALLOWED_HOSTS - the workflow engine has no wait node, "
        "so this push is what resumes a workflow after a render.",
    )
    callback_headers: dict[str, str] | None = None


class RenderJobResponse(BaseModel):
    job_id: str
    status: str
    detail: str = ""
    created_at: float
    started_at: float | None = None
    finished_at: float | None = None
    error: str | None = None
    result: dict | None = None
    queue_depth: int = 0


@app.post("/render", response_model=RenderJobResponse, status_code=202,
          dependencies=[Depends(require_internal_token)])
def render_submit(request: RenderRequest) -> RenderJobResponse:
    """
    Video Pipeline Stage 3. Queues a render and returns 202 with a job id.

    The ONLY asynchronous endpoint on this service, and the reason is a
    chain of timeouts rather than a preference: a 90-second 1080x1920 render
    is minutes of single-core x264, which outlives core's RestClient
    timeout, the workflow engine's per-node step timeout, and - on a real
    deployment - Cloudflare's ~100s request ceiling. /transcribe gets away
    with being synchronous because the Java side wraps it in a job of its
    own; nothing wraps a render.

    Concurrency is capped at one render at a time (a single-worker pool in
    render.py). That is deliberate load-shedding, not a scaling oversight:
    this container also serves /embed for live RAG traffic, and a render
    allowed to take every core would push that endpoint's p95 out of band.
    Renders queue; RAG does not degrade.
    """
    try:
        snapshot = render_module.submit(request.model_dump())
    except render_module.RenderError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    return RenderJobResponse(**snapshot, queue_depth=render_module.queue_depth())


@app.get("/render/{job_id}", response_model=RenderJobResponse,
         dependencies=[Depends(require_internal_token)])
def render_status(job_id: str) -> RenderJobResponse:
    snapshot = render_module.get_job(job_id)
    if snapshot is None:
        raise HTTPException(status_code=404, detail=f"no such render job: {job_id}")
    return RenderJobResponse(**snapshot, queue_depth=render_module.queue_depth())


@app.get("/render/{job_id}/artifact/{kind}",
         dependencies=[Depends(require_internal_token)])
def render_artifact(job_id: str, kind: str) -> FileResponse:
    """Stream a finished render's mp4 or thumbnail.

    The only binary-body route on this service. Every other endpoint carries
    binary as base64 in JSON, because core's RestClient has a documented
    interop quirk with this sidecar over chunked transfer-encoding (see
    ExtractRequest's comment). That quirk is about the REQUEST body; a
    Content-Length'd file response is a different direction and a different
    problem, and base64-ing a 20MB mp4 through a JSON job result just to
    avoid adding a route is the worse trade.
    """
    try:
        path = render_module.artifact_path(job_id, kind)
    except render_module.RenderError as e:
        raise HTTPException(status_code=404, detail=str(e)) from e
    media_type = "video/mp4" if kind == "video" else "image/jpeg"
    return FileResponse(path, media_type=media_type, filename=os.path.basename(path))
