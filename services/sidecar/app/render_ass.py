"""
Caption geometry and the ASS document the render burns in (see render.py).

Kept apart from the ffmpeg driving code because it is pure text: no
process, no disk, and the place the caption-sizing regression lives.
"""

from __future__ import annotations


def caption_style(width: int, height: int) -> dict:
    """Caption geometry in REAL OUTPUT PIXELS, derived from the frame.

    Fixed point sizes do not survive this endpoint, which renders whatever
    aspect the caller asks for. 2.8% of height is ~54px on 1080x1920 vertical
    (comfortably readable on a phone) and ~30px on a 1920x1080 landscape
    render. MarginV at 6% keeps the text clear of a platform's own
    bottom-edge UI. The floors stop a deliberately tiny render asking for 4px
    type.
    """
    font_size = max(20, round(height * 0.028))
    return {
        "font_size": font_size,
        "margin_v": max(24, round(height * 0.06)),
        "margin_h": max(16, round(width * 0.04)),
        "outline": max(2, round(font_size / 18)),
    }


def build_ass(srt_or_ass_text: str, width: int, height: int) -> str:
    """Force an explicit script resolution and one known style onto an ASS doc.

    THIS FUNCTION EXISTS BECAUSE OF A BUG TWO REAL RENDERS FOUND AND NO UNIT
    TEST COULD, and the history is worth keeping because the wrong fix looked
    right:

    libass lays text out in SSA *script* units and scales the result to the
    frame. An SRT has no script header at all, so ffmpeg's SRT->ASS conversion
    supplies a default PlayResY of 288 - and every Fontsize and MarginV then
    gets multiplied by height/288, which on a 1920-tall frame is 6.67x.
    Measured: `Fontsize=18` rendered at ~120px and `MarginV=90` put the text
    ~600px up the frame instead of 90px from the bottom, both off by exactly
    that factor.

    The obvious fix - passing `original_size` to the `subtitles` filter - does
    NOT work, and a frame grab is what proved it: that option corrects ASPECT
    RATIO arithmetic, not script resolution, so the text stayed 6.67x too big
    and simply overflowed the top of the frame instead.

    What does work is removing the implicit number entirely: set PlayResX and
    PlayResY to the actual output frame, so a Fontsize of 54 means 54 pixels,
    and hand the result to the `ass` filter (which, unlike `subtitles`, applies
    no style overrides of its own). There is then no magic constant anywhere -
    nothing to be wrong when the frame size changes.
    """
    style = caption_style(width, height)
    lines = srt_or_ass_text.splitlines()

    # Rebuild [Script Info] rather than editing it: the converter's version
    # may or may not contain PlayRes keys depending on ffmpeg version, and a
    # "replace if present, else insert" would be two code paths to get wrong.
    script_info = [
        "[Script Info]",
        "ScriptType: v4.00+",
        f"PlayResX: {width}",
        f"PlayResY: {height}",
        "WrapStyle: 0",
        "ScaledBorderAndShadow: yes",
        "",
    ]
    # BorderStyle 3 is an opaque box behind the text rather than an outline -
    # the only option that stays readable over arbitrary generated B-roll,
    # which can be any colour at any moment. &H90000000 is ~44% black.
    styles = [
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, "
        "BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, "
        "BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
        f"Style: Default,DejaVu Sans,{style['font_size']},&H00FFFFFF,&H000000FF,"
        f"&H90000000,&H00000000,0,0,0,0,100,100,0,0,3,{style['outline']},0,2,"
        f"{style['margin_h']},{style['margin_h']},{style['margin_v']},1",
        "",
    ]

    # Keep every Dialogue line, but force each onto the Default style - the
    # converter names its style "Default" already, yet an ASS handed in
    # directly may reference styles this header no longer defines, and libass
    # silently falls back to something else when it cannot resolve one.
    events = [
        "[Events]",
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
    ]
    for line in lines:
        if not line.startswith("Dialogue:"):
            continue
        fields = line[len("Dialogue:") :].split(",", 9)
        if len(fields) < 10:
            continue
        fields[3] = "Default"
        events.append("Dialogue:" + ",".join(fields))
    return "\n".join(script_info + styles + events) + "\n"
