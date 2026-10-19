"""Framework-free tests for the ephemeral-project lifecycle (disposable
software): expires_at self-archive, the lazy expire_due sweep, extend/keep,
and that archiving is per-tenant like everything else in the registry.

Run: python3 -m tests.test_ephemeral   (from the app-builder directory)
"""

from __future__ import annotations

import os
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.projects import (  # noqa: E402
    EPHEMERAL,
    PERMANENT,
    Project,
    ProjectArchivedError,
    archive,
    expire_due,
    extend,
    get,
    is_archived,
    is_expired,
    keep,
    require_live,
    upsert,
)
from app.tenancy import TenantContext  # noqa: E402

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


def make_project(project_id: str, lifecycle: str = PERMANENT,
                 expires_at: float | None = None) -> Project:
    return Project(project_id=project_id, tenant_id="t",
                   template_id="node-react", status="ready",
                   code_intel_repo_id=project_id,
                   lifecycle=lifecycle, expires_at=expires_at)


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-ephemeral-")
    try:
        ctx = TenantContext(tenant_id="t")
        now = time.time()

        print("== expiry predicate ==")
        check("permanent projects never expire",
              not is_expired(make_project("a"), now))
        check("ephemeral with future expiry is not expired",
              not is_expired(make_project("b", EPHEMERAL, now + 60), now))
        check("ephemeral past expiry is expired",
              is_expired(make_project("c", EPHEMERAL, now - 1), now))
        check("ephemeral without expires_at is not expired",
              not is_expired(make_project("d", EPHEMERAL, None), now))

        print("== lazy sweep ==")
        upsert(tmp, ctx, make_project("stale", EPHEMERAL, now - 3600))
        upsert(tmp, ctx, make_project("fresh", EPHEMERAL, now + 3600))
        upsert(tmp, ctx, make_project("perm"))
        expired = expire_due(tmp, ctx)
        check("sweep archives exactly the expired project",
              [p.project_id for p in expired] == ["stale"])
        check("expired project is archived on disk",
              is_archived(get(tmp, ctx, "stale")))
        check("live ephemeral project untouched",
              not is_archived(get(tmp, ctx, "fresh")))
        check("permanent project untouched",
              not is_archived(get(tmp, ctx, "perm")))
        check("a second sweep finds nothing",
              expire_due(tmp, ctx) == [])

        print("== archived projects refuse mutation ==")
        try:
            require_live(tmp, ctx, "stale")
            check("require_live refuses archived project", False)
        except ProjectArchivedError:
            check("require_live refuses archived project", True)
        check("archived record is still readable",
              get(tmp, ctx, "stale") is not None)

        print("== extend / keep ==")
        extended = extend(tmp, ctx, "fresh", 24)
        check("extend pushes expires_at ~24h out",
              abs(extended.expires_at - (now + 24 * 3600 + 3600)) < 7200
              or extended.expires_at > now + 23 * 3600)
        try:
            extend(tmp, ctx, "perm", 24)
            check("extend refuses permanent project", False)
        except ValueError:
            check("extend refuses permanent project", True)

        kept = keep(tmp, ctx, "fresh")
        check("keep makes the project permanent", kept.lifecycle == PERMANENT)
        check("keep clears the expiry", kept.expires_at is None)
        kept2 = keep(tmp, ctx, "stale")
        check("keep un-archives an expired project", not is_archived(kept2))
        check("kept project is live again",
              require_live(tmp, ctx, "stale").project_id == "stale")

        print("== archive is per-tenant ==")
        ctx_b = TenantContext(tenant_id="t2")
        upsert(tmp, ctx_b, make_project("shared-name", EPHEMERAL, now - 10))
        archive(tmp, ctx_b, "shared-name")
        check("tenant B's archive does not leak to tenant A",
              get(tmp, ctx, "shared-name") is None)
        check("tenant B's record archived",
              is_archived(get(tmp, ctx_b, "shared-name")))

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
