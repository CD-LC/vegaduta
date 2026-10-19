"""Per-chunk contextualization.

Three layers, cheapest first:

1. Structural (always, free): file path breadcrumb, language, enclosing
   symbol chain, line span, and the file's imports - extracted by regex per
   language family. Prepended to the chunk before embedding, so "where does
   this code live and what does it depend on" is part of the vector.

2. Context scripts (optional, per config): your own commands (ctags, a call
   graph extractor, `git log -1 --format=%s`, anything) run once per changed
   file; stdout is appended to that file's chunks' context.

3. LLM situating summary (optional, Anthropic-style contextual retrieval):
   a 1-2 sentence "how this chunk fits into the file/repo" paragraph.
   Generated ONLY for chunks whose content actually changed (cache keyed by
   chunk content hash in SQLite), so steady-state cost is near zero.
"""

from __future__ import annotations

import hashlib
import re
import shlex
import subprocess

from .chunker import Chunk
from .config import Config
from .languages import IMPORT_PATTERNS

MAX_IMPORT_LINES = 12
MAX_IMPORT_CHARS = 700


def extract_imports(text: str, language: str) -> list[str]:
    pattern = IMPORT_PATTERNS.get(language)
    if not pattern:
        return []
    rx = re.compile(pattern)
    found = []
    for line in text.split("\n")[:200]:  # imports live near the top
        if rx.match(line):
            found.append(line.strip())
            if len(found) >= MAX_IMPORT_LINES:
                break
    total = 0
    kept = []
    for imp in found:
        total += len(imp)
        if total > MAX_IMPORT_CHARS:
            break
        kept.append(imp)
    return kept


def build_script_argv(command_template: str, rel_path: str) -> list[str]:
    """Turn a configured context-script command into an argv list.

    SVC-005: `rel_path` is a repository file path, i.e. attacker-controlled
    for any tenant-supplied repo. The template used to be `.replace()`d and
    run with shell=True, so a file named `a;touch pwned.py` or `$(id).py`
    executed arbitrary shell. Now the OPERATOR-authored template is
    tokenized with shlex FIRST and `{file}` is substituted inside each
    already-split token, so a filename is always exactly (part of) one argv
    element - never extra arguments, never shell syntax - and no shell runs.

    POSIX shlex rules on every platform (quotes group, backslash escapes).
    On Windows that means a template with backslash paths must quote or
    double them; context scripts are a Linux-container feature in practice.
    Templates that need pipes/redirection must say so explicitly, e.g.
    `sh -c 'ctags -x "$1" | head' _ {file}` - the file then arrives as a
    positional parameter, not as shell source.

    A path starting with "-" (a top-level file named `--output=x`) is
    passed as `./--output=x` so the invoked tool can't parse it as an
    option - same file, different spelling.
    """
    tokens = shlex.split(command_template, posix=True)
    safe_path = "./" + rel_path if rel_path.startswith("-") else rel_path
    return [tok.replace("{file}", safe_path) for tok in tokens]


def run_context_scripts(cfg: Config, rel_path: str) -> str:
    """Run configured scripts for one file; concatenated, truncated output."""
    pieces = []
    for script in cfg.context_scripts:
        try:
            argv = build_script_argv(script.command, rel_path)
        except ValueError as e:  # unbalanced quotes in the template
            pieces.append(f"[{script.name}] (invalid command template: {e})")
            continue
        if not argv:
            continue
        try:
            # B603: argv list from build_script_argv, shell=False - the
            # filename can only ever be (part of) one argument.
            result = subprocess.run(  # nosec B603
                argv, shell=False, cwd=cfg.root, capture_output=True,
                text=True, timeout=script.timeout,
            )
            out = (result.stdout or "").strip()
            if result.returncode != 0 and not out:
                out = f"(exit {result.returncode}: {(result.stderr or '').strip()[:200]})"
        except subprocess.TimeoutExpired:
            out = f"(timed out after {script.timeout}s)"
        except OSError as e:
            out = f"(failed to run: {e})"
        if out:
            pieces.append(f"[{script.name}] {out[:script.max_output_chars]}")
    return "\n".join(pieces)


def structural_context(chunk: Chunk, imports: list[str],
                       script_output: str) -> str:
    lines = [f"File: {chunk.path} ({chunk.language}), "
             f"lines {chunk.start_line}-{chunk.end_line}"]
    if chunk.symbol:
        lines.append(f"Symbol: {chunk.symbol}")
    if imports:
        lines.append("Imports: " + "; ".join(imports))
    if script_output:
        lines.append(script_output)
    return "\n".join(lines)


def chunk_content_key(chunk: Chunk, file_hash: str) -> str:
    """Cache key for the LLM summary: survives line-number shifts (content
    only), but changes when the chunk's code or its file changes."""
    h = hashlib.sha256()
    h.update(chunk.content.encode("utf-8"))
    h.update(b"\0")
    h.update(file_hash.encode("utf-8"))
    return h.hexdigest()


class LlmContextualizer:
    """Lazy Anthropic client; caller is responsible for caching results."""

    PROMPT = (
        "<file path=\"{path}\">\n{file_text}\n</file>\n\n"
        "Here is a chunk from that file:\n<chunk>\n{chunk_text}\n</chunk>\n\n"
        "Write 1-2 sentences situating this chunk within the file for "
        "search retrieval: what it does, what it belongs to, and anything "
        "a keyword search would miss. Answer with only the sentences."
    )

    def __init__(self, cfg: Config):
        self.cfg = cfg
        self._client = None

    def _get_client(self):
        if self._client is None:
            try:
                import anthropic  # type: ignore
            except ImportError as e:
                raise RuntimeError(
                    "llm_context: true requires the 'anthropic' package "
                    "(pip install anthropic) and ANTHROPIC_API_KEY") from e
            self._client = anthropic.Anthropic()
        return self._client

    def summarize(self, chunk: Chunk, file_text: str) -> str:
        client = self._get_client()
        file_excerpt = file_text[: self.cfg.llm_max_file_chars]
        msg = client.messages.create(
            model=self.cfg.llm_model,
            max_tokens=150,
            messages=[{
                "role": "user",
                "content": self.PROMPT.format(
                    path=chunk.path, file_text=file_excerpt,
                    chunk_text=chunk.content[:6000]),
            }],
        )
        return "".join(
            block.text for block in msg.content if block.type == "text"
        ).strip()
