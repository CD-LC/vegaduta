"""WS4 - platform/code-intel approval reconciliation.

Verifies that an internal-token caller (core, which has already run the
platform's own admin-gated HumanApprovalGateService) does NOT dead-end on
code-intel's own second ASK gate, while:
  - a hard DENY still fires even for that trusted caller, and
  - a real browser user (no internal token, no admin role) still escalates
    exactly as before.

Run: python tests/test_approval_reconcile.py
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.registry import RepoEntry
from app.service import (
    ApprovalNotAuthorizedError,
    ApprovalRequiredError,
    CodeIntelService,
    PolicyDeniedError,
)
from app.settings import Settings
from app.tenancy import TenantContext, tenant_root

PASS = 0
FAIL = 0


def check(name, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def make_repo(base):
    d = os.path.join(base, "r")
    os.makedirs(os.path.join(d, "src"))
    open(os.path.join(d, "src", "a.py"), "w").write("def f():\n    return 1\n")
    subprocess.run(["git", "init", "-q", d], check=True)
    subprocess.run(["git", "-C", d, "config", "user.email", "t@t"], check=True)
    subprocess.run(["git", "-C", d, "config", "user.name", "t"], check=True)
    subprocess.run(["git", "-C", d, "add", "-A"], check=True)
    subprocess.run(["git", "-C", d, "commit", "-qm", "i"], check=True)
    return d


def main():
    print("== approval reconciliation (internal-token = platform pre-gated) ==")
    tmp = tempfile.mkdtemp(prefix="ci-reconcile-")
    try:
        # auth_mode=keycloak so _is_admin is NOT the trivial 'none' shortcut -
        # the internal-token trust path is what must carry the trusted caller.
        settings = Settings(data_dir=os.path.join(tmp, "data"),
                            auth_mode="keycloak", default_embedder="hash",
                            allow_exec=True, exec_timeout_seconds=60)
        svc = CodeIntelService(settings)

        core = TenantContext(tenant_id="t1", subject="core",
                             via_internal_token=True)         # trusted platform
        user = TenantContext(tenant_id="t1", subject="bob",
                             roles=frozenset())               # browser, non-admin
        repo = make_repo(tenant_root(settings.data_dir, core))

        svc.register(core, RepoEntry(repo_id="r", source_type="local", path=repo))
        ws = svc.workspace_create(core, "r")["workspace_id"]
        files = [{"path": "src/a.py", "content": "def f():\n    return 2\n"}]

        # 1. internal-token apply that would ASK in test_gated now proceeds
        #    (no dead-end) and actually writes.
        done = svc.workspace_apply(core, "r", ws, files)
        check("internal apply proceeds without 2nd approval",
              done.get("written") == ["src/a.py"], done)

        # 2. internal-token run of a non-allow-listed command proceeds too.
        run = svc.workspace_run(core, "r", ws, "python -c \"print(1)\"")
        check("internal run proceeds without 2nd approval",
              run.get("success") is not None, run)

        # 3. a hard DENY still fires for the trusted caller.
        try:
            svc.workspace_apply(core, "r", ws,
                                [{"path": ".git/config", "content": "x"}])
            check("internal caller still hard-denied (protected path)", False)
        except PolicyDeniedError:
            check("internal caller still hard-denied (protected path)", True)
        try:
            svc.workspace_run(core, "r", ws, "git push origin main")
            check("internal caller still hard-denied (deny pattern)", False)
        except PolicyDeniedError:
            check("internal caller still hard-denied (deny pattern)", True)

        # 4. a real browser user (no internal token, no admin) still escalates.
        approval_id = None
        try:
            svc.workspace_apply(user, "r", ws, files)
            check("browser user still escalates", False)
        except ApprovalRequiredError as e:
            approval_id = e.approval_id
            check("browser user still escalates", bool(approval_id))

        # 5. that non-admin browser user still cannot self-resolve it.
        try:
            svc.resolve_approval(user, approval_id, True)
            check("non-admin cannot self-approve", False)
        except ApprovalNotAuthorizedError:
            check("non-admin cannot self-approve", True)

        # 6. internal-token caller (trusted) CAN resolve it (admin-equivalent).
        entry = svc.resolve_approval(core, approval_id, True)
        check("internal-token caller can resolve", entry["status"] == "approved")

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
