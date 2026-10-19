"""
ffmpeg video assembly (Video Pipeline Stage 3).

The one genuinely new capability in the video pipeline: nothing in this
platform stitched clips + audio + captions into an mp4 before this file.
The sidecar is the right home anyway - it is already the CPU-only Python
media service, it already owns transcription.py, and PyAV (bundled with
faster-whisper) already puts the ffmpeg *libraries* in this image. What it
did NOT have is the ffmpeg *binary*, which is what this module shells out
to; see the Dockerfile's apt-get layer, which now installs it for exactly
the same reason it installs tesseract-ocr.

Four design decisions worth stating, because each one is a trade and not
an obvious default:

**1. Asynchronous, unlike every other endpoint here.** /embed, /extract,
/rerank and /transcribe are all synchronous `def`s, and /transcribe's own
docstring explains that the Java side (KnowledgeIngestionJobRunner) owns
the job queue and calls this service synchronously even for slow work.
That pattern does not survive a multi-minute render: the call would have
to hold a connection open through core's RestClient, through whatever
timeout the workflow engine applies to a step, and - on a real deployment
- through Caddy and Cloudflare, one of which will cut it. So /render
returns a job id immediately and the caller polls GET /render/{job_id},
following code-intel/app/main.py's `background=true` precedent rather
than inventing a job protocol.

**2. Single-threaded x264, by default.** `RENDER_THREADS=1` does two jobs
at once. It bounds a render to roughly one core, which is what keeps a
render from pushing /embed's p95 out of band on a 2-vCPU container - the
whole point of capping concurrency at 1 in the first place. And x264 is
only bit-exact for a fixed thread count, so it is also what makes
"render the same edit_list twice, get the same bytes" true rather than
aspirational. Raise it if renders are the bottleneck and you accept
both costs; do not raise it and then report a determinism failure.

**3. Metadata stripped, bitexact flags set.** ffmpeg stamps an encoder
string and a creation_time into the container by default, which alone
makes two identical renders differ. `-map_metadata -1` plus the bitexact
flags remove that, so byte-identical output is achievable instead of
merely duration-identical.

**4. Each beat's clip is looped-and-trimmed to its AUDIO duration.** The
durations come from the caller, which gets them from tts.synthesize's
measured values - not from episode.json's LLM-estimated `duration_sec`.
A 4-second clip under a 6-second narration beat loops rather than
freezing on a last frame or letting the audio run past the video; a
12-second clip is cut at 6. This is what keeps A/V sync true at the END
of a 90-second file, which is where drift actually shows up.

The module is split by responsibility: this file owns the job registry
and the render orchestration; render_fetch.py materializes inputs (and is
the SSRF boundary for clip URLs), render_ffmpeg.py builds every ffmpeg
argv, render_ass.py builds the caption document, and render_callback.py
owns the completion push.

Deliberately not built here, per the build plan's own scope line: no
music bed, no motion graphics, no B-roll auto-selection, no intro/outro
cards. One beat, one clip, in order, captions burned in.
"""

from __future__ import annotations

import base64
import logging
import os
import shutil
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field

from . import render_ffmpeg as ffmpeg
from .render_callback import notify, validate_callback
from .render_errors import RenderError
from .render_fetch import clip_extension, materialize

log = logging.getLogger("sidecar.render")

# The sidecar had no volume at all until this feature (the Dockerfile's
# non-root-user comment cites that as why the switch was safe). /app/workspace
# is created and chowned to `sidecar` at build time so the mount works
# whether or not an operator remembers to add the compose volume - without
# the chown this is the same ownership trap code-intel's /data mount hit.
WORKSPACE = os.environ.get("RENDER_WORKSPACE_DIR", "/app/workspace")

# Finished jobs are kept so the poller can read the outcome, but not
# forever: this registry is in-process memory and the workspace is disk.
RETAIN_JOBS = int(os.environ.get("RENDER_RETAIN_JOBS", "20"))


