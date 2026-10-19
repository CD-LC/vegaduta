"""Symbol-aware chunking.

Primary engine: tree-sitter (via tree-sitter-language-pack, ~165 grammars,
covering every major language). Files are cut along definition boundaries
(functions, methods, classes, structs, impls, ...), oversized definitions
are split, and tiny fragments are merged with their neighbors. Each chunk
carries the enclosing symbol chain (e.g. "class UserService > def create").

Fallback engine (tree-sitter not installed, or no grammar for a language):
regex definition boundaries per language family, or fixed-size line windows
with overlap for anything unrecognized. The tool therefore always works;
installing tree-sitter-language-pack upgrades chunk quality.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from functools import cache

from .config import Config
from .languages import (
    DEFINITION_TYPE_FRAGMENTS,
    FALLBACK_DEF_PATTERNS,
    ts_grammar_for,
)


@dataclass
class Chunk:
    path: str
    language: str
    index: int
    symbol: str           # enclosing symbol chain, "" for module-level code
    start_line: int       # 1-based, inclusive
    end_line: int         # 1-based, inclusive
    content: str


@dataclass
class _Segment:
    start: int            # 0-based line index, inclusive
    end: int              # 0-based line index, inclusive
    symbol: str
    is_def: bool


# --------------------------------------------------------------------------
# tree-sitter engine
# --------------------------------------------------------------------------

@cache
def _get_parser(grammar: str):
    """Return a tree-sitter parser or None. Cached, including failures."""
    try:
        from tree_sitter_language_pack import get_parser  # type: ignore
    except ImportError:
        return None
    try:
        return get_parser(grammar)  # type: ignore[arg-type]
    except Exception:
        return None


def _is_definition(node) -> bool:
    t = node.type
    return any(frag in t for frag in DEFINITION_TYPE_FRAGMENTS)


def _node_symbol(node, source: bytes) -> str:
    """Best-effort symbol name for a definition node."""
    name_node = None
    try:
        name_node = node.child_by_field_name("name")
    except Exception:
        pass
    if name_node is None:
        for child in node.named_children:
            if "identifier" in child.type or child.type in ("name", "constant"):
                name_node = child
                break
    kind = node.type.split("_")[0]
    if name_node is not None:
        name = source[name_node.start_byte:name_node.end_byte].decode(
            "utf-8", errors="replace")
        return f"{kind} {name}"
    first_line = source[node.start_byte:node.end_byte].split(b"\n", 1)[0]
    return first_line.decode("utf-8", errors="replace").strip()[:80]


def _collect_segments(node, source: bytes, max_lines: int,
                      prefix: str, out: list[_Segment]) -> None:
    """Walk `node`'s children in order, emitting definition segments.
    Definitions small enough become leaf segments; oversized ones are
    descended into, with their non-definition remainder emitted around
    the nested definitions."""
    cursor_line = node.start_point[0]
    for child in node.named_children:
        c_start, c_end = child.start_point[0], child.end_point[0]
        if not _is_definition(child):
            continue
        # Gap before this definition (module statements, comments, fields)
        if c_start > cursor_line:
            out.append(_Segment(cursor_line, c_start - 1, prefix, False))
        symbol = _node_symbol(child, source)
        full = f"{prefix} > {symbol}" if prefix else symbol
        if (c_end - c_start + 1) <= max_lines:
            out.append(_Segment(c_start, c_end, full, True))
        else:
            # Oversized definition: recurse so its methods/inner defs come
            # out as their own segments, header lines attached to `full`.
            inner: list[_Segment] = []
            _collect_segments(child, source, max_lines, full, inner)
            if inner:
                if inner[0].start > c_start:
                    out.append(_Segment(c_start, inner[0].start - 1, full, False))
                out.extend(inner)
                if inner[-1].end < c_end:
                    out.append(_Segment(inner[-1].end + 1, c_end, full, False))
            else:
                out.append(_Segment(c_start, c_end, full, True))
        cursor_line = c_end + 1
    if node.end_point[0] >= cursor_line and node.named_child_count > 0:
        out.append(_Segment(cursor_line, node.end_point[0], prefix, False))


def _ts_segments(text: str, language: str, max_lines: int) -> list[_Segment] | None:
    grammar = ts_grammar_for(language)
    if grammar is None:
        return None
    parser = _get_parser(grammar)
    if parser is None:
        return None
    source = text.encode("utf-8")
    try:
        tree = parser.parse(source)
    except Exception:
        return None
    segments: list[_Segment] = []
    _collect_segments(tree.root_node, source, max_lines, "", segments)
    return _normalize(segments)


# --------------------------------------------------------------------------
# regex fallback engine
# --------------------------------------------------------------------------

def _regex_segments(lines: list[str], language: str) -> list[_Segment] | None:
    pattern = FALLBACK_DEF_PATTERNS.get(language)
    if pattern is None:
        return None
    rx = re.compile(pattern)
    boundaries = [i for i, line in enumerate(lines) if rx.match(line)]
    if not boundaries:
        return None
    segments: list[_Segment] = []
    if boundaries[0] > 0:
        segments.append(_Segment(0, boundaries[0] - 1, "", False))
    for j, b in enumerate(boundaries):
        end = (boundaries[j + 1] - 1) if j + 1 < len(boundaries) else len(lines) - 1
        symbol = lines[b].strip()[:80]
        segments.append(_Segment(b, end, symbol, True))
    return _normalize(segments)


# --------------------------------------------------------------------------
# shared packing
# --------------------------------------------------------------------------

def _normalize(segments: list[_Segment]) -> list[_Segment]:
    """Sort, drop empties/overlaps so segments tile the file monotonically."""
    segments = sorted((s for s in segments if s.end >= s.start),
                      key=lambda s: (s.start, s.end))
    out: list[_Segment] = []
    for seg in segments:
        if out and seg.start <= out[-1].end:
            seg = _Segment(out[-1].end + 1, seg.end, seg.symbol, seg.is_def)
            if seg.end < seg.start:
                continue
        out.append(seg)
    return out


def _blank_only(lines: list[str], seg: _Segment) -> bool:
    return all(not lines[i].strip() for i in range(seg.start, seg.end + 1))


def _pack(lines: list[str], segments: list[_Segment], cfg: Config,
          path: str, language: str) -> list[Chunk]:
    chunks: list[Chunk] = []
    cur: list[_Segment] = []

    def cur_lines() -> int:
        return (cur[-1].end - cur[0].start + 1) if cur else 0

    def flush() -> None:
        if not cur:
            return
        start, end = cur[0].start, cur[-1].end
        content = "\n".join(lines[start:end + 1])
        if not content.strip():
            cur.clear()
            return
        symbols = [s.symbol for s in cur if s.symbol]
        symbol = symbols[0] if symbols else ""
        chunks.append(Chunk(path, language, len(chunks), symbol,
                            start + 1, end + 1, content))
        cur.clear()

    for seg in segments:
        seg_len = seg.end - seg.start + 1
        if seg_len > cfg.max_chunk_lines:
            flush()
            _split_oversized(lines, seg, cfg, path, language, chunks)
            continue
        if cur and (cur_lines() + seg_len > cfg.max_chunk_lines):
            flush()
        if (cur and seg.is_def and seg_len >= 10
                and cur_lines() >= cfg.min_chunk_lines
                and not _blank_only(lines, cur[-1])):
            flush()
        cur.append(seg)
    flush()

    # Merge a trailing tiny chunk into its predecessor
    merged: list[Chunk] = []
    for ch in chunks:
        if (merged and (ch.end_line - ch.start_line + 1) < cfg.min_chunk_lines
                and (merged[-1].end_line - merged[-1].start_line + 1
                     + ch.end_line - ch.start_line + 1) <= cfg.max_chunk_lines
                and merged[-1].end_line + 1 == ch.start_line):
            prev = merged.pop()
            merged.append(Chunk(path, language, prev.index,
                                prev.symbol or ch.symbol,
                                prev.start_line, ch.end_line,
                                prev.content + "\n" + ch.content))
        else:
            merged.append(Chunk(path, language, len(merged), ch.symbol,
                                ch.start_line, ch.end_line, ch.content))
    for i, ch in enumerate(merged):
        ch.index = i
    return merged


def _split_oversized(lines: list[str], seg: _Segment, cfg: Config,
                     path: str, language: str, chunks: list[Chunk]) -> None:
    step = cfg.max_chunk_lines - cfg.fallback_overlap_lines
    part = 1
    pos = seg.start
    while pos <= seg.end:
        end = min(pos + cfg.max_chunk_lines - 1, seg.end)
        content = "\n".join(lines[pos:end + 1])
        if content.strip():
            symbol = f"{seg.symbol} (part {part})" if seg.symbol else ""
            chunks.append(Chunk(path, language, len(chunks), symbol,
                                pos + 1, end + 1, content))
        part += 1
        if end >= seg.end:
            break
        pos += step


def _window_segments(lines: list[str], cfg: Config) -> list[_Segment]:
    segments = []
    step = cfg.fallback_chunk_lines - cfg.fallback_overlap_lines
    pos = 0
    while pos < len(lines):
        end = min(pos + cfg.fallback_chunk_lines - 1, len(lines) - 1)
        segments.append(_Segment(pos, end, "", False))
        if end >= len(lines) - 1:
            break
        pos += step
    return segments


# --------------------------------------------------------------------------
# public API
# --------------------------------------------------------------------------

def chunk_file(cfg: Config, path: str, text: str, language: str) -> list[Chunk]:
    lines = text.split("\n")
    if not any(line.strip() for line in lines):
        return []
    segments = _ts_segments(text, language, cfg.max_chunk_lines)
    engine_used = "tree-sitter"
    if not segments:
        segments = _regex_segments(lines, language)
        engine_used = "regex"
    if not segments:
        segments = _window_segments(lines, cfg)
        engine_used = "window"
    chunks = _pack(lines, segments, cfg, path, language)
    for ch in chunks:
        ch.language = language
    chunk_file.last_engine = engine_used  # type: ignore[attr-defined]
    return chunks
