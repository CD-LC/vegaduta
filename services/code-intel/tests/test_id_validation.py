"""Id allowlists must reject a trailing newline.

``re.match(r"^...$", s)`` also accepts ``s`` with ONE trailing "\\n" (``$``
matches before a final newline), so "abc\\n" used to pass the tenant, repo,
workspace and git-ref checks. The validators now use ``fullmatch``.

Run: python3 -m tests.test_id_validation   (from the code-intel directory)
"""

from __future__ import annotations

import os
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import gitops, workspace
from app.gitops import GitOpsValidationError
from app.tenancy import TenantValidationError, validate_repo_id, validate_tenant_id

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


def rejects(fn, value, exc=TenantValidationError):
    try:
        fn(value)
    except exc:
        return True
    return False


def main() -> int:
    print("== tenant id ==")
    check("plain id accepted", validate_tenant_id("acme") == "acme")
    check("trailing newline rejected", rejects(validate_tenant_id, "acme\n"))

    print("== repo id ==")
    check("plain id accepted", validate_repo_id("my.repo-1") == "my.repo-1")
    check("trailing newline rejected", rejects(validate_repo_id, "my.repo-1\n"))

    print("== workspace id ==")
    ws_dir = tempfile.mkdtemp(prefix="codeintel-wsid-")
    try:
        ok = workspace._ws_path(ws_dir, "ws-0123456789ab")
        check("well-formed id resolves", ok.endswith("ws-0123456789ab"))
        check(
            "trailing newline rejected",
            rejects(
                lambda v: workspace._ws_path(ws_dir, v),
                "ws-0123456789ab\n",
                workspace.WorkspaceError,
            ),
        )
    finally:
        shutil.rmtree(ws_dir, ignore_errors=True)

    print("== git ref ==")
    check(
        "trailing newline rejected before any network lookup",
        rejects(
            lambda v: gitops.validate_git_source("https://github.com/o/r.git", v),
            "main\n",
            GitOpsValidationError,
        ),
    )

    print(f"\n{PASS} passed, {FAIL} failed")
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
