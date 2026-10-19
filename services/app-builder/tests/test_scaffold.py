"""Framework-free tests for app/scaffold.py's LEGACY local-checkout path.

Uses the REAL templates/ catalog shipped with this service (small, real
files — see templates/README expectations in app-builder/README.md) but
mocks every codeintel_client call so no code-intel instance needs to be
running, and mocks generate.modify_project so the initial-prompt path
doesn't need a running core or network access.

## Scope note (2026-07-30 bring-your-own-repo pivot)

The DEFAULT path is now "push to a branch in the tenant's own GitHub repo and
open a PR" — that is covered by tests/test_byo_repo.py, which is the
acceptance gate for the pivot. This file now pins the RETAINED fallback:
`APP_BUILDER_LOCAL_PREVIEW_ENABLED=true` with no GitHub repo connected, i.e.
bare local dev with zero external dependencies. Every Settings() here
therefore sets `local_preview_enabled=True` explicitly; the first test below
asserts that WITHOUT it, and without a connected repo, creating a project is
refused rather than silently falling back.

Run: python3 -m tests.test_scaffold   (from the app-builder directory)
"""

from __future__ import annotations

import os
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from app import generate, projects, scaffold  # noqa: E402
from app.codeintel_client import client as ci_client  # noqa: E402
from app.projects import ProjectExistsError  # noqa: E402
from app.scaffold import LocalPreviewDisabledError, TemplateNotFoundError  # noqa: E402
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


class _Recorder:
    def __init__(self):
        self.calls: list[tuple] = []

    def __call__(self, *args, **kwargs):
        self.calls.append((args, kwargs))
        return self.result

    result = {}


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-scaffold-")
    templates_dir = os.path.join(ROOT, "templates")
    # See the module docstring: this file pins the legacy local path, which
    # only exists behind this flag now.
    settings = Settings(data_dir=tmp, templates_dir=templates_dir,
                        local_preview_enabled=True)
    default_settings = Settings(data_dir=tmp, templates_dir=templates_dir)
    ctx = TenantContext(tenant_id="tenant_a")

    orig_register = ci_client.register_repo
    orig_index = ci_client.index_repo
    orig_modify = generate.modify_project

    register_calls = _Recorder()
    register_calls.result = {"repo_id": "app", "source_type": "local"}
    index_calls = _Recorder()
    index_calls.result = {"status": "ok"}
    generate_calls = _Recorder()
    generate_calls.result = {"diff": "", "files_changed": [], "success": True}

    ci_client.register_repo = register_calls
    ci_client.index_repo = index_calls
    generate.modify_project = generate_calls

    try:
        print("== the legacy local path is OFF by default ==")
        try:
            scaffold.create_project(ctx, "refused", "node-react", None,
                                    default_settings)
            check("local scaffold refused unless explicitly enabled", False)
        except LocalPreviewDisabledError:
            check("local scaffold refused unless explicitly enabled", True)
        check("nothing was registered with code-intel for the refused call",
              len(register_calls.calls) == 0)

        print("== scaffold without an initial prompt ==")
        project = scaffold.create_project(ctx, "app", "node-react", None, settings)
        check("project persisted as ready", project.status == "ready")
        check("code_intel_repo_id == project_id (1:1)",
              project.code_intel_repo_id == "app")

        pdir = os.path.join(tmp, "tenants", "tenant_a", "projects", "app")
        check("template files were copied",
              os.path.isfile(os.path.join(pdir, "package.json")))
        check("manifest copied alongside the template",
              os.path.isfile(os.path.join(pdir, ".appbuilder.json")))
        check("project dir is a real git repo",
              os.path.isdir(os.path.join(pdir, ".git")))

        check("code-intel register_repo called once", len(register_calls.calls) == 1)
        _, reg_kwargs = register_calls.calls[0]
        check("register_repo called with the project's own checkout path",
              reg_kwargs.get("path") == pdir, extra=str(reg_kwargs))
        check("code-intel index_repo called once", len(index_calls.calls) == 1)
        check("no prompt -> generate.modify_project not called",
              len(generate_calls.calls) == 0)

        print("== scaffold WITH an initial prompt ==")
        project2 = scaffold.create_project(
            ctx, "app-with-prompt", "python-fastapi",
            "make it say hello in French", settings)
        check("prompt scaffold also ready", project2.status == "ready")
        check("generate.modify_project called exactly once for the initial prompt",
              len(generate_calls.calls) == 1)
        g_args, _ = generate_calls.calls[0]
        check("generate called with the right project_id and instruction",
              g_args[1] == "app-with-prompt"
              and g_args[2] == "make it say hello in French",
              extra=str(g_args))

        print("== duplicate project_id rejected ==")
        try:
            scaffold.create_project(ctx, "app", "static-html", None, settings)
            check("duplicate project_id rejected", False)
        except ProjectExistsError:
            check("duplicate project_id rejected", True)

        print("== unknown template rejected ==")
        try:
            scaffold.create_project(ctx, "brand-new", "no-such-template",
                                    None, settings)
            check("unknown template rejected", False)
        except TemplateNotFoundError:
            check("unknown template rejected", True)

        print("== tenant isolation: same project_id, different tenants ==")
        ctx_b = TenantContext(tenant_id="tenant_b")
        register_calls.calls.clear()
        project_b = scaffold.create_project(ctx_b, "app", "static-html", None, settings)
        check("tenant B can use the same project_id as tenant A",
              project_b.template_id == "static-html")
        pdir_b = os.path.join(tmp, "tenants", "tenant_b", "projects", "app")
        check("tenant B's checkout is a separate directory from tenant A's",
              pdir_b != pdir and os.path.isdir(pdir_b))
        check("tenant A's project is untouched",
              projects.get(tmp, ctx, "app").template_id == "node-react")

        print("== templates catalog ==")
        catalog = scaffold.list_templates(settings)
        ids = {t["template_id"] for t in catalog}
        check("all three starter templates are listed",
              {"node-react", "python-fastapi", "static-html"} <= ids,
              extra=str(ids))

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        ci_client.register_repo = orig_register
        ci_client.index_repo = orig_index
        generate.modify_project = orig_modify
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
