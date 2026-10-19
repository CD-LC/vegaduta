"""
SRT caption generation from word-level timings (Video Pipeline Stage 2).

Greenfield: nothing in this repo emitted SRT or VTT before this file.

The load-bearing design choice is WHERE the timings come from. Captions are
generated from the synthesized audio's own transcription, not from the
narration text that produced it. That looks like the long way round - the
text is right there - but the text has no timings in it, and any attempt to
infer them (characters-per-second, word counts, punctuation pauses) is an
estimate that drifts monotonically: a 200ms error in beat 1 is still there
in beat 12, on top of beat 12's own. Transcribing the audio gives timings
measured against the file that will actually be muxed, so caption drift is
bounded by Whisper's word alignment (tens of milliseconds) rather than by
an accumulating guess, and it stays correct after a speed/pace change
without anything here knowing a speed change happened.

The cost is that captions show what the voice actually said, including any
Sarvam mispronunciation-as-transcribed. That is the correct trade for
burned-in captions on a published video: a caption that matches the audio
is right even when the audio is wrong, and the reverse is not true.
"""
from __future__ import annotations

from itertools import pairwise

# Caption grouping. These three are the whole readability model, and the
# defaults are chosen for 1080x1920 vertical - the format Stage 3 renders.
#
# Two lines of ~32 characters is the widely-used broadcast-subtitle shape
# (BBC/Netflix house styles both land near it) and is about as much text as
# fits legibly across a 1080-wide frame at a caption size big enough to read
# on a phone. Raising MAX_CHARS is the first thing to try if captions look
# sparse on a wider aspect; lowering MAX_DURATION_S makes them feel snappier
# at the cost of more cue changes.
MAX_CHARS = 42
MAX_DURATION_S = 3.5
# A gap this long between two words ends the cue even if there is room left.
# Without it, a cue can span a two-second pause and sit on screen through
# silence, which reads as a stuck caption rather than as a pause.
MAX_GAP_S = 0.6


def _format_timestamp(seconds: float) -> str:
    """SRT wants HH:MM:SS,mmm with a comma, exactly two-digit hours."""
    if seconds < 0:
        seconds = 0.0
    total_ms = round(seconds * 1000)
    hours, remainder = divmod(total_ms, 3_600_000)
    minutes, remainder = divmod(remainder, 60_000)
    secs, millis = divmod(remainder, 1000)
    return f"{hours:02d}:{minutes:02d}:{secs:02d},{millis:03d}"


def group_words(
    words: list[dict],
    *,
    max_chars: int = MAX_CHARS,
    max_duration_s: float = MAX_DURATION_S,
    max_gap_s: float = MAX_GAP_S,
) -> list[dict]:
    """Group word timings into cues. Returns [{"start", "end", "text"}]."""
    cues: list[dict] = []
    current: list[dict] = []

    def flush() -> None:
        if not current:
            return
        cues.append(
            {
                "start": current[0]["start"],
                "end": current[-1]["end"],
                "text": " ".join(w["word"] for w in current),
            }
        )
        current.clear()

    for word in words:
        text = (word.get("word") or "").strip()
        if not text:
            continue
        start = float(word["start"])
        end = float(word["end"])
        if current:
            pending_chars = sum(len(w["word"]) for w in current) + len(current) + len(text)
            too_long = pending_chars > max_chars
            too_slow = (end - current[0]["start"]) > max_duration_s
            too_far = (start - current[-1]["end"]) > max_gap_s
            if too_long or too_slow or too_far:
                flush()
        current.append({"word": text, "start": start, "end": end})
    flush()

    # Never let two cues overlap. Whisper's word alignment can hand back an
    # end that is a hair past the next word's start; an overlapping SRT is
    # not strictly invalid but ffmpeg's subtitle filter renders both cues at
    # once, which looks like a rendering bug and is not one.
    for earlier, later in pairwise(cues):
        if earlier["end"] > later["start"]:
            earlier["end"] = later["start"]
    return [c for c in cues if c["end"] > c["start"]]


def to_srt(cues: list[dict]) -> str:
    """Render cues as an SRT document. Empty input gives an empty string."""
    blocks = []
    for index, cue in enumerate(cues, start=1):
        blocks.append(
            f"{index}\n"
            f"{_format_timestamp(cue['start'])} --> {_format_timestamp(cue['end'])}\n"
            f"{cue['text']}\n"
        )
    return "\n".join(blocks)


def words_to_srt(words: list[dict], **kwargs) -> str:
    return to_srt(group_words(words, **kwargs))


def shift_words(words: list[dict], offset_s: float) -> list[dict]:
    """Move every timing later by `offset_s`.

    Stage 2 synthesizes and transcribes one beat at a time, so each beat's
    word timings start at zero. The episode's captions need them on the
    episode timeline, which means adding the cumulative duration of every
    preceding beat - measured duration, from tts.synthesize, not the
    estimate in episode.json.
    """
    return [
        {
            "word": w["word"],
            "start": float(w["start"]) + offset_s,
            "end": float(w["end"]) + offset_s,
        }
        for w in words
    ]
