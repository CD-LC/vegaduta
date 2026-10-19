"""Security tests for the two 2026-08-17 pentest findings on this service.

H1 - `auth_env` is a caller-supplied env-var NAME resolved against the
     process env and injected as the git basic-auth password toward a
     caller-controlled git_url. Un-allowlisted names (e.g.
     CODE_INTEL_INTERNAL_TOKEN) must be rejected with a validation error
     BEFORE any environment read or git/network call.

H2 - `source_type=local` paths must be confined to the REQUESTING tenant's
     own subtree (data/tenants/<tenant_id>/) via realpath resolution:
     reject /proc, /etc, symlink escapes AND another tenant's clones or
     SQLite indexes under the shared data root.

Both rejection paths raise gitops.GitOpsValidationError, which main.py maps
to HTTP 400 (plain GitOpsError - an actually-failed git op - stays 500).

Run: python3 tests/test_gitops_confinement.py   (from the code-intel directory)
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
# (gitops.validate_git_source allows https only). Widen it for this process,
# and approve file:// as an auth_env destination, before app.gitops is used.
os.environ.setdefault("CODE_INTEL_GIT_ALLOWED_SCHEMES", "https,file")
os.environ.setdefault("CODE_INTEL_AUTH_ENV_URL_PREFIXES", "file://")

from app import gitops
from app.gitops import GitOpsValidationError
from app.registry import RepoEntry
from app.tenancy import TenantContext, tenant_root

PASS = 0
FAIL = 0
FAKE_SECRET = "internal-secret-FAKE-zz-not-real"


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def make_source_repo(base: str) -> str:
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


def test_h1_auth_env_allowlist(tmp: str):
    print("== H1: auth_env allowlist ==")
    real_run = subprocess.run
    git_url = to_file_uri(make_source_repo(tmp))

    # -- reject: an un-allowlisted name (a real service secret) is refused
    #    BEFORE the env is resolved and before ANY subprocess/network call.
    os.environ["CODE_INTEL_INTERNAL_TOKEN"] = FAKE_SECRET
    calls: list[list[str]] = []
    gitops.subprocess.run = (
        lambda cmd, *a, **kw: (calls.append(list(cmd)), real_run(cmd, *a, **kw))[1])
    try:
        entry = RepoEntry(repo_id="h1-reject", source_type="git",
                          git_url=git_url,
                          auth_env="CODE_INTEL_INTERNAL_TOKEN")
        rdir = os.path.join(tmp, "h1-reject")
        os.makedirs(rdir, exist_ok=True)
        try:
            gitops.ensure_checkout(entry, rdir, timeout=30)
            check("un-allowlisted auth_env rejected", False)
        except GitOpsValidationError as e:
            check("un-allowlisted auth_env rejected (validation error)", True)
            check("rejection message does not leak the secret value",
                  FAKE_SECRET not in str(e))
        except gitops.GitOpsError:
            check("rejected with GitOpsValidationError (400), "
                  "not plain GitOpsError (500)", False)
        check("no git subprocess ran before the rejection", len(calls) == 0,
              extra=str(calls))
    finally:
        gitops.subprocess.run = real_run
        del os.environ["CODE_INTEL_INTERNAL_TOKEN"]

    # -- accept: the built-in name CODE_INTEL_GIT_TOKEN is allowed by default.
    os.environ["CODE_INTEL_GIT_TOKEN"] = "operator-git-token-FAKE"
    try:
        entry = RepoEntry(repo_id="h1-builtin", source_type="git",
                          git_url=git_url, auth_env="CODE_INTEL_GIT_TOKEN")
        rdir = os.path.join(tmp, "h1-builtin")
        os.makedirs(rdir, exist_ok=True)
        gitops.ensure_checkout(entry, rdir, timeout=30)
        check("built-in CODE_INTEL_GIT_TOKEN name accepted",
              os.path.isdir(os.path.join(rdir, "checkout", ".git")))
    finally:
        del os.environ["CODE_INTEL_GIT_TOKEN"]

    # -- accept: an operator-added name via CODE_INTEL_ALLOWED_AUTH_ENV.
    os.environ["CODE_INTEL_ALLOWED_AUTH_ENV"] = "GITHUB_TOKEN_TENANT_A"
    os.environ["GITHUB_TOKEN_TENANT_A"] = "tenant-a-pat-FAKE"
    try:
        entry = RepoEntry(repo_id="h1-operator", source_type="git",
                          git_url=git_url, auth_env="GITHUB_TOKEN_TENANT_A")
        rdir = os.path.join(tmp, "h1-operator")
        os.makedirs(rdir, exist_ok=True)
        gitops.ensure_checkout(entry, rdir, timeout=30)
        check("operator-allowlisted auth_env name accepted",
              os.path.isdir(os.path.join(rdir, "checkout", ".git")))
    finally:
        del os.environ["GITHUB_TOKEN_TENANT_A"]
        del os.environ["CODE_INTEL_ALLOWED_AUTH_ENV"]


def test_h2_local_path_confinement(tmp: str):
    print("== H2: local-path tenant-subtree confinement ==")
    data_dir = os.path.join(tmp, "data")
    ctx_a = TenantContext(tenant_id="tenant_a")
    ctx_b = TenantContext(tenant_id="tenant_b")
    troot_a = tenant_root(data_dir, ctx_a)
    troot_b = tenant_root(data_dir, ctx_b)

    # Tenant B has a "cloned repo" under the SHARED data root.
    b_checkout = os.path.join(troot_b, "repos", "rb", "checkout")
    os.makedirs(b_checkout, exist_ok=True)
    with open(os.path.join(b_checkout, "secret.py"), "w") as f:
        f.write("B_SECRET = 'tenant-b-only'\n")

    # Tenant A has a legit operator-provisioned dir in its OWN subtree.
    a_local = os.path.join(troot_a, "mounted-src")
    os.makedirs(a_local, exist_ok=True)
    with open(os.path.join(a_local, "ok.py"), "w") as f:
        f.write("def ok():\n    return 1\n")

    def local_entry(rid: str, path: str) -> RepoEntry:
        return RepoEntry(repo_id=rid, source_type="local", path=path)

    def expect_reject(name: str, path: str):
        try:
            gitops.ensure_checkout(local_entry("r", path),
                                   os.path.join(tmp, "unused"), timeout=5,
                                   data_root=troot_a)
            check(name, False, extra=f"accepted {path}")
        except GitOpsValidationError:
            check(name, True)

    # -- accept: a path inside the requesting tenant's own subtree.
    got = gitops.ensure_checkout(local_entry("ra", a_local),
                                 os.path.join(tmp, "unused"), timeout=5,
                                 data_root=troot_a)
    check("own-subtree local path accepted and returned as-is",
          got == a_local)

    # -- reject: an absolute path entirely outside the data root.
    outside = os.path.join(tmp, "outside-data-root")
    os.makedirs(outside, exist_ok=True)
    expect_reject("path outside the data root rejected", outside)

    # -- reject: ANOTHER TENANT'S subtree under the same shared data root
    #    (cross-tenant read of clones/SQLite indexes - the remaining H2 gap
    #    after the 2026-08-17 data-root-only confinement).
    expect_reject("another tenant's checkout under the shared data root "
                  "rejected", b_checkout)
    expect_reject("another tenant's whole subtree rejected", troot_b)

    # -- reject: traversal that textually starts inside but resolves outside.
    traversal = os.path.join(troot_a, "..", "tenant_b", "repos", "rb",
                             "checkout")
    expect_reject("dot-dot traversal out of the tenant subtree rejected",
                  traversal)

    # -- reject: a symlink INSIDE the tenant subtree pointing OUTSIDE it
    #    (realpath resolves it; skipped where symlinks need privileges).
    link = os.path.join(troot_a, "sneaky-link")
    try:
        os.symlink(outside, link, target_is_directory=True)
    except (OSError, NotImplementedError):
        print("  skip  symlink escape (symlinks unavailable on this host)")
    else:
        expect_reject("symlink escaping the tenant subtree rejected", link)

    # -- reject: a path that does not exist at all.
    try:
        gitops.ensure_checkout(
            local_entry("rmiss", os.path.join(troot_a, "no-such-dir")),
            os.path.join(tmp, "unused"), timeout=5, data_root=troot_a)
        check("missing local path rejected", False)
    except GitOpsValidationError:
        check("missing local path rejected (validation error)", True)


def test_h2_service_register(tmp: str):
    print("== H2: service.register enforces the same confinement ==")
    from app.service import CodeIntelService
    from app.settings import Settings

    data_dir = os.path.join(tmp, "svc-data")
    settings = Settings(data_dir=data_dir, auth_mode="internal",
                        internal_token="s3cret", default_embedder="hash")
    svc = CodeIntelService(settings)
    ctx_a = TenantContext(tenant_id="tenant_a")
    ctx_b = TenantContext(tenant_id="tenant_b")

    b_checkout = os.path.join(tenant_root(data_dir, ctx_b),
                              "repos", "rb", "checkout")
    os.makedirs(b_checkout, exist_ok=True)
    with open(os.path.join(b_checkout, "secret.py"), "w") as f:
        f.write("B_SECRET = 'tenant-b-only'\n")

    a_local = os.path.join(tenant_root(data_dir, ctx_a), "mounted-src")
    os.makedirs(a_local, exist_ok=True)
    with open(os.path.join(a_local, "ok.py"), "w") as f:
        f.write("def ok():\n    return 1\n")

    # -- reject: tenant A registering tenant B's clone as a local repo.
    try:
        svc.register(ctx_a, RepoEntry(repo_id="steal", source_type="local",
                                      path=b_checkout))
        check("register() rejects another tenant's subtree", False)
    except GitOpsValidationError:
        check("register() rejects another tenant's subtree (400-mapped)", True)

    # -- reject: tenant A registering an arbitrary host path.
    host_path = os.path.dirname(os.path.abspath(__file__))  # exists, outside
    try:
        svc.register(ctx_a, RepoEntry(repo_id="fsread", source_type="local",
                                      path=host_path))
        check("register() rejects a path outside the data root", False)
    except GitOpsValidationError:
        check("register() rejects a path outside the data root "
              "(400-mapped)", True)

    # -- accept: tenant A registering a path in its OWN subtree.
    entry = svc.register(ctx_a, RepoEntry(repo_id="mine", source_type="local",
                                          path=a_local))
    check("register() accepts a path in the tenant's own subtree",
          entry.repo_id == "mine")


def test_http_mapping():
    print("== main.py maps GitOpsValidationError to 400 ==")
    try:
        from app.main import app as fastapi_app
    except Exception as e:  # fastapi not installed in this env - don't fail
        print(f"  skip  app.main import unavailable ({type(e).__name__})")
        return
    check("400 handler registered for GitOpsValidationError",
          GitOpsValidationError in fastapi_app.exception_handlers)


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="codeintel-confinement-test-")
    real_run = subprocess.run
    try:
        test_h1_auth_env_allowlist(tmp)
        test_h2_local_path_confinement(tmp)
        test_h2_service_register(tmp)
        test_http_mapping()
        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        subprocess.run = real_run
        gitops.subprocess.run = real_run
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
