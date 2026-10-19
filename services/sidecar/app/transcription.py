"""
Knowledge Stage F3c/F3d: audio (and audio-bearing video) transcription for
the async ingestion job path.

Uses faster-whisper (a CTranslate2-backed Whisper reimplementation)
entirely on CPU - same "no hosted API required" philosophy as
embeddings.py's local embedding model and extraction.py's OCR. The "base"
model is a deliberate default: small enough to run acceptably on CPU, good
enough for real speech. A per-deployment override is reasonable future
work, not built yet.

Unlike extraction.py's extractors (in-memory bytes in, text out),
faster-whisper needs a real file on disk - its underlying audio decoding
(via PyAV) expects a seekable file path, not an arbitrary bytes buffer.
Written to a NamedTemporaryFile and deleted immediately after this
function returns, regardless of outcome.

Deliberately conservative on failure, same convention as extraction.py: a
bad/corrupt file or unsupported extension raises ValueError with a
specific message - main.py's /transcribe turns that into a 400, not a 500.

Stage F3d note (corrected understanding vs. the Stage F Build Plan's
original assumption): video containers (.mp4/.mov/.webm) do NOT need a
separate ffmpeg audio-extraction step before reaching this function.
faster-whisper's own audio decoding is built on PyAV, which "bundles the
FFmpeg libraries so there is no additional system dependency" (PyAV's own
docs) and opens any container format, decoding just the first audio stream
(`container.decode(audio=0)`) regardless of whether video frames are also
present. Confirmed by reading faster_whisper/audio.py directly before
writing this comment, rather than assuming - the exact same function below
already handles video files correctly with zero changes; only the
supported-extension list needed to grow. Frame-level visual content (OCR
on video frames, scene detection, etc.) is a separate, unbuilt feature -
this only ever transcribes the audio track, stated honestly per the Build
Plan's Section 5.
"""
import contextlib
import os
import tempfile
import threading

from faster_whisper import WhisperModel

MODEL_SIZE = "base"

# Concurrency tuning (2026-08-05 - "single worker blocks concurrent callers"
# incident). CTranslate2 exposes two ORTHOGONAL thread knobs, easy to
# conflate: `cpu_threads` (intra_threads) controls how many CPU threads a
# SINGLE inference call uses internally; `num_workers` (inter_threads)
# controls how many inference calls CTranslate2 will run truly concurrently
# via its own internal replica queue, sharing one copy of the model weights
# (confirmed via CTranslate2's docs: all compute methods release the GIL, so
# concurrent Python-thread calls against one shared WhisperModel are the
# supported pattern - never instantiate a model per thread/request, that
# only multiplies RAM for no benefit on CPU).
#
# Left at faster-whisper's own defaults, `cpu_threads` greedily grabs EVERY
# visible core for one call - measured live on this box (2 vCPU allotted to
# this container's cgroup out of the host's 4): 4 concurrent /transcribe
# calls each took 9-14s (vs ~4s best-case sequential), i.e. each call's
# internal threads were fighting the others for the same cores instead of
# getting a clean, bounded share - "thrashing", not deadlock, but a real
# consumer-visible latency regression under any concurrent voice/knowledge
# load. The fix is exactly the pattern CTranslate2's own parallelism docs and
# faster-whisper's community discussions (#346, #406, issue #1419)
# converge on for a small fixed core count: cpu_threads x num_workers should
# stay comfortably under the container's real core budget, leaving headroom
# for the ~15 OTHER containers sharing this same small VM (Postgres,
# Keycloak, core, Redis, LiveKit, ...) - "no infrastructure increase" means
# working within that budget, not assuming this process owns the box.
#
# Defaults chosen for a 4 vCPU HOST shared this way (override via env vars
# if a deployment's actual core budget differs): cpu_threads=1 (one thread
# per in-flight call - the fastest single-call time is a good trade against
# never oversubscribing) x num_workers=2 (two truly concurrent transcriptions
# at once, matching the "realistically target 2 concurrent on a 4 vCPU box"
# ceiling the research converged on) = 2 worst-case concurrent CPU threads
# for STT, well under 4.
_CPU_THREADS = int(os.environ.get("WHISPER_CPU_THREADS", "1"))
_NUM_WORKERS = int(os.environ.get("WHISPER_NUM_WORKERS", "2"))

