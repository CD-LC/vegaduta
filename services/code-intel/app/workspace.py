"""Isolated scratch workspaces for the run -> fix -> rerun loop.

Every run happens inside a workspace under the tenant's subtree (git
worktree when the source is a git checkout; copy otherwise), so the primary
checkout and the index are never mutated. This module owns a workspace's
lifecycle - create, load, apply files, diff, delete - and its trusted
metadata; executor.py owns what runs inside one.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import time
import uuid
from typing import Any

from .textcap import tail


class WorkspaceError(RuntimeError):
    pass


# SEC-015: everything inside <workspaces_dir>/<ws_id>/ is TENANT-WRITABLE -
# the check command runs there (bind-mounted rw in docker mode, cwd in local
# mode). So nothing this service later TRUSTS may live in it:
#   - workspace metadata lives in <workspaces_dir>/.meta/<ws_id>.json (never
#     mounted; `.meta` also doesn't match the reaper's `ws-*` scan), and the
#     workspace path is always recomputed from (workspaces_dir, ws_id) - a
#     stored path is never followed;
#   - host-side git never reads the workspace's `.git` gitlink (a tenant can
#     point it at a gitdir whose config sets core.fsmonitor=<cmd>, i.e. RCE in
#     this service). The worktree's admin dir is recorded at creation from
#     the source repo's own gitdir, and every workspace git call passes an
#     explicit --git-dir/--work-tree with fsmonitor and hooks disabled.
_WS_ID_RE = re.compile(r"^ws-[0-9a-f]{12}$")
_META_DIRNAME = ".meta"
_LEGACY_META = ".codeintel-ws.json"  # pre-SEC-015 in-workspace location
_LEGACY_META_MAX_BYTES = 64 * 1024


def _ws_path(workspaces_dir: str, ws_id: str) -> str:
    if not _WS_ID_RE.match(ws_id or ""):
        raise WorkspaceError(f"invalid workspace id: {ws_id!r}")
    return os.path.join(os.path.realpath(workspaces_dir), ws_id)


def _meta_file(workspaces_dir: str, ws_id: str) -> str:
    return os.path.join(os.path.realpath(workspaces_dir), _META_DIRNAME,
                        f"{ws_id}.json")


def _write_meta(workspaces_dir: str, meta: dict[str, Any]) -> None:
    path = _meta_file(workspaces_dir, meta["workspace_id"])
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(meta, f)
    os.replace(tmp, path)


def _source_common_gitdir(source_checkout: str) -> str | None:
    """The source checkout's own git dir (service-managed, never mounted
    into a sandbox): `.git` dir, or a --separate-git-dir gitlink target."""
    from .gitops import _existing_git_dir
    gd = _existing_git_dir(source_checkout)
    return os.path.realpath(gd) if gd else None


def _find_worktree_admin(source_checkout: str, ws_path: str) -> str | None:
    """Locate ws_path's worktree admin dir by scanning the SOURCE repo's
    <gitdir>/worktrees/*/gitdir back-pointers - trusted files outside the
    workspace - instead of reading the workspace's tenant-writable gitlink."""
    common = _source_common_gitdir(source_checkout)
    if not common:
        return None
    wt_root = os.path.join(common, "worktrees")
    want = os.path.normcase(os.path.realpath(os.path.join(ws_path, ".git")))
    try:
        names = os.listdir(wt_root)
    except OSError:
        return None
    for name in names:
        admin = os.path.join(wt_root, name)
        try:
            with open(os.path.join(admin, "gitdir"), encoding="utf-8") as f:
                target = f.read().strip()
        except OSError:
            continue
        if os.path.normcase(os.path.realpath(target)) == want:
            return os.path.realpath(admin)
    return None


def _ws_git(meta: dict[str, Any], *args: str) -> list[str]:
    """Hardened host-side git argv for a workspace (see SEC-015 note)."""
    ws_path = meta["path"]
    return ["git",
            "-c", "core.fsmonitor=false",
            "-c", f"core.hooksPath={os.devnull}",
            "-c", f"safe.directory={os.path.realpath(meta['source'])}",
            "-c", f"safe.directory={ws_path}",
            "-c", f"safe.directory={meta['gitdir']}",
            f"--git-dir={meta['gitdir']}", f"--work-tree={ws_path}", *args]


def create_workspace(source_checkout: str, workspaces_dir: str) -> dict:
    """Isolated copy of the source tree. git worktree when possible
    (cheap, exact), rsync-style copy otherwise."""
    ws_id = "ws-" + uuid.uuid4().hex[:12]
    os.makedirs(workspaces_dir, exist_ok=True)
    ws_path = _ws_path(workspaces_dir, ws_id)
    # .git is a dir for a plain clone, a gitlink FILE for a checkout cloned
    # with --separate-git-dir (chmod-less mounts - see gitops._gitdir_for).
    is_git = os.path.exists(os.path.join(source_checkout, ".git"))
    gitdir: str | None = None
    if is_git:
        r = subprocess.run(
            # safe.directory (protected cmdline scope): source checkouts on
            # mounts reporting foreign ownership (Azure Files -> root) are
            # otherwise refused as "dubious ownership". Whitelist BOTH the
            # repo we run in and the worktree path being created - the check
            # can fire on either during worktree add.
            ["git", "-c", f"safe.directory={os.path.realpath(source_checkout)}",
             "-c", f"safe.directory={ws_path}",
             "-c", "core.fsmonitor=false", "-c", f"core.hooksPath={os.devnull}",
             "-C", source_checkout, "worktree", "add", "--detach",
             ws_path], capture_output=True, text=True, timeout=120)
        if r.returncode != 0:
            raise WorkspaceError(f"git worktree add failed: {r.stderr[:300]}")
        # Recorded NOW, before any tenant code has run in the workspace.
        gitdir = _find_worktree_admin(source_checkout, ws_path)
        if gitdir is None:
            shutil.rmtree(ws_path, ignore_errors=True)
            raise WorkspaceError("git worktree add: admin dir not found")
    else:
        shutil.copytree(source_checkout, ws_path, symlinks=True,
                        ignore=shutil.ignore_patterns(".git", ".coderag",
                                                      "node_modules"))
    meta = {"workspace_id": ws_id, "path": ws_path, "git": is_git,
            "source": source_checkout, "gitdir": gitdir,
            "created_at": time.time(), "iterations": 0}
    _write_meta(workspaces_dir, meta)
    return meta


def _migrate_legacy_meta(workspaces_dir: str, ws_id: str,
                         ws_path: str) -> dict[str, Any] | None:
    """Pre-SEC-015 workspaces kept metadata INSIDE the workspace, so every
    field is tenant-controlled. Rebuild a trusted record: path recomputed;
    source trusted only if it is this repo's own service-managed checkout
    (sibling `checkout/` of workspaces_dir); gitdir re-derived from the
    source repo; created_at clamped to now (a future stamp can't dodge the
    reaper); iterations sanitised. Then move it to .meta/."""
    legacy = os.path.join(ws_path, _LEGACY_META)
    if os.path.islink(legacy) or not os.path.isfile(legacy):
        return None
    try:
        with open(legacy, encoding="utf-8") as f:
            raw = json.loads(f.read(_LEGACY_META_MAX_BYTES))
    except (OSError, ValueError):
        return None
    if not isinstance(raw, dict):
        return None
    now = time.time()
    expected_source = os.path.join(
        os.path.dirname(os.path.realpath(workspaces_dir)), "checkout")
    source = raw.get("source")
    gitdir = None
    if (isinstance(source, str)
            and os.path.realpath(source) == os.path.realpath(expected_source)):
        gitdir = _find_worktree_admin(expected_source, ws_path)
    created = raw.get("created_at")
    iterations = raw.get("iterations")
    meta = {"workspace_id": ws_id, "path": ws_path, "git": gitdir is not None,
            "source": expected_source, "gitdir": gitdir,
            "created_at": (min(float(created), now)
                           if isinstance(created, int | float) else now),
            "iterations": (iterations if isinstance(iterations, int)
                           and 0 <= iterations < 10_000 else 0)}
    _write_meta(workspaces_dir, meta)
    try:
        os.remove(legacy)
    except OSError:
        pass
    return meta


def load_workspace(workspaces_dir: str, ws_id: str) -> dict:
    ws_path = _ws_path(workspaces_dir, ws_id)
    if os.path.islink(ws_path) or not os.path.isdir(ws_path):
        raise WorkspaceError(f"workspace not found: {ws_id}")
    meta_file = _meta_file(workspaces_dir, ws_id)
    if os.path.isfile(meta_file):
        with open(meta_file, encoding="utf-8") as f:
            meta: dict[str, Any] = json.load(f)
    else:
        migrated = _migrate_legacy_meta(workspaces_dir, ws_id, ws_path)
        if migrated is None:
            raise WorkspaceError(f"workspace not found: {ws_id}")
        meta = migrated
    # Defence in depth: .meta is service-private, but never let a stored
    # path/id steer a delete or write anyway.
    meta["workspace_id"] = ws_id
    meta["path"] = ws_path
    if meta.get("git") and not meta.get("gitdir"):
        meta["git"] = False
    return meta


def delete_workspace(workspaces_dir: str, ws_id: str) -> None:
    meta = load_workspace(workspaces_dir, ws_id)
    # rmtree first, then let git forget the worktree via `worktree prune`,
    # which only reads the SOURCE repo's admin dirs. (`worktree remove`
    # would inspect the tenant-writable worktree.) rmtree never follows
    # symlinks planted inside the workspace.
    shutil.rmtree(meta["path"], ignore_errors=True)
    if meta.get("git"):
        subprocess.run(["git", "-c",
                        f"safe.directory={os.path.realpath(meta['source'])}",
                        "-c", "core.fsmonitor=false",
                        "-c", f"core.hooksPath={os.devnull}",
                        "-C", meta["source"], "worktree", "prune"],
                       capture_output=True, timeout=60)
    try:
        os.remove(_meta_file(workspaces_dir, ws_id))
    except OSError:
        pass


def apply_files(workspaces_dir: str, ws_id: str,
                files: list[dict]) -> dict:
    """Apply full-content file writes (the rework step). Paths are
    repo-relative and confined to the workspace."""
    meta = load_workspace(workspaces_dir, ws_id)
    ws_path = os.path.realpath(meta["path"])
    written, deleted = [], []
    for f in files:
        rel = f["path"].replace("\\", "/").lstrip("/")
        abs_path = os.path.realpath(os.path.join(ws_path, rel))
        if not abs_path.startswith(ws_path + os.sep):
            raise WorkspaceError(f"path escapes workspace: {f['path']!r}")
        if f.get("delete"):
            if os.path.exists(abs_path):
                os.remove(abs_path)
                deleted.append(rel)
            continue
        os.makedirs(os.path.dirname(abs_path), exist_ok=True)
        with open(abs_path, "w", encoding="utf-8") as out:
            out.write(f.get("content", ""))
        written.append(rel)
    meta["iterations"] += 1
    _write_meta(workspaces_dir, meta)
    return {"workspace_id": ws_id, "written": written, "deleted": deleted,
            "iteration": meta["iterations"]}


def diff_workspace(workspaces_dir: str, ws_id: str) -> str:
    """The final artifact of a successful rework loop: a unified diff the
    caller can turn into a PR. Empty for non-git workspaces."""
    meta = load_workspace(workspaces_dir, ws_id)
    if not meta["git"]:
        return ""
    r = subprocess.run(_ws_git(meta, "diff"),
                       capture_output=True, text=True, timeout=60)
    return tail(r.stdout, 60_000)