@dataclass
class RenderJob:
    job_id: str
    status: str = "queued"  # queued | running | completed | failed
    detail: str = ""
    created_at: float = field(default_factory=time.time)
    started_at: float | None = None
    finished_at: float | None = None
    error: str | None = None
    result: dict | None = None

    def snapshot(self) -> dict:
        return {
            "job_id": self.job_id,
            "status": self.status,
            "detail": self.detail,
            "created_at": self.created_at,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "error": self.error,
            "result": self.result,
        }


_jobs: dict[str, RenderJob] = {}
_jobs_lock = threading.Lock()
# max_workers=1 is the concurrency cap from the build plan, expressed as the
# only thing that actually enforces it. A semaphore would let N requests
# queue up inside N held threads; a single-worker pool queues the WORK and
# leaves Starlette's thread pool free to keep serving /embed.
_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="render")


def _register(job: RenderJob) -> None:
    with _jobs_lock:
        _jobs[job.job_id] = job
        finished = sorted(
            (j for j in _jobs.values() if j.status in ("completed", "failed")),
            key=lambda j: j.finished_at or 0.0,
        )
        for stale in finished[: max(0, len(finished) - RETAIN_JOBS)]:
            _jobs.pop(stale.job_id, None)
            shutil.rmtree(os.path.join(WORKSPACE, stale.job_id), ignore_errors=True)


def get_job(job_id: str) -> dict | None:
    with _jobs_lock:
        job = _jobs.get(job_id)
        return job.snapshot() if job else None


def artifact_path(job_id: str, kind: str) -> str:
    """Absolute path of a finished job's artifact, or raise RenderError."""
    job = get_job(job_id)
    if job is None:
        raise RenderError(f"no such render job: {job_id}")
    if job["status"] != "completed":
        raise RenderError(f"render job {job_id} is {job['status']}, not completed")
    key = {"video": "video_path", "thumbnail": "thumbnail_path"}.get(kind)
    if key is None:
        raise RenderError(f"unknown artifact kind: {kind} (expected 'video' or 'thumbnail')")
    path = os.path.join(WORKSPACE, (job["result"] or {})[key])
    if not os.path.isfile(path):
        raise RenderError(f"artifact {kind} for job {job_id} is no longer on disk")
    return path


def _write_captions(request: dict, job_dir: str, width: int, height: int) -> str | None:
    """Write the request's SRT and its ASS conversion; return the ASS path."""
    if not (request.get("burn_captions", True) and (request.get("srt") or "").strip()):
        return None
    # The SRT is kept alongside the ASS deliberately: it is what gets
    # uploaded to YouTube as a real caption track, and it is the thing a
    # human can read when a caption looks wrong.
    raw_srt_path = os.path.join(job_dir, "captions.srt")
    with open(raw_srt_path, "w", encoding="utf-8") as handle:
        handle.write(request["srt"])
    ass_path = os.path.join(job_dir, "captions.ass")
    ffmpeg.convert_srt_to_ass(raw_srt_path, ass_path, width, height)
    return ass_path


def _materialize_clips(
    job: RenderJob, edit_list: list, job_dir: str
) -> tuple[list[str], list[float]]:
    """Put every beat's clip on disk; return (paths, measured durations)."""
    clip_paths: list[str] = []
    durations: list[float] = []
    for index, entry in enumerate(edit_list):
        beat_id = entry.get("beat_id") or entry.get("id") or f"#{index}"
        job.detail = f"materializing clip for beat {beat_id} ({index + 1}/{len(edit_list)})"
        source = entry.get("clip")
        if not isinstance(source, dict) or not source:
            # This is DoD item 3: a missing clip must name the beat.
            raise RenderError(f"beat {beat_id} has no clip source - cannot render")
        try:
            path = materialize(
                source,
                os.path.join(job_dir, f"clip_{index:03d}{clip_extension(source)}"),
                what=f"beat {beat_id} clip",
                workspace=WORKSPACE,
            )
        except RenderError as e:
            raise RenderError(f"beat {beat_id}: {e}") from e
        duration = float(entry.get("duration_sec") or 0)
        if duration <= 0:
            raise RenderError(
                f"beat {beat_id} has duration_sec={entry.get('duration_sec')!r} - "
                f"it must be the MEASURED audio duration from /tts, not an estimate"
            )
        clip_paths.append(path)
        durations.append(duration)
    return clip_paths, durations


