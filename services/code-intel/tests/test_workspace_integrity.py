"""SEC-015: nothing inside a (tenant-writable) workspace steers host-side
file operations or host-side git.

Real git repos, real worktrees, real `git diff`. The tenant is simulated by
writing directly into the workspace dir - exactly what a check command can
do (the workspace is the docker bind mount / local-mode cwd).

Run: python -m tests.test_workspace_integrity   (from the code-intel directory)
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import executor
from app.executor import WorkspaceError

PASS = 0
FAIL = 0
SKIP = 0

# Hermetic git: ignore the machine's system gitconfig.
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


def git(cwd: str, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *GIT_ID, "-C", cwd, *args],
                          capture_output=True, text=True, timeout=60)


def make_repo_root(tmp: str) -> tuple[str, str, str]:
    """<tmp>/repo/{checkout,workspaces} like tenancy's layout; returns
    (repo_root, source_checkout, workspaces_dir)."""
    repo_root = os.path.join(tmp, "repo")
    source = os.path.join(repo_root, "checkout")
    os.makedirs(source)
    git(source, "init", "-q")
    with open(os.path.join(source, "a.py"), "w") as f:
        f.write("x = 1\n")
    git(source, "add", "a.py")
    git(source, "commit", "-q", "-m", "init")
    return repo_root, source, os.path.join(repo_root, "workspaces")


def fsmonitor_hook(tmp: str, marker: str) -> str:
    """A core.fsmonitor hook that proves execution by creating `marker`."""
    hook = os.path.join(tmp, "fsmon.sh")
    with open(hook, "w", newline="\n") as f:
        f.write(f"#!/bin/sh\necho pwned > '{marker.replace(os.sep, '/')}'\nexit 1\n")
    # B103: test fixture - the hook must be executable to prove it is NOT run.
    os.chmod(hook, 0o700)
    return hook.replace(os.sep, "/")


def test_metadata_outside_workspace() -> None:
    print("== metadata lives outside the workspace; stored path never followed ==")
    tmp = tempfile.mkdtemp(prefix="ci-wsint-meta-")
    try:
        _, source, wsdir = make_repo_root(tmp)
        meta = executor.create_workspace(source, wsdir)
        ws_id, ws_path = meta["workspace_id"], meta["path"]
        check("no metadata file inside the workspace",
              not os.path.exists(os.path.join(ws_path, ".codeintel-ws.json")))
        check("metadata in <workspaces>/.meta/<ws_id>.json",
              os.path.isfile(os.path.join(wsdir, ".meta", f"{ws_id}.json")))
        check("worktree admin dir recorded from the source repo",
              bool(meta["gitdir"]) and os.path.realpath(meta["gitdir"]).startswith(
                  os.path.realpath(os.path.join(source, ".git", "worktrees"))))

        # Tenant plants an old-style metadata file claiming another path.
        victim = os.path.join(tmp, "victim")
        os.makedirs(victim)
        open(os.path.join(victim, "keep.txt"), "w").close()
        with open(os.path.join(ws_path, ".codeintel-ws.json"), "w") as f:
            json.dump({"path": victim, "source": victim, "git": True}, f)
        loaded = executor.load_workspace(wsdir, ws_id)
        check("planted in-workspace metadata ignored (path recomputed)",
              loaded["path"] == ws_path)

        executor.delete_workspace(wsdir, ws_id)
        check("delete removed the workspace", not os.path.exists(ws_path))
        check("delete did NOT touch the path the tenant named",
              os.path.isfile(os.path.join(victim, "keep.txt")))
        check("delete removed the .meta record",
              not os.path.exists(os.path.join(wsdir, ".meta", f"{ws_id}.json")))
        wl = git(source, "worktree", "list").stdout
        check("git forgot the worktree (worktree prune)", ws_id not in wl, wl)

        for bad in ("../x", "ws-../../etc", "ws-ABCDEF123456", "ws-0123456789ab/x",
                    "ws-0123456789a"):
            try:
                executor.load_workspace(wsdir, bad)
                check(f"invalid id rejected: {bad!r}", False)
            except WorkspaceError:
                check(f"invalid id rejected: {bad!r}", True)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def test_legacy_migration() -> None:
    print("== legacy in-workspace metadata is migrated, never trusted ==")
    tmp = tempfile.mkdtemp(prefix="ci-wsint-legacy-")
    try:
        _, source, wsdir = make_repo_root(tmp)
        victim = os.path.join(tmp, "victim")
        os.makedirs(victim)
        open(os.path.join(victim, "keep.txt"), "w").close()

        # (1) legacy record with the genuine source: stays a git workspace.
        m1 = executor.create_workspace(source, wsdir)
        os.remove(os.path.join(wsdir, ".meta", f"{m1['workspace_id']}.json"))
        with open(os.path.join(m1["path"], ".codeintel-ws.json"), "w") as f:
            json.dump({"workspace_id": m1["workspace_id"], "path": victim,
                       "git": True, "source": source,
                       "created_at": time.time() + 10**9, "iterations": 3}, f)
        l1 = executor.load_workspace(wsdir, m1["workspace_id"])
        check("legacy: path recomputed, not the stored one", l1["path"] == m1["path"])
        check("legacy: genuine source -> git kept, gitdir re-derived from source",
              l1["git"] is True and l1["gitdir"] == m1["gitdir"])
        check("legacy: future created_at clamped (can't dodge the reaper)",
              l1["created_at"] <= time.time())
        check("legacy: migrated to .meta and in-workspace file removed",
              os.path.isfile(os.path.join(wsdir, ".meta", f"{m1['workspace_id']}.json"))
              and not os.path.exists(os.path.join(m1["path"], ".codeintel-ws.json")))

        # (2) legacy record pointing source at somewhere else.
        m2 = executor.create_workspace(source, wsdir)
        os.remove(os.path.join(wsdir, ".meta", f"{m2['workspace_id']}.json"))
        with open(os.path.join(m2["path"], ".codeintel-ws.json"), "w") as f:
            json.dump({"path": victim, "git": True, "source": victim}, f)
        l2 = executor.load_workspace(wsdir, m2["workspace_id"])
        check("legacy: foreign source -> treated as non-git (no git on it)",
              l2["git"] is False and l2["source"] != victim)
        executor.delete_workspace(wsdir, m2["workspace_id"])
        check("legacy: delete never touches the stored path",
              os.path.isfile(os.path.join(victim, "keep.txt"))
              and not os.path.exists(m2["path"]))

        # (3) garbage legacy record -> not found, nothing deleted.
        m3 = executor.create_workspace(source, wsdir)
        os.remove(os.path.join(wsdir, ".meta", f"{m3['workspace_id']}.json"))
        with open(os.path.join(m3["path"], ".codeintel-ws.json"), "w") as f:
            f.write("[1, 2")
        try:
            executor.load_workspace(wsdir, m3["workspace_id"])
            check("legacy: corrupt record -> WorkspaceError", False)
        except WorkspaceError:
            check("legacy: corrupt record -> WorkspaceError", True)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def test_gitlink_and_fsmonitor() -> None:
    print("== tampered gitlink not honoured; fsmonitor never executed ==")
    tmp = tempfile.mkdtemp(prefix="ci-wsint-git-")
    try:
        _, source, wsdir = make_repo_root(tmp)
        meta = executor.create_workspace(source, wsdir)
        ws_id, ws_path = meta["workspace_id"], meta["path"]
        executor.apply_files(wsdir, ws_id, [{"path": "a.py", "content": "x = 2\n"}])

        # Attacker gitdir with core.fsmonitor=<cmd>, and the workspace's
        # gitlink re-pointed at it (a check command can do exactly this).
        evil = os.path.join(tmp, "evil")
        os.makedirs(evil)
        git(evil, "init", "-q")
        marker = os.path.join(tmp, "PWNED")
        git(evil, "config", "core.fsmonitor", fsmonitor_hook(tmp, marker))
        evil_gitdir = os.path.join(evil, ".git").replace(os.sep, "/")
        gitlink = os.path.join(ws_path, ".git")
        os.remove(gitlink)  # Windows marks it hidden; "w" on a hidden file is EACCES
        with open(gitlink, "w") as f:
            f.write(f"gitdir: {evil_gitdir}\n")

        # Sanity: the OLD code path (`git -C <ws> diff`) really executes it
        # here, so the negative check below means something.
        subprocess.run(["git", "-c", f"safe.directory={ws_path}",
                        "-c", f"safe.directory={evil_gitdir}", "-C", ws_path,
                        "diff"], capture_output=True, text=True, timeout=60)
        exploit_works = os.path.exists(marker)
        if exploit_works:
            os.remove(marker)
            check("sanity: unhardened `git -C ws diff` runs the planted fsmonitor",
                  True)
        else:
            skip("sanity: unhardened git runs the planted fsmonitor",
                 "this git/OS did not invoke the hook")

        diff = executor.diff_workspace(wsdir, ws_id)
        check("diff_workspace did not execute the planted fsmonitor",
              not os.path.exists(marker))
        check("diff_workspace used the REAL worktree gitdir (shows the edit)",
              "-x = 1" in diff and "+x = 2" in diff, repr(diff[:300]))

        # Even a fsmonitor in the trusted repo config is overridden.
        git(source, "config", "core.fsmonitor", fsmonitor_hook(tmp, marker))
        executor.diff_workspace(wsdir, ws_id)
        check("core.fsmonitor=false override beats repo config",
              not os.path.exists(marker))
        git(source, "config", "--unset", "core.fsmonitor")

        executor.delete_workspace(wsdir, ws_id)
        check("attacker gitdir untouched by delete",
              os.path.isdir(os.path.join(evil, ".git")))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main() -> int:
    test_metadata_outside_workspace()
    test_legacy_migration()
    test_gitlink_and_fsmonitor()
    print(f"\n{PASS} passed, {FAIL} failed, {SKIP} skipped")
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
