"""Regression test for the 2026-09-27 prod breakage: on Azure Files
(SMB/CIFS), which backs CODE_INTEL_DATA_DIR on Container Apps, chmod(2) is
refused - so a plain in-checkout .git makes every git lockfile commit die
("chmod on .../config.lock failed: Operation not permitted" / "could not
set 'core.filemode'"). ensure_checkout must detect the chmod-less fs and
clone with --separate-git-dir onto container-local storage, keeping only
the worktree + .git gitlink in the tenant's data subtree. It must also
re-clone when the gitlink target is gone (a replica restart wipes
container-local gitdirs while the share keeps the worktree).

This host's temp dirs DO support chmod, so the probe is stubbed for the
separate-git-dir cases; the last case exercises the real probe to pin the
unchanged POSIX behavior.

Run: python3 tests/test_gitops_separate_gitdir.py   (from the code-intel directory)
"""

from __future__ import annotations

import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# These tests clone fixture repos over file://, which production refuses
# (gitops.validate_git_source allows https only). Widen it for this process
# before app.gitops is used.
os.environ.setdefault("CODE_INTEL_GIT_ALLOWED_SCHEMES", "https,file")

from app import gitops
from app.registry import RepoEntry

PASS = 0
FAIL = 0


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def make_source_repo(base: str, name: str = "source-repo") -> str:
    d = os.path.join(base, name)
    os.makedirs(os.path.join(d, "sub"))
    with open(os.path.join(d, "sub", "f.py"), "w") as f:
        f.write("def x():\n    return 1\n")
    subprocess.run(["git", "init", "-q", d], check=True)
    subprocess.run(["git", "-C", d, "config", "user.email", "t@t"], check=True)
    subprocess.run(["git", "-C", d, "config", "user.name", "t"], check=True)
    subprocess.run(["git", "-C", d, "add", "-A"], check=True)
    subprocess.run(["git", "-C", d, "commit", "-qm", "init"], check=True)
    return d


def to_file_uri(path: str) -> str:
    return pathlib.Path(path).resolve().as_uri()


def git_head(path: str) -> str:
    r = subprocess.run(["git", "-C", path, "rev-parse", "HEAD"],
                       capture_output=True, text=True, check=True)
    return r.stdout.strip()


def read_gitlink(checkout: str) -> str:
    with open(os.path.join(checkout, ".git"), encoding="utf-8") as f:
        return f.read().strip()


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="codeintel-sepgitdir-test-")
    real_probe = gitops._fs_supports_chmod
    gitdir_root = os.path.join(tmp, "gitdirs")
    os.environ["CODE_INTEL_GITDIR_ROOT"] = gitdir_root
    try:
        source = make_source_repo(tmp)
        git_url = to_file_uri(source)
        entry = RepoEntry(repo_id="r1", source_type="git", git_url=git_url)

        print("== chmod-less fs: clone lands metadata on local disk ==")
        gitops._fs_supports_chmod = lambda p: False
        repo_dir = os.path.join(tmp, "data", "t1", "repos", "r1")
        checkout = gitops.ensure_checkout(entry, repo_dir, timeout=60)
        check("worktree created on the data dir",
              os.path.isdir(os.path.join(checkout, "sub")))
        check(".git is a gitlink FILE, not a directory",
              os.path.isfile(os.path.join(checkout, ".git")))
        link = read_gitlink(checkout)
        check("gitlink points under CODE_INTEL_GITDIR_ROOT",
              link.startswith("gitdir:")
              and os.path.realpath(
                  os.path.join(
                      checkout, link[len("gitdir:"):].strip()  # noqa: RUF034
                      if not os.path.isabs(link[len("gitdir:"):].strip())
                      else link[len("gitdir:"):].strip()))
              .startswith(os.path.realpath(gitdir_root)),
              extra=link)
        gitdir = gitops._gitdir_for(repo_dir)
        check("separate gitdir exists and holds the objects/refs",
              os.path.isdir(os.path.join(gitdir, "objects")))
        check("checkout still resolves as a git worktree",
              git_head(checkout) == git_head(source))

        print("== gitlink checkout: fetch+reset keeps working ==")
        with open(os.path.join(source, "sub", "g.py"), "w") as f:
            f.write("def y():\n    return 2\n")
        subprocess.run(["git", "-C", source, "add", "-A"], check=True)
        subprocess.run(["git", "-C", source, "commit", "-qm", "two"],
                       check=True)
        gitops.ensure_checkout(entry, repo_dir, timeout=60)
        check("fetch+reset via gitlink picked up the new commit",
              git_head(checkout) == git_head(source))
        check("new file materialized in the worktree",
              os.path.isfile(os.path.join(checkout, "sub", "g.py")))

        print("== stale gitlink (replica restart): re-clones on demand ==")
        # git pack files are read-only; Windows refuses to unlink those, so
        # rmtree needs the classic chmod-and-retry (POSIX unlink ignores
        # file mode entirely - prod code's ignore_errors rmtree is fine).
        def _rw_retry(func, path, _exc):
            os.chmod(path, 0o700)
            func(path)
        shutil.rmtree(gitdir, onerror=_rw_retry)
        check("gitdir really gone", not os.path.isdir(gitdir))
        checkout = gitops.ensure_checkout(entry, repo_dir, timeout=60)
        check("re-cloned into a fresh gitdir", os.path.isdir(gitdir))
        check("worktree restored and at source HEAD",
              os.path.isfile(os.path.join(checkout, "sub", "g.py"))
              and git_head(checkout) == git_head(source))

        print("== discard_gitdir removes the local clone metadata ==")
        gitops.discard_gitdir(repo_dir)
        check("gitdir removed", not os.path.isdir(gitdir))

        print("== chmod-capable fs: unchanged in-checkout .git ==")
        gitops._fs_supports_chmod = real_probe
        repo_dir2 = os.path.join(tmp, "data", "t1", "repos", "r2")
        checkout2 = gitops.ensure_checkout(
            RepoEntry(repo_id="r2", source_type="git", git_url=git_url),
            repo_dir2, timeout=60)
        check("plain clone still produces a .git DIRECTORY",
              os.path.isdir(os.path.join(checkout2, ".git")))
        check("nothing leaked into CODE_INTEL_GITDIR_ROOT",
              not os.path.exists(
                  gitops._gitdir_for(repo_dir2)))

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        gitops._fs_supports_chmod = real_probe
        os.environ.pop("CODE_INTEL_GITDIR_ROOT", None)
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
