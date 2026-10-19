"""Security-critical test for the per-tenant BYO-credential extension
(`RepoEntry.auth_token` / `RegisterRepoRequest.auth_token`).

The entire security argument for accepting a direct per-tenant token (as
opposed to only the shared `auth_env` env-var-name mechanism) rests on the
token never touching disk, subprocess argv, or logs - exactly the same
posture gitops.py already guarantees for `auth_env`-resolved tokens via its
ephemeral credential helper. This test proves that holds for `auth_token`
too, that it takes precedence over `auth_env` when both are set, that the
registry never persists the raw value to registry.json, and that the
pre-existing `auth_env`-only path is completely unaffected (additive-only).

Run: python3 tests/test_gitops_auth_token.py   (from the code-intel directory)
"""

from __future__ import annotations

import contextlib
import io
import json
import logging
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# These tests clone fixture repos over file://, which production refuses
# (gitops.validate_git_source allows https only). Widen it for this process,
# and approve file:// as an auth_env destination, before app.gitops is used.
os.environ.setdefault("CODE_INTEL_GIT_ALLOWED_SCHEMES", "https,file")
os.environ.setdefault("CODE_INTEL_AUTH_ENV_URL_PREFIXES", "file://")

from app import (
    gitops,
    registry,
)
from app.registry import RepoEntry
from app.tenancy import TenantContext, tenant_root

PASS = 0
FAIL = 0
FAKE_TOKEN = "ghp_FAKE_obviously_not_a_real_token_zzz999"


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def make_source_repo(base: str) -> str:
    """A throwaway local git repo standing in for a BYO GitHub remote -
    ensure_checkout() clones from it via a file:// URL, same as it would
    clone from https://github.com/... in production."""
    d = os.path.join(base, "source-repo")
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