# 2026-08-06 follow-up to the tuning above: cpu_threads/num_workers bounds
# *total* concurrent inference, but says nothing about *who* gets a slot
# first. Measured: this same worker pool is shared identically by live-call
# transcription (voice-agent's SidecarWhisperSTT, blocking a real person
# mid-conversation) and unrelated knowledge-ingestion embed/extract/
# transcribe jobs (KnowledgeIngestionJobRunner - a background poller with no
# human waiting synchronously). Under 4 concurrent requests this measured
# 9-14s/call, up from ~4s sequential - the two traffic classes were fighting
# over the same _NUM_WORKERS budget with no priority between them, so a
# burst of ingestion jobs can fully occupy every slot and make a live caller
# queue behind them with no bound.
#
# Fix: split the worker budget into a small reserved pool live calls alone
# can draw from, plus a general pool everyone (including live calls, when
# idle) can draw from - see `_acquire_inference_slot` below. This guarantees
# a live call is never queued behind an arbitrarily deep ingestion backlog,
# without adding a second deployed service or changing the CTranslate2
# concurrency ceiling itself (`_LIVE_RESERVED_WORKERS` + `_GENERAL_WORKERS`
# always sum to `_NUM_WORKERS`).
#
# Only meaningful when there's more than one worker to split; a
# single-worker deployment (`WHISPER_NUM_WORKERS=1`) has nothing to
# partition and falls back to today's fully-shared single-pool behavior -
# reserving that one worker exclusively for live calls would instead fully
# starve ingestion, the same failure mode in the other direction.
_LIVE_RESERVED_WORKERS = int(os.environ.get("WHISPER_LIVE_RESERVED_WORKERS", "1"))
_LIVE_RESERVED_WORKERS = (
    min(_LIVE_RESERVED_WORKERS, max(_NUM_WORKERS - 1, 0)) if _NUM_WORKERS > 1 else 0
)
_GENERAL_WORKERS = _NUM_WORKERS - _LIVE_RESERVED_WORKERS

_SUPPORTED_EXTENSIONS = (".mp3", ".wav", ".m4a", ".ogg", ".mp4", ".mov", ".webm")

_model: WhisperModel | None = None
# Double-checked-locking guard for the lazy singleton below. Without this,
# two requests arriving before the very first model load completes could
# both observe `_model is None` and both construct a WhisperModel
# concurrently - wasted work at best (loading the same weights twice) and a
# real race on the module-global `_model` assignment at worst. This lock is
# only ever held for the (one-time) construction path, never per-request -
# see the fast, lock-free `if _model is not None` check below that every
# request after the first takes.
_model_lock = threading.Lock()

# Bounds actual concurrent .transcribe() calls to _NUM_WORKERS in total
# (across both pools combined), independent of how many HTTP requests
# FastAPI/Starlette's own thread pool happens to be running at once
# (Starlette runs every plain `def` endpoint - all of this sidecar's
# endpoints - in its own worker-thread pool, sized far larger than
# _NUM_WORKERS by default). Without this, a burst of N>2 simultaneous
# /transcribe calls would still all enter model.transcribe() at once and
# rely on CTranslate2's OWN internal queuing alone; these semaphores make
# the queueing explicit - a burst of transcriptions no longer has an
# unbounded number of OS threads all mid-inference simultaneously, only
# _NUM_WORKERS of them, with the rest waiting their turn (predictable
# queueing, not chaotic contention).
#
# Two pools, not one, per the priority-split reasoning above:
# `_general_semaphore` (size `_GENERAL_WORKERS`) is drawn from by every
# caller; `_live_reserved_semaphore` (size `_LIVE_RESERVED_WORKERS`) is
# drawn from ONLY by live calls, and only once the general pool is already
# full - see `_acquire_inference_slot`. Ingestion work never touches the
# reserved pool, which is what makes the reservation real rather than
# advisory.
_general_semaphore = threading.Semaphore(_GENERAL_WORKERS)
_live_reserved_semaphore = threading.Semaphore(_LIVE_RESERVED_WORKERS)


@contextlib.contextmanager
def _acquire_inference_slot(is_live_call: bool):
    """
    Acquire one of the _NUM_WORKERS inference slots, honoring the live-call
    reservation.

    Ingestion (`is_live_call=False`) only ever draws from the general pool -
    it can never touch the reserved pool, so it can never fully occupy every
    slot and starve a live caller.

    Live calls first try the general pool *non-blocking* - if a slot is
    already free there, use it rather than needlessly camping the reserved
    slot while general capacity sits idle. Only if the general pool is fully
    occupied (e.g. by ingestion jobs) does a live call fall back to the
    reserved pool, which - because ingestion never draws from it - is
    guaranteed to become available without waiting on ingestion's backlog.
    """
    if is_live_call:
        if _general_semaphore.acquire(blocking=False):
            sem = _general_semaphore
        else:
            _live_reserved_semaphore.acquire()
            sem = _live_reserved_semaphore
    else:
        _general_semaphore.acquire()
        sem = _general_semaphore
    try:
        yield
    finally:
        sem.release()