def _render(job: RenderJob, request: dict, job_dir: str) -> dict:
    width = int(request.get("width", 1080))
    height = int(request.get("height", 1920))
    fps = int(request.get("fps", 30))
    edit_list = request.get("edit_list") or []
    if not edit_list:
        raise RenderError("edit_list is empty - nothing to render")

    job.detail = "materializing audio"
    audio_path = materialize(
        request["audio"], os.path.join(job_dir, "narration.wav"), what="audio", workspace=WORKSPACE
    )
    audio_duration = ffmpeg.probe_duration(audio_path)
    ass_path = _write_captions(request, job_dir, width, height)
    clip_paths, durations = _materialize_clips(job, edit_list, job_dir)

    total_video = sum(durations)
    # A warning, not an error: the caller may deliberately be rendering a
    # sub-range, and refusing here would turn a cosmetic mismatch into a
    # failed episode. But a large gap means the beats and the audio came
    # from different runs, which is worth seeing in the run log.
    if abs(total_video - audio_duration) > 1.0:
        log.warning(
            "edit_list totals %.2fs but the narration track is %.2fs - beats and audio "
            "may be from different runs",
            total_video,
            audio_duration,
        )

    job.detail = f"encoding {total_video:.1f}s at {width}x{height}"
    output = os.path.join(job_dir, "episode.mp4")
    ffmpeg.encode(
        clip_paths,
        durations,
        audio_path,
        output,
        width=width,
        height=height,
        fps=fps,
        ass_path=ass_path,
    )

    job.detail = "extracting thumbnail"
    thumbnail = os.path.join(job_dir, "thumbnail.jpg")
    ffmpeg.extract_thumbnail(output, thumbnail)

    rendered_duration = ffmpeg.probe_duration(output)
    result = {
        "video_path": os.path.relpath(output, WORKSPACE),
        "thumbnail_path": os.path.relpath(thumbnail, WORKSPACE),
        "duration_sec": rendered_duration,
        "audio_duration_sec": audio_duration,
        "edit_list_duration_sec": total_video,
        "width": width,
        "height": height,
        "fps": fps,
        "video_bytes": os.path.getsize(output),
        "beats": len(clip_paths),
    }
    if request.get("return_base64"):
        with open(output, "rb") as handle:
            result["video_base64"] = base64.b64encode(handle.read()).decode("ascii")
    return result


def _run_job(job: RenderJob, request: dict) -> None:
    job.status = "running"
    job.started_at = time.time()
    job_dir = os.path.join(WORKSPACE, job.job_id)
    try:
        os.makedirs(job_dir, exist_ok=True)
        job.result = _render(job, request, job_dir)
        job.status = "completed"
        job.detail = "done"
    except RenderError as e:
        job.status = "failed"
        job.error = str(e)
        job.detail = "failed"
        log.warning("render job %s failed: %s", job.job_id, e)
    except Exception as e:
        # Deliberately broad: this runs on the render pool's own thread, so
        # an escaping exception would leave the job stuck 'running' forever
        # with nothing in the response to say why.
        job.status = "failed"
        job.error = f"unexpected render failure: {e}"
        job.detail = "failed"
        log.exception("render job %s crashed", job.job_id)
    finally:
        job.finished_at = time.time()
        notify(job, request)


def submit(request: dict) -> dict:
    """Queue a render. Returns the job snapshot immediately."""
    if request.get("callback_url"):
        validate_callback(request["callback_url"])
    os.makedirs(WORKSPACE, exist_ok=True)
    job = RenderJob(job_id=uuid.uuid4().hex)
    _register(job)
    _executor.submit(_run_job, job, request)
    return job.snapshot()


def queue_depth() -> int:
    with _jobs_lock:
        return sum(1 for j in _jobs.values() if j.status in ("queued", "running"))
