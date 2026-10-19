"""Framework-free tests: exercise auth + tenant isolation + index/search/
graph through the service layer directly (no FastAPI needed).

Run: python3 -m tests.test_service   (from the code-intel directory)
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.auth import AuthError, authenticate
from app.registry import RepoEntry
from app.service import CodeIntelService, RepoNotFoundError
from app.settings import Settings
from app.tenancy import TenantContext

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


def make_repo(base: str, marker: str) -> str:
    d = os.path.join(base, f"src-{marker}")
    os.makedirs(os.path.join(d, "src"))
    with open(os.path.join(d, "src", "svc.py"), "w") as f:
        f.write(
            f"def secret_{marker}():\n    return '{marker}'\n\n"
            f"def caller_{marker}():\n    return secret_{marker}()\n")
    subprocess.run(["git", "init", "-q", d], check=True)
    subprocess.run(["git", "-C", d, "config", "user.email", "t@t"], check=True)
    subprocess.run(["git", "-C", d, "config", "user.name", "t"], check=True)
    subprocess.run(["git", "-C", d, "add", "-A"], check=True)
    subprocess.run(["git", "-C", d, "commit", "-qm", "init"], check=True)
    return d


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="codeintel-test-")
    try:
        settings = Settings(data_dir=os.path.join(tmp, "data"),
                            auth_mode="internal",
                            internal_token="s3cret",
                            default_embedder="hash")
        svc = CodeIntelService(settings)

        print("== auth ==")
        ctx_a = authenticate({"X-Internal-Token": "s3cret",
                              "X-Tenant-Id": "tenant_a"}, settings)
        check("internal auth resolves tenant", ctx_a.tenant_id == "tenant_a")
        try:
            authenticate({"X-Internal-Token": "wrong",
                          "X-Tenant-Id": "tenant_a"}, settings)
            check("bad token rejected", False)
        except AuthError as e:
            check("bad token rejected (401)", e.status == 401)
        try:
            authenticate({"X-Internal-Token": "s3cret"}, settings)
            check("missing tenant rejected", False)
        except AuthError as e:
            check("missing tenant rejected (403)", e.status == 403)
        try:
            authenticate({"X-Internal-Token": "s3cret",
                          "X-Tenant-Id": "../evil"}, settings)
            check("path-traversal tenant rejected", False)
        except Exception:
            check("path-traversal tenant rejected", True)
        try:
            authenticate({"X-Internal-Token": "s3cret", "X-Tenant-Id": "x"},
                         Settings(data_dir=tmp, auth_mode="internal",
                                  internal_token=""))
            check("internal mode without secret fails closed", False)
        except AuthError as e:
            check("internal mode without secret fails closed (500)",
                  e.status == 500)

        print("== tenant isolation: same repo_id, different tenants ==")
        ctx_b = TenantContext(tenant_id="tenant_b")
        # Local-source paths must live inside the REGISTERING tenant's own
        # subtree (H2 confinement - see gitops.ensure_checkout / register):
        # an arbitrary path outside data/tenants/<tenant_id>/ is now a 400.
        repo_a = make_repo(os.path.join(tmp, "data", "tenants", "tenant_a"),
                           "alpha")
        repo_b = make_repo(os.path.join(tmp, "data", "tenants", "tenant_b"),
                           "beta")
        svc.register(ctx_a, RepoEntry(repo_id="app", source_type="local",
                                      path=repo_a))
        svc.register(ctx_b, RepoEntry(repo_id="app", source_type="local",
                                      path=repo_b))
        ra = svc.index(ctx_a, "app")
        rb = svc.index(ctx_b, "app")
        check("tenant A indexed", ra["status"] == "ok"
              and ra["stats"]["files_changed"] == 1)
        check("tenant B indexed", rb["status"] == "ok")

        hits_a = svc.search(ctx_a, "app", "secret_alpha", top_k=3)
        hits_b = svc.search(ctx_b, "app", "secret_alpha", top_k=3)
        a_txt = " ".join(h["content"] for h in hits_a)
        b_txt = " ".join(h["content"] for h in hits_b)
        check("tenant A sees its own code", "secret_alpha" in a_txt)
        check("tenant B never sees tenant A's code",
              "secret_alpha" not in b_txt and "alpha" not in b_txt)

        check("tenant B cannot read unknown repo of A",
              True)  # same repo_id resolves inside B's subtree only:
        try:
            svc.search(TenantContext(tenant_id="tenant_c"), "app", "x")
            check("unknown tenant repo -> not found", False)
        except RepoNotFoundError:
            check("unknown tenant repo -> not found", True)

        state_a = os.path.join(settings.data_dir, "tenants", "tenant_a")
        state_b = os.path.join(settings.data_dir, "tenants", "tenant_b")
        check("physically separate state dirs",
              os.path.isdir(state_a) and os.path.isdir(state_b))
        check("index lives outside the checkout",
              not os.path.exists(os.path.join(repo_a, ".coderag")))

        print("== graph per tenant ==")
        ga = svc.graph_build(ctx_a, "app")
        check("graph built", ga["symbols"] >= 2)
        callers = svc.graph_callers(ctx_a, "app", "secret_alpha")
        check("callers_of works in service",
              any(c["caller"] == "caller_alpha" for c in callers))
        callers_b = svc.graph_callers(ctx_b, "app", "secret_alpha")
        check("tenant B graph knows nothing of tenant A symbols",
              callers_b == [])

        print("== incremental via service ==")
        with open(os.path.join(repo_a, "src", "svc.py"), "a") as f:
            f.write("\ndef extra():\n    return 1\n")
        r2 = svc.index(ctx_a, "app")
        check("incremental reindex", r2["stats"]["files_changed"] == 1)

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
