"""Framework-free tests for what an edit shows the model (generate.py's
gather_edit_context / drop_partial_writes) and for the corrective retry
keeping its inputs.

Run: python -m tests.test_edit_context   (from the app-builder directory)
"""

from __future__ import annotations

import os
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from app import generate, projects  # noqa: E402
from app.codeintel_client import client as ci_client  # noqa: E402
from app.settings import Settings  # noqa: E402
from app.tenancy import TenantContext, project_root  # noqa: E402

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


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-editctx-")
    settings = Settings(data_dir=tmp)
    ctx = TenantContext(tenant_id="tenant_a")
    orig_search = ci_client.search
    try:
        pdir = project_root(settings.data_dir, ctx, "p1")
        os.makedirs(os.path.join(pdir, "src"), exist_ok=True)
        with open(os.path.join(pdir, "src", "App.jsx"), "w", encoding="utf-8") as f:
            f.write("line1\nline2\nline3\n")
        with open(os.path.join(pdir, "src", "huge.js"), "w", encoding="utf-8") as f:
            f.write("x" * (generate._MAX_FULL_FILE_CHARS + 10))
        project = projects.Project(project_id="p1", tenant_id="tenant_a",
                                   template_id="node-react", status="ready",
                                   code_intel_repo_id="p1")

        ci_client.search = lambda c, pid, q, **kw: [
            {"path": "src/App.jsx", "content": "line2"},
            {"path": "src/App.jsx", "content": "line3"},
            {"path": "src/huge.js", "content": "xxx"},
        ]
        print("== retrieved paths are re-read whole; oversize stays partial ==")
        ec = generate.gather_edit_context(ctx, project, "change it", settings)
        by = {f["path"]: f for f in ec["files"]}
        check("one entry per path", len(ec["files"]) == 2, str(ec["files"]))
        check("small file sent whole", by["src/App.jsx"]["content"] == "line1\nline2\nline3\n"
              and by["src/App.jsx"]["full"] is True)
        check("huge file stays excerpts", by["src/huge.js"]["full"] is False
              and by["src/huge.js"]["content"] == "xxx")
        check("project file list present", "src/App.jsx" in ec["project_files"])

        print("== writes to partial files are dropped ==")
        kept, dropped = generate.drop_partial_writes(
            [{"path": "src/App.jsx", "content": "new"},
             {"path": "src/huge.js", "content": "clobber"}], ec["files"])
        check("full-file write kept", [f["path"] for f in kept] == ["src/App.jsx"])
        check("partial-file write dropped", dropped == ["src/huge.js"])

        print("== search failure still yields a file list ==")
        from app.codeintel_client import CodeIntelClientError

        def boom(*a, **kw):
            raise CodeIntelClientError(503, "down")
        ci_client.search = boom
        ec2 = generate.gather_edit_context(ctx, project, "x", settings)
        check("no files, but project list", ec2["files"] == [] and ec2["project_files"])
    finally:
        ci_client.search = orig_search
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)

    print(f"\n{PASS} passed, {FAIL} failed")
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
