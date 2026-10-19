#!/usr/bin/env python3
"""Build the self-hosted woff2 fonts that web/ loads with next/font/local.

The apps used next/font/google, which downloads every font from Google at `next build` time, so
a build failed whenever fonts.gstatic.com was unreachable. This script produces the same coverage
as committed files instead. Run it only to add a family or change subsets or weights; the outputs
are committed and the build never fetches fonts.

For each family it:
  1. downloads the upstream TTF from github.com/google/fonts at the pinned commit (OFL-1.1),
  2. reads Google Fonts' own unicode-range for each requested subset (the ranges next/font/google
     would have served),
  3. limits a variable font's weight axis to the weights the app uses (and pins any other axis to
     its default),
  4. subsets to the union of those ranges with every OpenType layout feature kept (Devanagari
     shaping depends on them) and writes one woff2 per face.

next/font/local has no per-file unicode-range, so one file per face covers all of the face's
subsets, unlike next/font/google's one file per subset.

Usage (needs network; fontTools and brotli):
    python3 -m pip install 'fonttools==4.60.1' 'brotli==1.1.0'
    python3 scripts/dev/build-web-fonts.py
"""

from __future__ import annotations

import io
import re
import urllib.request
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

GOOGLE_FONTS_COMMIT = "bd8f81ddb5c74d5c8897b36ad88b440266245103"
RAW = f"https://raw.githubusercontent.com/google/fonts/{GOOGLE_FONTS_COMMIT}/ofl"
CSS_API = "https://fonts.googleapis.com/css2"
# A woff2-capable browser, so the CSS API returns per-subset unicode-range blocks.
USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/130.0 Safari/537.36"
)
REPO = Path(__file__).resolve().parents[2]

# (output file, upstream path, Google family name, subsets, weights). A weight range on a
# variable font becomes its wght axis limits; a single weight on a static font is that face.
FONTS = {
    "web/app/fonts": [
        ("geist.woff2", "geist/Geist[wght].ttf", "Geist", ["latin"], (400, 700)),
        (
            "geist-mono.woff2",
            "geistmono/GeistMono[wght].ttf",
            "Geist Mono",
            ["latin"],
            (400, 600),
        ),
        (
            "noto-sans-devanagari.woff2",
            "notosansdevanagari/NotoSansDevanagari[wdth,wght].ttf",
            "Noto Sans Devanagari",
            ["devanagari", "latin"],
            (400, 600),
        ),
        (
            "yatra-one.woff2",
            "yatraone/YatraOne-Regular.ttf",
            "Yatra One",
            ["latin", "latin-ext"],
            (400, 400),
        ),
        (
            "plus-jakarta-sans.woff2",
            "plusjakartasans/PlusJakartaSans[wght].ttf",
            "Plus Jakarta Sans",
            ["latin"],
            (400, 700),
        ),
        (
            "dm-mono-400.woff2",
            "dmmono/DMMono-Regular.ttf",
            "DM Mono",
            ["latin"],
            (400, 400),
        ),
        (
            "dm-mono-500.woff2",
            "dmmono/DMMono-Medium.ttf",
            "DM Mono",
            ["latin"],
            (500, 500),
        ),
    ],
        ("geist.woff2", "geist/Geist[wght].ttf", "Geist", ["latin"], (400, 700)),
        (
            "geist-mono.woff2",
            "geistmono/GeistMono[wght].ttf",
            "Geist Mono",
            ["latin"],
            (400, 600),
        ),
        (
            "yatra-one.woff2",
            "yatraone/YatraOne-Regular.ttf",
            "Yatra One",
            ["latin", "latin-ext"],
            (400, 400),
        ),
    ],
}


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:  # noqa: S310 - fixed https hosts
        return response.read()


def subset_ranges(family: str, weight: int) -> dict[str, str]:
    """Google Fonts' unicode-range per subset for one family, from its CSS API."""
    query = f"family={family.replace(' ', '+')}:wght@{weight}&display=swap"
    css = fetch(f"{CSS_API}?{query}").decode()
    return dict(
        re.findall(
            r"/\* ([\w-]+) \*/\s*@font-face \{[^}]*?unicode-range: ([^;]+);", css
        )
    )


def build(
    out_dir: Path,
    name: str,
    upstream: str,
    family: str,
    subsets: list[str],
    weights: tuple[int, int],
) -> None:
    ranges = subset_ranges(family, weights[0])
    missing = [s for s in subsets if s not in ranges]
    if missing:
        raise SystemExit(
            f"{family}: Google Fonts serves no {missing} subset (has {sorted(ranges)})"
        )
    unicodes = ",".join(ranges[s] for s in subsets).replace("U+", "").replace(" ", "")

    # recalcTimestamp=False keeps upstream head.modified, so a rebuild is byte-identical.
    font = TTFont(io.BytesIO(fetch(f"{RAW}/{upstream}")), recalcTimestamp=False)
    if "fvar" in font:
        limits = {}
        for axis in font["fvar"].axes:
            if axis.axisTag == "wght":
                limits["wght"] = weights if weights[0] != weights[1] else weights[0]
            else:
                limits[axis.axisTag] = axis.defaultValue
        font = instancer.instantiateVariableFont(font, limits)
        # Round-trip: the instancer leaves lazily loaded tables (gvar) that the subsetter cannot
        # walk, which fails with KeyError '.notdef'.
        buffer = io.BytesIO()
        font.save(buffer)
        font = TTFont(io.BytesIO(buffer.getvalue()), recalcTimestamp=False)

    options = subset.Options()
    options.flavor = "woff2"
    options.layout_features = ["*"]
    options.name_IDs = ["*"]  # keep the copyright and license records the OFL requires
    options.notdef_outline = True
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=subset.parse_unicodes(unicodes))
    subsetter.subset(font)

    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / name
    font.flavor = "woff2"
    font.save(path)
    print(
        f"{path.relative_to(REPO)}  {path.stat().st_size // 1024} KiB  {family} {subsets} {weights}"
    )


def main() -> None:
    for folder, faces in FONTS.items():
        for face in faces:
            build(REPO / folder, *face)


if __name__ == "__main__":
    main()
