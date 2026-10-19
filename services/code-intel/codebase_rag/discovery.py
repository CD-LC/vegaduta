"""File discovery: git-aware when possible, plain walk otherwise.

Returns repo-relative paths of files we should index (recognized language,
not ignored, not binary, not oversized).
"""

from __future__ import annotations

import fnmatch
import os
import stat

from . import gitstate
from .config import Config
from .languages import detect_language

MAX_FILE_BYTES = 2 * 1024 * 1024  # skip files > 2 MB (generated/vendored)

# O_NOFOLLOW where the OS has it (Linux/macOS): the final open refuses a
# symlink even if one is swapped in after the lstat check below.
_O_NOFOLLOW = getattr(os, "O_NOFOLLOW", 0)


def safe_path(root: str, rel_path: str) -> str | None:
    """Absolute path of `rel_path` if it is a REGULAR, NON-SYMLINK file
    that really lives under `root`; else None.

    SEC-016: a repository is tenant input, and a committed symlink such as
    `leak.py -> /data/tenants/<victim>/repos/x/checkout/secrets.py` (or a
    symlinked directory on the way there) would otherwise be followed by
    getsize/open and the victim's file indexed into the attacker's search
    results. Symlinks are skipped outright (not just out-of-tree ones - an
    in-tree link only duplicates content), and the realpath must stay
    inside root, which also catches a symlinked PARENT directory.
    """
    abs_path = os.path.join(root, rel_path)
    try:
        st = os.lstat(abs_path)
    except OSError:
        return None
    if stat.S_ISLNK(st.st_mode) or not stat.S_ISREG(st.st_mode):
        return None
    real_root = os.path.realpath(root)
    real = os.path.realpath(abs_path)
    try:
        inside = os.path.commonpath(
            [os.path.normcase(real_root), os.path.normcase(real)]
        ) == os.path.normcase(real_root)
    except ValueError:  # different drives (Windows)
        inside = False
    return abs_path if inside else None


def _open_nofollow(abs_path: str) -> int:
    return os.open(abs_path, os.O_RDONLY | _O_NOFOLLOW | getattr(os, "O_BINARY", 0))


def _ignored(rel_path: str, patterns: list[str]) -> bool:
    parts = rel_path.replace("\\", "/").split("/")
    base = parts[-1]
    for pat in patterns:
        if "/" in pat:
            if fnmatch.fnmatch(rel_path, pat):
                return True
        elif "*" in pat or "?" in pat or "[" in pat:
            if fnmatch.fnmatch(base, pat):
                return True
        else:
            if pat in parts:  # directory or exact name anywhere in the path
                return True
    return False


def _eligible(cfg: Config, rel_path: str, ignores: list[str]) -> bool:
    if _ignored(rel_path, ignores):
        return False
    lang = detect_language(rel_path)
    if lang is None:
        return False
    if cfg.languages and lang not in cfg.languages:
        return False
    abs_path = safe_path(cfg.root, rel_path)
    if abs_path is None:
        return False
    try:
        size = os.lstat(abs_path).st_size
    except OSError:
        return False
    if size == 0 or size > MAX_FILE_BYTES:
        return False
    return True


def _looks_binary(abs_path: str) -> bool:
    try:
        with os.fdopen(_open_nofollow(abs_path), "rb") as f:
            sample = f.read(8000)
        return b"\0" in sample
    except OSError:
        return True


def discover_all(cfg: Config) -> list[str]:
    """Every indexable file in the tree (repo-relative, sorted)."""
    ignores = cfg.all_ignores()
    if gitstate.is_git_repo(cfg.root):
        candidates = gitstate.list_tracked_and_untracked(cfg.root)
    else:
        candidates = []
        # followlinks=False (the default, made explicit): never descend into
        # a symlinked directory; symlinked files are dropped by safe_path.
        for dirpath, dirnames, filenames in os.walk(cfg.root, followlinks=False):
            rel_dir = os.path.relpath(dirpath, cfg.root)
            rel_dir = "" if rel_dir == "." else rel_dir.replace("\\", "/")
            dirnames[:] = [
                d for d in dirnames
                if not _ignored((rel_dir + "/" + d).lstrip("/"), ignores)
            ]
            for name in filenames:
                candidates.append((rel_dir + "/" + name).lstrip("/"))
    out = []
    for rel in candidates:
        rel = rel.replace("\\", "/")
        if _eligible(cfg, rel, ignores) and not _looks_binary(os.path.join(cfg.root, rel)):
            out.append(rel)
    return sorted(set(out))


def filter_eligible(cfg: Config, paths: set[str]) -> set[str]:
    """Subset of `paths` that we should actually index."""
    ignores = cfg.all_ignores()
    result = set()
    for rel in paths:
        rel = rel.replace("\\", "/")
        abs_path = safe_path(cfg.root, rel)
        if abs_path is None:
            continue
        if _eligible(cfg, rel, ignores) and not _looks_binary(abs_path):
            result.add(rel)
    return result


def read_text(cfg: Config, rel_path: str) -> str | None:
    abs_path = safe_path(cfg.root, rel_path)
    if abs_path is None:
        return None
    try:
        with os.fdopen(_open_nofollow(abs_path), "rb") as f:
            text = f.read().decode("utf-8", errors="replace")
        # same universal-newline result the old text-mode open() produced
        return text.replace("\r\n", "\n").replace("\r", "\n")
    except OSError:
        return None
