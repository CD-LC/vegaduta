"""preview.py hands the TENANT to ingress (routes are per tenant) and removes a
route registered before namespacing under its old id.

Run: python3 -m tests.test_preview_routes   (from the app-builder directory)
"""

from __future__ import annotations

import os
import shutil
import sys
import tempfile
from types import SimpleNamespace

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import ingress, preview, projects  # noqa: E402
from app.settings import Settings  # noqa: E402
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


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-preview-")
    saved = (
        preview.ci_client,
        preview.project_manifest,
        ingress.register_preview_route,
        ingress.deregister_preview_route,
    )
    try:
        settings = Settings(
            data_dir=tmp,
            local_preview_enabled=True,
            preview_path_base_url="http://localhost:8899",
            preview_domain="preview.example.com",
        )
        ctx = TenantContext(tenant_id="tenant_a")
        projects.upsert(
            tmp,
            ctx,
            projects.Project(
                project_id="shop",
                tenant_id="tenant_a",
                template_id="node-react",
                status="ready",
                code_intel_repo_id="shop",
            ),
        )

        calls: list[tuple] = []
        preview.project_manifest = lambda *_a, **_k: {"run": "npm start", "port": 3000}
        preview.ci_client = SimpleNamespace(
            create_workspace=lambda *_a: {"workspace_id": "ws-0123456789ab"},
            create_process=lambda *_a, **_k: {"process_id": "p1", "host": "10.0.0.5", "port": 3000},
            delete_process=lambda *_a: None,
            delete_workspace=lambda *_a: None,
        )

        def fake_register(*args, **kwargs):
            calls.append(("register", args, kwargs))
            return "http://localhost:8899/preview/KEY/shop/"

        def fake_deregister(*args, **kwargs):
            calls.append(("deregister", args, kwargs))

        ingress.register_preview_route = fake_register
        ingress.deregister_preview_route = fake_deregister

        print("== start_preview passes the tenant id to ingress ==")
        preview.start_preview(ctx, "shop", settings)
        reg = calls[0]
        check(
            "register called with (tenant_id, project_id, host, port)",
            reg[1][:4] == ("tenant_a", "shop", "10.0.0.5", 3000),
            extra=str(reg),
        )

        print("== stop_preview of a namespaced preview ==")
        calls.clear()
        preview.stop_preview(ctx, "shop", settings)
        dereg = calls[0]
        check(
            "deregister called with the tenant id and project id",
            dereg[1][:2] == ("tenant_a", "shop"),
            extra=str(dereg),
        )
        check(
            "namespaced URL -> not treated as legacy",
            dereg[2].get("legacy") is False,
            extra=str(dereg),
        )

        print("== stop_preview of a preview started before namespacing ==")
        project = projects.get(tmp, ctx, "shop")
        project.preview = {
            "process_id": "p0",
            "workspace_id": "ws-0123456789ab",
            "preview_url": "http://localhost:8899/preview/shop/",
        }
        projects.upsert(tmp, ctx, project)
        calls.clear()
        preview.stop_preview(ctx, "shop", settings)
        check(
            "old-shape URL -> legacy=True (old route id is removed)",
            calls[0][2].get("legacy") is True,
            extra=str(calls[0]),
        )

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        (
            preview.ci_client,
            preview.project_manifest,
            ingress.register_preview_route,
            ingress.deregister_preview_route,
        ) = saved
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
