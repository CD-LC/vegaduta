"""Read a project's current file set back out — for the client-side
instant preview (SandboxPreview renders it in a sandboxed iframe without
waiting for a Pages deploy) and for any future "show me the code" surface.

BYO projects read straight from the branch over the Git Data API (list
tree -> get file text per path); legacy local projects read from disk.
Only text files a generated app is actually made of are returned —
lockfiles, workflows, binaries and assets are deliberately skipped to keep
the response small. Caps keep a pathological repo from producing a
huge response.
"""

from __future__ import annotations

import os

from . import github_credentials, projects
from .github_client import GitHubClient, GitHubError
from .settings import SETTINGS, Settings
from .tenancy import TenantContext, project_root

_TEXT_EXT = {
    ".html", ".htm", ".css", ".js", ".jsx", ".ts", ".tsx", ".mjs",
    ".json", ".astro", ".vue", ".svelte", ".svg", ".md", ".txt", ".py",
}
_SKIP_PREFIX = ("node_modules/", ".git/", "dist/", "build/", ".astro/")
_SKIP_BASENAME = {"package-lock.json", "yarn.lock", "pnpm-lock.yaml"}
_MAX_FILES = 40
_MAX_FILE_BYTES = 256 * 1024


def _is_text_source(path: str) -> bool:
    if any(path.startswith(p) for p in _SKIP_PREFIX):
        return False
    base = path.rsplit("/", 1)[-1]
    if base in _SKIP_BASENAME or base.startswith("."):
        return False
    ext = os.path.splitext(base)[1].lower()
    return ext in _TEXT_EXT


def project_files(ctx: TenantContext, project_id: str,
                  settings: Settings = SETTINGS) -> list[dict]:
    """[{path, content}] for the project's current text sources."""
    project = projects.get_or_404(settings.data_dir, ctx, project_id)
    if project.github:
        return _files_from_branch(ctx, project, settings)
    return _files_from_local(ctx, project_id, settings)


def _files_from_branch(ctx: TenantContext, project,
                       settings: Settings) -> list[dict]:
    gh_state = project.github or {}
    repo, branch = gh_state.get("repo_full_name"), gh_state.get("branch")
    if not repo or not branch:
        return []
    conn = github_credentials.require(ctx, settings)
    token = github_credentials.token_for(conn, settings)
    gh = GitHubClient(token, settings)
    try:
        tree = gh.list_tree(repo, branch)
    except GitHubError:
        return []
    out = []
    for entry in tree:
        path = entry["path"]
        if len(out) >= _MAX_FILES:
            break
        if not _is_text_source(path):
            continue
        if (entry.get("size") or 0) > _MAX_FILE_BYTES:
            continue
        text = gh.get_file_text(repo, branch, path)
        if text is not None:
            out.append({"path": path, "content": text})
    return out


def _files_from_local(ctx: TenantContext, project_id: str,
                      settings: Settings) -> list[dict]:
    pdir = project_root(settings.data_dir, ctx, project_id, create=False)
    if not os.path.isdir(pdir):
        return []
    out = []
    for dirpath, _dn, filenames in os.walk(pdir):
        for fn in sorted(filenames):
            if len(out) >= _MAX_FILES:
                break
            ap = os.path.join(dirpath, fn)
            rel = os.path.relpath(ap, pdir).replace(os.sep, "/")
            if not _is_text_source(rel):
                continue
            try:
                if os.path.getsize(ap) > _MAX_FILE_BYTES:
                    continue
                with open(ap, "r", encoding="utf-8") as f:
                    out.append({"path": rel, "content": f.read()})
            except (OSError, UnicodeDecodeError):
                continue
    return out
