"""SEC-016: a repository's symlinks never make the indexer read files
outside that repository.

Uses REAL symlinks (skipped with a reason where the OS refuses to create
them, e.g. Windows without Developer Mode) and a REAL git clone of a repo
whose tree contains a symlink entry (built with git plumbing, so that part
needs no OS symlink support at all).

Run: python -m tests.test_symlink_confinement   (from the code-intel directory)
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import gitops
from app.registry import RepoEntry
from codebase_rag import discovery
from codebase_rag.config import load_config

PASS = 0
FAIL = 0
SKIP = 0

os.environ["GIT_CONFIG_NOSYSTEM"] = "1"
GIT_ID = ["-c", "user.name=t", "-c", "user.email=t@example.invalid",
          "-c", "init.defaultBranch=main"]


def check(name: str, cond: bool, extra: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def skip(name: str, why: str) -> None:
    global SKIP
    SKIP += 1
    print(f"  skip  {name} ({why})")


def git(cwd: str, *args: str, stdin: str | None = None) -> str:
    r = subprocess.run(["git", *GIT_ID, "-C", cwd, *args], input=stdin,
                       capture_output=True, text=True, timeout=60)
    return r.stdout.strip()


def _write(path: str, text: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


def _can_symlink(tmp: str) -> str | None:
    try:
        os.symlink(os.path.join(tmp, "nowhere"), os.path.join(tmp, "probe"))
        os.remove(os.path.join(tmp, "probe"))
        return None
    except (OSError, NotImplementedError) as e:
        return f"OS refused symlink creation: {e}"


def test_discovery_skips_symlinks() -> None:
    print("== discovery/read_text never follow symlinks out of the repo ==")
    tmp = tempfile.mkdtemp(prefix="ci-symlink-")
    try:
        why = _can_symlink(tmp)
        if why:
            skip("discovery symlink checks (plain walk + git listing)", why)
            return
        victim = os.path.join(tmp, "victim")
        _write(os.path.join(victim, "secret.py"), "VICTIM_SECRET = 'x'\n")
        _write(os.path.join(victim, ".coderag.yml"), "max_chunk_lines: 3\n")
        for mode in ("plain", "git"):
            root = os.path.join(tmp, f"repo-{mode}")
            _write(os.path.join(root, "ok.py"), "print('ok')\n")
            os.symlink(os.path.join(victim, "secret.py"), os.path.join(root, "leak.py"))
            os.symlink(victim, os.path.join(root, "linkdir"), target_is_directory=True)
            os.symlink(os.path.join(victim, ".coderag.yml"),
                       os.path.join(root, ".coderag.yml"))
            if mode == "git":
                git(root, "init", "-q")
                git(root, "add", "-A")
                git(root, "commit", "-q", "-m", "init")
            cfg = load_config(root)
            found = discovery.discover_all(cfg)
            check(f"{mode}: only the real file is discovered", found == ["ok.py"], str(found))
            check(f"{mode}: read_text refuses a symlinked file",
                  discovery.read_text(cfg, "leak.py") is None)
            check(f"{mode}: read_text refuses a path through a symlinked dir",
                  discovery.read_text(cfg, "linkdir/secret.py") is None)
            check(f"{mode}: filter_eligible drops both symlink routes",
                  discovery.filter_eligible(
                      cfg, {"ok.py", "leak.py", "linkdir/secret.py"}) == {"ok.py"})
            check(f"{mode}: read_text still reads a normal file",
                  discovery.read_text(cfg, "ok.py") == "print('ok')\n")
            check(f"{mode}: symlinked .coderag.yml not loaded",
                  cfg.max_chunk_lines != 3)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def test_safe_path_mocked() -> None:
    print("== safe_path decisions (mocked lstat/realpath - runs on every OS) ==")
    import stat as stat_mod
    from unittest import mock
    tmp = tempfile.mkdtemp(prefix="ci-symlink-mock-")
    try:
        _write(os.path.join(tmp, "ok.py"), "x = 1\n")
        _write(os.path.join(tmp, "sub", "b.py"), "y = 2\n")
        check("regular in-tree file accepted",
              discovery.safe_path(tmp, "ok.py") == os.path.join(tmp, "ok.py"))
        real_lstat = os.lstat

        def lstat_as_link(p, *a, **k):
            st = real_lstat(p, *a, **k)
            if p.endswith("ok.py"):
                return os.stat_result((stat_mod.S_IFLNK | 0o777, *tuple(st)[1:]))
            return st

        with mock.patch.object(discovery.os, "lstat", lstat_as_link):
            check("a symlink (by lstat) is rejected",
                  discovery.safe_path(tmp, "ok.py") is None)
        real_realpath = os.path.realpath
        outside = os.path.join(os.path.dirname(tmp), "elsewhere", "b.py")

        def realpath_via_linked_dir(p, *a, **k):
            if p.endswith("b.py"):
                return outside
            return real_realpath(p, *a, **k)

        with mock.patch.object(discovery.os.path, "realpath", realpath_via_linked_dir):
            check("a file whose realpath leaves root (symlinked parent) is rejected",
                  discovery.safe_path(tmp, os.path.join("sub", "b.py")) is None)
        check("a directory is not a file", discovery.safe_path(tmp, "sub") is None)
        check("a missing file is rejected", discovery.safe_path(tmp, "nope.py") is None)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def test_crlf_read_text_unchanged() -> None:
    print("== read_text keeps the old universal-newline behaviour ==")
    tmp = tempfile.mkdtemp(prefix="ci-symlink-crlf-")
    try:
        with open(os.path.join(tmp, "a.py"), "wb") as f:
            f.write(b"a = 1\r\nb = 2\rc = 3\n")
        cfg = load_config(tmp)
        check("CRLF/CR normalised to LF as text-mode open() did",
              discovery.read_text(cfg, "a.py") == "a = 1\nb = 2\nc = 3\n")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def test_clone_checks_out_symlinks_as_files() -> None:
    print("== clone uses core.symlinks=false (symlink -> plain file) ==")
    tmp = tempfile.mkdtemp(prefix="ci-symlink-clone-")
    old = os.environ.get("CODE_INTEL_GIT_ALLOWED_SCHEMES")
    os.environ["CODE_INTEL_GIT_ALLOWED_SCHEMES"] = "https,file"
    try:
        src = os.path.join(tmp, "src")
        os.makedirs(src)
        git(src, "init", "-q")
        _write(os.path.join(src, "ok.py"), "print('ok')\n")
        git(src, "add", "ok.py")
        # A symlink tree entry (mode 120000) via plumbing - no OS symlink needed.
        blob = git(src, "hash-object", "-w", "--stdin",
                   stdin="/data/tenants/victim/secret.py")
        git(src, "update-index", "--add", "--cacheinfo", f"120000,{blob},leak.py")
        git(src, "commit", "-q", "-m", "with symlink")
        check("fixture: leak.py is a symlink entry in the tree",
              git(src, "ls-tree", "HEAD", "leak.py").startswith("120000"))

        repo_dir = os.path.join(tmp, "clone")
        os.makedirs(repo_dir)
        url = "file:///" + src.replace(os.sep, "/").lstrip("/")
        checkout = gitops.ensure_checkout(
            RepoEntry(repo_id="r", source_type="git", git_url=url), repo_dir, timeout=60)
        leak = os.path.join(checkout, "leak.py")
        check("leak.py checked out as a regular file, not a link",
              os.path.isfile(leak) and not os.path.islink(leak))
        with open(leak) as f:
            check("its content is just the link text", f.read() == "/data/tenants/victim/secret.py")
        check("core.symlinks=false persisted in the clone's config",
              git(checkout, "config", "--get", "core.symlinks") == "false")
    finally:
        if old is None:
            os.environ.pop("CODE_INTEL_GIT_ALLOWED_SCHEMES", None)
        else:
            os.environ["CODE_INTEL_GIT_ALLOWED_SCHEMES"] = old
        shutil.rmtree(tmp, ignore_errors=True)


def main() -> int:
    test_discovery_skips_symlinks()
    test_safe_path_mocked()
    test_crlf_read_text_unchanged()
    test_clone_checks_out_symlinks_as_files()
    print(f"\n{PASS} passed, {FAIL} failed, {SKIP} skipped")
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
