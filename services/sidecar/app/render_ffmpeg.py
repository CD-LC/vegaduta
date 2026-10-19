"""
Driving the ffmpeg/ffprobe binaries for the render pipeline (see render.py).

Everything that builds an ffmpeg argv lives here: the per-input safety
prefix (SEC-031), the subprocess wrapper, the SRT->ASS conversion, the
filter graph, the encode itself and the thumbnail grab. render.py decides
WHAT to render; this module decides how ffmpeg is told to do it.
"""

from __future__ import annotations

import logging
import os
import subprocess

from .render_ass import build_ass
from .render_errors import RenderError

log = logging.getLogger("sidecar.render")

FFMPEG = os.environ.get("FFMPEG_BINARY", "ffmpeg")
FFPROBE = os.environ.get("FFPROBE_BINARY", "ffprobe")

RENDER_THREADS = os.environ.get("RENDER_THREADS", "1")
RENDER_PRESET = os.environ.get("RENDER_PRESET", "medium")
RENDER_CRF = os.environ.get("RENDER_CRF", "23")
RENDER_TIMEOUT_S = float(os.environ.get("RENDER_TIMEOUT_S", "3600"))

# SEC-031 (2026-09-30): every ffmpeg/ffprobe input here is attacker-supplied
# media (tenant URL, workspace file or base64). ffmpeg picks a demuxer by
# probing CONTENT, not the extension, so a "clip" that is really an HLS or
# concat playlist (or an image2 pattern, a subfile/tee/crypto URL...) would
# make the sidecar open further URLs itself - an SSRF from inside the
# network, e.g. a GET to code-intel's Docker socket proxy - that the fetch
# guard in render_fetch.py never sees. Two per-input limits close that:
#   -protocol_whitelist file   the input may only be read from local disk:
#                              no http/tcp/udp/rtmp/concat/subfile/data/crypto,
#                              including for anything a demuxer opens itself;
#   -format_whitelist <list>   the demuxer must be one of the named ones, so
#                              hls/concat/image2/ffconcat/playlist-style
#                              demuxers are refused outright.
# Rather than forcing one `-f` (clips legitimately arrive as mp4/mov/webm/
# mkv/avi), each input kind gets an explicit demuxer allow-list. Names match
# ffmpeg's demuxer names: "mov" is the mp4/mov/m4a/3gp family, "matroska" is
# mkv and webm.
CLIP_DEMUXERS = "mov,matroska,avi"
AUDIO_DEMUXERS = "wav,mp3,mov,ogg,flac,aac,matroska"
_SUBTITLE_DEMUXERS = "srt"
_PROBE_DEMUXERS = ",".join(dict.fromkeys(f"{CLIP_DEMUXERS},{AUDIO_DEMUXERS}".split(",")))


def safe_input(path: str, demuxers: str) -> list[str]:
    """The input-option prefix every untrusted ffmpeg/ffprobe input gets."""
    return ["-protocol_whitelist", "file", "-format_whitelist", demuxers, "-i", path]


