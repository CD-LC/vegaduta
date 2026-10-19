"""Framework-free tests: the project registry's tenant isolation and CRUD,
mirroring code-intel/tests/test_service.py's "same repo_id, different
tenants" pattern.

Run: python3 -m tests.test_projects   (from the app-builder directory)
"""

from __future__ import annotations

import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.projects import (  # noqa: E402
    Project,
    ProjectNotFoundError,
    delete,
    get,
    get_or_404,
    load,
    upsert,
)
from app.tenancy import TenantContext, TenantValidationError  # noqa: E402

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


def make_project(project_id: str, tenant_id: str, template_id: str) -> Project:
    return Project(project_id=project_id, tenant_id=tenant_id,
                   template_id=template_id, status="ready",
                   code_intel_repo_id=project_id)


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-projects-")
    try:
        ctx_a = TenantContext(tenant_id="tenant_a")
        ctx_b = TenantContext(tenant_id="tenant_b")

        print("== tenant isolation: same project_id, different tenants ==")
        upsert(tmp, ctx_a, make_project("app", "tenant_a", "node-react"))
        upsert(tmp, ctx_b, make_project("app", "tenant_b", "python-fastapi"))

        a = get(tmp, ctx_a, "app")
        b = get(tmp, ctx_b, "app")
        check("tenant A sees its own project", a is not None
              and a.template_id == "node-react")
        check("tenant B sees its own project", b is not None
              and b.template_id == "python-fastapi")
        check("tenant B's record isn't tenant A's",
              b.template_id != a.template_id)

        check("tenant A's registry file only lists its own project",
              list(load(tmp, ctx_a).keys()) == ["app"])
        check("tenant B's registry file only lists its own project",
              list(load(tmp, ctx_b).keys()) == ["app"])

        print("== CRUD ==")
        try:
            get_or_404(tmp, ctx_a, "does-not-exist")
            check("get_or_404 raises for unknown project", False)
        except ProjectNotFoundError:
            check("get_or_404 raises for unknown project", True)

        check("delete returns True for an existing project",
              delete(tmp, ctx_a, "app") is True)
        check("delete returns False for an already-deleted project",
              delete(tmp, ctx_a, "app") is False)
        check("tenant B's project survives tenant A's delete",
              get(tmp, ctx_b, "app") is not None)

        print("== validation ==")
        try:
            upsert(tmp, ctx_a, make_project("../evil", "tenant_a", "x"))
            check("path-traversal project id rejected", False)
        except TenantValidationError:
            check("path-traversal project id rejected", True)

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
