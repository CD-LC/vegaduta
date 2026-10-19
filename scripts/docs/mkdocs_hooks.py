"""MkDocs hooks for the documentation site (mkdocs.yml).

Two jobs, both so the Markdown stays correct on GitHub *and* on the published site:

1. Embeds. A line ``--8<-- "path/from/repo/root.md"`` is replaced by that file's content, so the
   Java Agent SDK README, the example README and the client SDKs README each have exactly one
   source. Relative links inside the embedded file are rewritten as if the file sat in place.
2. Links that leave docs/. GitHub resolves ``../deploy/README.md`` from docs/README.md; the
   site cannot, because the target is not part of the site. Such links are rewritten to the
   file's GitHub URL (blob for files, tree for directories). Links that stay inside docs/ are
   re-expressed relative to the page that shows them, so they keep working for embeds.

Nothing here changes the files on disk.
"""

from __future__ import annotations

import os
import posixpath
import re

REPO_URL = "https://github.com/CD-LC/vegaduta"
BRANCH = "main"

_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
_LINK = re.compile(r"(\]\()([^)\s]+)((?:\s+\"[^\"]*\")?\))")
_EMBED = re.compile(r'^--8<--\s+"([^"]+)"\s*$')
_SCHEME = re.compile(r"^[a-zA-Z][a-zA-Z0-9+.-]*:")


def _exists(repo_path: str) -> bool:
    return os.path.exists(os.path.join(_REPO_ROOT, repo_path))


def _isdir(repo_path: str) -> bool:
    return os.path.isdir(os.path.join(_REPO_ROOT, repo_path))


def _rewrite_links(markdown: str, source_repo_path: str, page_repo_path: str) -> str:
    """Rewrite relative links of ``markdown`` (which lives at ``source_repo_path``)."""
    source_dir = posixpath.dirname(source_repo_path)
    page_dir = posixpath.dirname(page_repo_path)
    out: list[str] = []
    in_fence = False
    for line in markdown.split("\n"):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
        if in_fence:
            out.append(line)
            continue

        def repl(match: re.Match[str]) -> str:
            target = match.group(2)
            if target.startswith(("#", "/", "<")) or _SCHEME.match(target):
                return match.group(0)
            path, hash_, fragment = target.partition("#")
            if not path:
                return match.group(0)
            resolved = posixpath.normpath(posixpath.join(source_dir, path))
            if resolved.startswith(".."):
                return match.group(0)
            if not _exists(resolved):
                return match.group(0)
            frag = f"#{fragment}" if hash_ else ""
            inside_docs = resolved.startswith("docs/") and not _isdir(resolved)
            if inside_docs:
                rel = posixpath.relpath(resolved, page_dir or ".")
                return f"{match.group(1)}{rel}{frag}{match.group(3)}"
            kind = "tree" if _isdir(resolved) else "blob"
            return f"{match.group(1)}{REPO_URL}/{kind}/{BRANCH}/{resolved.rstrip('/')}{frag}{match.group(3)}"

        out.append(_LINK.sub(repl, line))
    return "\n".join(out)


def on_page_markdown(markdown: str, page, config, files):  # noqa: ANN001 - MkDocs hook signature
    page_repo_path = posixpath.join("docs", page.file.src_uri)
    lines: list[str] = []
    for line in markdown.split("\n"):
        embed = _EMBED.match(line)
        if not embed:
            lines.append(line)
            continue
        embedded_path = embed.group(1)
        full = os.path.join(_REPO_ROOT, embedded_path)
        if not os.path.isfile(full):
            raise FileNotFoundError(f"{page.file.src_uri}: embedded file not found: {embedded_path}")
        with open(full, encoding="utf-8") as handle:
            body = handle.read()
        lines.append(_rewrite_links(body, embedded_path, page_repo_path))
    text = "\n".join(lines)
    # The page's own links, resolved against its location in the repository.
    return _rewrite_links(text, page_repo_path, page_repo_path)