def run(argv: list[str], *, what: str, timeout: float) -> str:
    log.info("%s: %s", what, " ".join(argv))
    try:
        # Fixed binary from FFMPEG/FFPROBE, argv list, shell=False - no
        # caller-supplied string is ever parsed by a shell.
        completed = subprocess.run(
            argv,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError as e:
        raise RenderError(
            f"{argv[0]} is not installed in this image - the sidecar Dockerfile's "
            f"apt-get layer is what provides it"
        ) from e
    except subprocess.TimeoutExpired as e:
        raise RenderError(f"{what} exceeded RENDER_TIMEOUT_S ({timeout}s)") from e
    if completed.returncode != 0:
        # ffmpeg's real complaint is the last few lines of stderr; the first
        # 30 are build configuration nobody needs in a workflow run log.
        tail = "\n".join((completed.stderr or "").strip().splitlines()[-12:])
        raise RenderError(f"{what} failed (exit {completed.returncode}):\n{tail}")
    return completed.stdout


def convert_srt_to_ass(srt_path: str, ass_path: str, width: int, height: int) -> None:
    """SRT -> ASS via ffmpeg, then force the resolution and style.

    ffmpeg does the timecode parsing rather than this module: SRT is more
    irregular in the wild than its format suggests (BOMs, CRLF, blank cue
    text, overlapping indices), and a hand-rolled parser here would be a
    second place for caption bugs to live.
    """
    intermediate = ass_path + ".raw.ass"
    run(
        [
            FFMPEG,
            "-nostdin",
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            *safe_input(srt_path, _SUBTITLE_DEMUXERS),
            intermediate,
        ],
        what="ffmpeg srt->ass",
        timeout=120,
    )
    with open(intermediate, encoding="utf-8", errors="replace") as handle:
        converted = handle.read()
    with open(ass_path, "w", encoding="utf-8") as handle:
        handle.write(build_ass(converted, width, height))


def probe_duration(path: str) -> float:
    out = run(
        [
            FFPROBE,
            "-v",
            "error",
            "-protocol_whitelist",
            "file",
            "-format_whitelist",
            _PROBE_DEMUXERS,
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            path,
        ],
        what="ffprobe duration",
        timeout=60,
    )
    try:
        return float(out.strip())
    except ValueError as e:
        raise RenderError(f"ffprobe returned no usable duration for {path}") from e


def _escape_filter_path(path: str) -> str:
    """Escape a path for use inside an ffmpeg filter argument.

    The subtitles filter takes its filename as a filter option, so the path
    crosses two levels of ffmpeg parsing: `\\` `:` `'` `[` `]` `,` all have
    meaning. Job directories are uuid-named so this never actually fires
    today - it is here so that it keeps not firing when someone later
    passes a caller-supplied filename through.
    """
    out = path.replace("\\", "\\\\")
    for char in (":", "'", "[", "]", ",", ";"):
        out = out.replace(char, "\\" + char)
    return out


def build_filter(count: int, width: int, height: int, fps: int, srt_path: str | None) -> str:
    chains = []
    for index in range(count):
        chains.append(
            f"[{index}:v]"
            f"scale={width}:{height}:force_original_aspect_ratio=decrease,"
            f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color=black,"
            f"setsar=1,fps={fps},format=yuv420p"
            f"[v{index}]"
        )
    concat_inputs = "".join(f"[v{i}]" for i in range(count))
    chains.append(f"{concat_inputs}concat=n={count}:v=1:a=0[vcat]")
    if srt_path:
        # `ass=`, not `subtitles=`, and the difference is the whole reason
        # captions are legible. See render_ass.build_ass - this was measured
        # on a real render, twice, not reasoned about.
        chains.append(f"[vcat]ass=filename='{_escape_filter_path(srt_path)}'[vout]")
    else:
        chains.append("[vcat]null[vout]")
    return ";".join(chains)


def encode(
    clip_paths: list[str],
    durations: list[float],
    audio_path: str,
    output: str,
    *,
    width: int,
    height: int,
    fps: int,
    ass_path: str | None,
) -> None:
    """Concatenate the beat clips under the narration into `output` (mp4)."""
    argv = [FFMPEG, "-nostdin", "-y", "-hide_banner", "-loglevel", "error"]
    for path, duration in zip(clip_paths, durations, strict=True):
        # -stream_loop -1 with -t before -i: loop the clip forever, read
        # exactly `duration` seconds of it. Both are INPUT options, so the
        # order matters - after -i they would apply to the output instead.
        argv += ["-stream_loop", "-1", "-t", f"{duration:.6f}", *safe_input(path, CLIP_DEMUXERS)]
    audio_index = len(clip_paths)
    argv += safe_input(audio_path, AUDIO_DEMUXERS)
    argv += [
        "-filter_complex",
        build_filter(len(clip_paths), width, height, fps, ass_path),
        "-map",
        "[vout]",
        "-map",
        f"{audio_index}:a",
        "-c:v",
        "libx264",
        "-preset",
        RENDER_PRESET,
        "-crf",
        RENDER_CRF,
        "-pix_fmt",
        "yuv420p",
        "-threads",
        RENDER_THREADS,
        "-c:a",
        "aac",
        "-b:a",
        "160k",
        "-ar",
        "48000",
        "-ac",
        "2",
        "-movflags",
        "+faststart",
        # Determinism: strip the encoder string and creation_time, and ask
        # both muxer and codecs for bitexact output. See render.py's module
        # header - byte-identical also needs RENDER_THREADS=1.
        "-map_metadata",
        "-1",
        "-fflags",
        "+bitexact",
        "-flags:v",
        "+bitexact",
        "-flags:a",
        "+bitexact",
        "-shortest",
        output,
    ]
    run(argv, what="ffmpeg render", timeout=RENDER_TIMEOUT_S)


def extract_thumbnail(video: str, thumbnail: str) -> None:
    """Grab one JPEG frame of `video` into `thumbnail`."""
    # A frame from 1s in, not 0s: the very first frame of a generated clip is
    # frequently a fade-in from black, which makes a useless thumbnail.
    run(
        [
            FFMPEG,
            "-nostdin",
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-ss",
            "1",
            # Our own encoder output, but it goes through the same gate: the
            # rule is simply that no ffmpeg input here is unrestricted.
            *safe_input(video, "mov"),
            "-frames:v",
            "1",
            "-q:v",
            "2",
            thumbnail,
        ],
        what="ffmpeg thumbnail",
        timeout=300,
    )