class ListHandler(logging.Handler):
    def __init__(self):
        super().__init__()
        self.records: list[str] = []

    def emit(self, record):
        self.records.append(self.format(record))


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="codeintel-gitops-authtoken-test-")
    real_run = subprocess.run
    try:
        source = make_source_repo(tmp)
        git_url = to_file_uri(source)

        print("== auth_token: never leaks to .git/config, argv, or logs ==")
        captured_calls: list[list[str]] = []

        def spy_run(cmd, *args, **kwargs):
            captured_calls.append(list(cmd))
            return real_run(cmd, *args, **kwargs)

        handler = ListHandler()
        root_logger = logging.getLogger()
        root_logger.addHandler(handler)
        prev_level = root_logger.level
        root_logger.setLevel(logging.DEBUG)

        entry = RepoEntry(repo_id="byo", source_type="git",
                           git_url=git_url, auth_token=FAKE_TOKEN)
        repo_dir = os.path.join(tmp, "repo_state")
        os.makedirs(repo_dir, exist_ok=True)

        stdout_buf, stderr_buf = io.StringIO(), io.StringIO()
        gitops.subprocess.run = spy_run
        try:
            with contextlib.redirect_stdout(stdout_buf), \
                 contextlib.redirect_stderr(stderr_buf):
                checkout = gitops.ensure_checkout(entry, repo_dir, timeout=30)
                # second call exercises the fetch+reset path with the same
                # token, not just the initial clone path
                gitops.ensure_checkout(entry, repo_dir, timeout=30)
        finally:
            gitops.subprocess.run = real_run
            root_logger.removeHandler(handler)
            root_logger.setLevel(prev_level)

        check("checkout has a working tree",
              os.path.isdir(os.path.join(checkout, "sub")))

        config_path = os.path.join(checkout, ".git", "config")
        with open(config_path, encoding="utf-8") as f:
            config_text = f.read()
        check("token absent from .git/config", FAKE_TOKEN not in config_text,
              extra=config_text if FAKE_TOKEN in config_text else "")

        check("clone + fetch + reset calls were captured",
              len(captured_calls) >= 3, extra=str(len(captured_calls)))
        argv_text = json.dumps(captured_calls)
        check("token absent from every subprocess argv",
              FAKE_TOKEN not in argv_text)

        check("token absent from captured stdout",
              FAKE_TOKEN not in stdout_buf.getvalue())
        check("token absent from captured stderr",
              FAKE_TOKEN not in stderr_buf.getvalue())
        check("token absent from log output",
              all(FAKE_TOKEN not in r for r in handler.records))

        print("== registry never persists auth_token to registry.json ==")
        data_dir = os.path.join(tmp, "data")
        ctx = TenantContext(tenant_id="t1")
        registry.upsert(data_dir, ctx, entry)
        registry_path = os.path.join(tenant_root(data_dir, ctx),
                                      "registry.json")
        with open(registry_path, encoding="utf-8") as f:
            raw = f.read()
        check("token absent from registry.json on disk",
              FAKE_TOKEN not in raw)
        reloaded = registry.load(data_dir, ctx)["byo"]
        check("reloaded entry has auth_token stripped",
              reloaded.auth_token is None)
        check("reloaded entry kept its other fields (additive-only)",
              reloaded.git_url == git_url)

        print("== precedence: auth_token wins over auth_env ==")
        os.environ["CODEINTEL_TEST_ENV_TOKEN"] = "env-token-should-not-be-used"
        try:
            entry2 = RepoEntry(repo_id="byo2", source_type="git",
                                git_url=git_url, auth_token=FAKE_TOKEN,
                                auth_env="CODEINTEL_TEST_ENV_TOKEN")
            repo_dir2 = os.path.join(tmp, "repo_state2")
            os.makedirs(repo_dir2, exist_ok=True)
            captured2: list[list[str]] = []
            gitops.subprocess.run = (
                lambda cmd, *a, **kw:
                (captured2.append(list(cmd)), real_run(cmd, *a, **kw))[1])
            gitops.ensure_checkout(entry2, repo_dir2, timeout=30)
            gitops.subprocess.run = real_run
            check("direct auth_token path cloned successfully",
                  os.path.isdir(os.path.join(repo_dir2, "checkout", ".git")))
            argv2_text = json.dumps(captured2)
            check("neither token nor env-token leaked to argv",
                  FAKE_TOKEN not in argv2_text
                  and "env-token-should-not-be-used" not in argv2_text)
        finally:
            gitops.subprocess.run = real_run
            del os.environ["CODEINTEL_TEST_ENV_TOKEN"]

        print("== pre-existing auth_env-only path is unaffected ==")
        # auth_env now requires the name be on the operator allowlist (H1 hardening).
        # The test env-var names are the legit per-tenant provisioning case, so allow them.
        os.environ["CODE_INTEL_ALLOWED_AUTH_ENV"] = (
            "CODEINTEL_TEST_ENV_TOKEN2,CODEINTEL_TEST_ENV_TOKEN_UNSET")
        os.environ["CODEINTEL_TEST_ENV_TOKEN2"] = "some-env-token"
        try:
            entry3 = RepoEntry(repo_id="byo3", source_type="git",
                                git_url=git_url,
                                auth_env="CODEINTEL_TEST_ENV_TOKEN2")
            repo_dir3 = os.path.join(tmp, "repo_state3")
            os.makedirs(repo_dir3, exist_ok=True)
            gitops.ensure_checkout(entry3, repo_dir3, timeout=30)
            check("auth_env-only clone still works unchanged",
                  os.path.isdir(os.path.join(repo_dir3, "checkout", ".git")))
        finally:
            del os.environ["CODEINTEL_TEST_ENV_TOKEN2"]

        try:
            entry4 = RepoEntry(repo_id="byo4", source_type="git",
                                git_url=git_url,
                                auth_env="CODEINTEL_TEST_ENV_TOKEN_UNSET")
            repo_dir4 = os.path.join(tmp, "repo_state4")
            os.makedirs(repo_dir4, exist_ok=True)
            gitops.ensure_checkout(entry4, repo_dir4, timeout=30)
            check("auth_env unset still raises GitOpsError", False)
        except gitops.GitOpsError as e:
            check("auth_env unset still raises GitOpsError (unchanged)",
                  "auth_env" in str(e))
        finally:
            os.environ.pop("CODE_INTEL_ALLOWED_AUTH_ENV", None)

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        subprocess.run = real_run
        gitops.subprocess.run = real_run
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