def get_model() -> WhisperModel:
    global _model
    if _model is not None:
        return _model
    with _model_lock:
        if _model is None:
            _model = WhisperModel(
                MODEL_SIZE,
                device="cpu",
                compute_type="int8",
                cpu_threads=_CPU_THREADS,
                num_workers=_NUM_WORKERS,
            )
    return _model


def transcribe_audio(filename: str, content: bytes, is_live_call: bool = False) -> str:
    """
    `is_live_call` defaults to False (today's ingestion-job behavior,
    unchanged) since no caller currently has a way to signal otherwise.
    Wiring an actual live-call signal end to end is a caller-side follow-up
    (see this session's coordination-doc entry) - this parameter and the
    reserved-pool split above are the sidecar-side half of that fix and are
    inert until something upstream starts passing `is_live_call=True`.
    """
    suffix = _suffix_of(filename)
    try:
        with tempfile.NamedTemporaryFile(suffix=suffix) as tmp:
            tmp.write(content)
            tmp.flush()
            model = get_model()
            with _acquire_inference_slot(is_live_call):
                # vad_filter=True trims silence/non-speech before it ever
                # reaches the model - cheap (a lightweight local VAD pass,
                # not another network call) and directly cuts CPU-seconds
                # per request, on top of whatever upstream VAD (the voice
                # agent's Silero gate) already trimmed. Safe for the
                # multilingual "base" model - this does not swap model size
                # (an English-only tiny/small.en model would regress the
                # platform's 22+-language support, a real product
                # regression not worth the marginal extra speed).
                segments, _info = model.transcribe(tmp.name, vad_filter=True)
                text = " ".join(segment.text.strip() for segment in segments)
    except ValueError:
        raise
    except Exception as e:
        raise ValueError(f"Could not transcribe audio: {e}") from e
    return text.strip()


def _suffix_of(filename: str) -> str:
    lowered = filename.lower()
    for ext in _SUPPORTED_EXTENSIONS:
        if lowered.endswith(ext):
            return ext
    raise ValueError(f"Unsupported audio extension for transcription: {filename}")


def transcribe_words(
    filename: str,
    content: bytes,
    *,
    language: str | None = None,
    initial_prompt: str | None = None,
) -> list[dict]:
    """Word-level timings for caption generation (Video Pipeline Stage 2).

    Additive sibling of transcribe_audio above, NOT a change to it:
    KnowledgeIngestionJobRunner on the Java side calls that function and
    deserializes its `{"text": ...}` response, so its signature and return
    type stay exactly as they were.

    Two deliberate differences from transcribe_audio:

    - `word_timestamps=True`. faster-whisper only populates
      `segment.words` when asked; the ingestion path throws the timings
      away, so it never asks. Captions are the timings.
    - `vad_filter=False`. The VAD gate is a real CPU saving on arbitrary
      uploaded audio and that is why transcribe_audio keeps it. Here the
      input is audio this service synthesized itself seconds earlier -
      studio-clean, no leading silence to trim, so the gate saves almost
      nothing - and every timestamp it produces has to be mapped back
      through the removed spans before it means anything against the
      original file. Captions are the one caller that cannot absorb a
      timestamp-mapping error, so the gate is off rather than trusted.

    `initial_prompt` is a vocabulary hint, and it earns its place - the first
    real run of the video pipeline put "then FML assembles it" on screen
    because Whisper heard "ffmpeg" that way, and "The narration comes from
    several" for "Sarvam". Captions are generated FROM the audio, so a
    mis-HEARING (as opposed to a mis-pronunciation) is a wrong caption over
    correct speech, and proper nouns and technical terms are exactly where
    that happens. Whisper conditions on this text as though it preceded the
    audio, which biases it toward spelling those words correctly.

    It is a hint, not a constraint: a term not in the prompt still
    transcribes normally, and a wrong hint does not force a wrong word.

    Returns one dict per word: {"word", "start", "end"} in seconds from the
    start of the file. Whisper emits words with leading spaces; they are
    stripped here so the caller never has to.
    """
    suffix = _suffix_of(filename)
    words: list[dict] = []
    try:
        with tempfile.NamedTemporaryFile(suffix=suffix) as tmp:
            tmp.write(content)
            tmp.flush()
            model = get_model()
            with _acquire_inference_slot(False):
                segments, _info = model.transcribe(
                    tmp.name,
                    language=language,
                    word_timestamps=True,
                    vad_filter=False,
                    initial_prompt=initial_prompt or None,
                )
                for segment in segments:
                    for word in getattr(segment, "words", None) or []:
                        text = (word.word or "").strip()
                        if not text:
                            continue
                        words.append(
                            {"word": text, "start": float(word.start), "end": float(word.end)}
                        )
    except ValueError:
        raise
    except Exception as e:
        raise ValueError(f"Could not transcribe audio for captions: {e}") from e
    return words
