"""Git helpers: HEAD commit, changed files between commits, dirty files.

Everything degrades gracefully on a non-git folder: is_git_repo() returns
False and the pipeline falls back to whole-tree hash comparison.
"""

from __future__ import annotations

import os
import subprocess


def _git(root: str, *args: str) -> str:
    result = subprocess.run(
        # safe.directory=<root> (protected cmdline scope): mounts that report
        # foreign ownership (Azure Files -> root) would otherwise fail every
        # read too - "detected dubious ownership". Scoped to the exact tree.
        ["git", "-c", f"safe.directory={os.path.realpath(root)}",
         "-C", root, *args],
        capture_output=True, text=True, timeout=120,
    )
    if result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def is_git_repo(root: str) -> bool:
    try:
        out = _git(root, "rev-parse", "--is-inside-work-tree")
        return out.strip() == "true"
    except Exception:
        return False


def head_commit(root: str) -> str | None:
    try:
        return _git(root, "rev-parse", "HEAD").strip()
    except Exception:
        return None  # e.g. brand-new repo with no commits


def commit_exists(root: str, commit: str) -> bool:
    try:
        _git(root, "cat-file", "-e", f"{commit}^{{commit}}")
        return True
    except Exception:
        return False


def diff_since(root: str, last_commit: str) -> tuple[set[str], set[str]]:
    """(changed_or_added, deleted) repo-relative paths between last_commit
    and HEAD, rename-aware: a rename contributes old path to deleted and
    new path to changed."""
    out = _git(root, "diff", "--name-status", "--find-renames", "-z",
               last_commit, "HEAD")
    changed: set[str] = set()
    deleted: set[str] = set()
    parts = out.split("\0")
    i = 0
    while i < len(parts):
        status = parts[i]
        if not status:
            i += 1
            continue
        if status.startswith("R") or status.startswith("C"):
            old, new = parts[i + 1], parts[i + 2]
            if status.startswith("R"):
                deleted.add(old)
            changed.add(new)
            i += 3
        else:
            path = parts[i + 1] if i + 1 < len(parts) else None
            if path:
                if status.startswith("D"):
                    deleted.add(path)
                else:
                    changed.add(path)
            i += 2
    return changed, deleted


def dirty_files(root: str) -> tuple[set[str], set[str]]:
    """(changed_or_untracked, deleted) from the working tree + index,
    i.e. everything not yet captured by HEAD."""
    out = _git(root, "status", "--porcelain", "-z", "--untracked-files=all")
    changed: set[str] = set()
    deleted: set[str] = set()
    parts = out.split("\0")
    i = 0
    while i < len(parts):
        entry = parts[i]
        if len(entry) < 4:
            i += 1
            continue
        xy, path = entry[:2], entry[3:]
        if "R" in xy:  # rename: next NUL-part is the original path
            i += 1
            if i < len(parts):
                deleted.add(parts[i])
        if "D" in xy:
            deleted.add(path)
        else:
            changed.add(path)
        i += 1
    return changed, deleted


def list_tracked_and_untracked(root: str) -> list[str]:
    """All files git would consider part of the tree (tracked + untracked,
    minus ignored) - the cheapest gitignore-respecting file walk there is."""
    out = _git(root, "ls-files", "-z", "--cached", "--others",
               "--exclude-standard")
    return [p for p in out.split("\0") if p]


def file_exists(root: str, rel_path: str) -> bool:
    return os.path.isfile(os.path.join(root, rel_path))
