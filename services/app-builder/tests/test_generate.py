"""Framework-free tests for app/generate.py's orchestration: apply -> run ->
(on failure) ONE corrective retry -> promote-or-not. Every code-intel call
and the core generate-files call itself (`generate._generate_files`) are
mocked in the orchestration tests below, so those need neither a running
code-intel instance nor a running core. `_generate_files` itself (the httpx
call to core's `POST /api/internal/app-builder/generate-files`) is covered
separately near the bottom by mocking `httpx.request`, the same way
tests/test_ingress.py mocks it for ingress.py's `_admin_request`.

Run: python3 -m tests.test_generate   (from the app-builder directory)
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

import httpx  # noqa: E402

from app import generate, projects  # noqa: E402
from app.codeintel_client import client as ci_client  # noqa: E402
from app.generate import GenerateError  # noqa: E402
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


class _FakeResp:
    """Stands in for an httpx.Response, for mocking `httpx.request` the same
    way tests/test_ingress.py's FakeResponse does."""

    def __init__(self, status_code: int, body):
        self.status_code = status_code
        self._body = body
        self.content = json.dumps(body).encode()
        self.text = json.dumps(body)

    def json(self):
        return self._body


def _git(args: list[str], cwd: str) -> None:
    subprocess.run(["git", *args], cwd=cwd, check=True,
                   capture_output=True, text=True, timeout=30)


def _make_project(settings: Settings, ctx: TenantContext, project_id: str) -> str:
    pdir = project_root(settings.data_dir, ctx, project_id)
    with open(os.path.join(pdir, ".appbuilder.json"), "w", encoding="utf-8") as f:
        json.dump({"name": "Test Stack", "install": "", "run": "start",
                  "build": "", "test": "npm test", "port": 3000}, f)
    with open(os.path.join(pdir, "app.txt"), "w", encoding="utf-8") as f:
        f.write("original content\n")
    _git(["init", "-q"], pdir)
    _git(["config", "user.email", "t@t"], pdir)
    _git(["config", "user.name", "t"], pdir)
    _git(["add", "-A"], pdir)
    _git(["commit", "-q", "-m", "init"], pdir)

    projects.upsert(settings.data_dir, ctx, projects.Project(
        project_id=project_id, tenant_id=ctx.tenant_id,
        template_id="node-react", status="ready",
        code_intel_repo_id=project_id))
    return pdir


def _run_sequence(results):
    it = iter(results)

    def fake_run(ctx, project_id, ws_id, command, **kwargs):
        return next(it)
    return fake_run


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-generate-")
    settings = Settings(data_dir=tmp)
    ctx = TenantContext(tenant_id="tenant_a")

    orig = {
        "search": ci_client.search,
        "create_workspace": ci_client.create_workspace,
        "apply_files": ci_client.apply_files,
        "run": ci_client.run,
        "diff": ci_client.diff,
        "delete_workspace": ci_client.delete_workspace,
        "index_repo": ci_client.index_repo,
        "_generate_files": generate._generate_files,
    }

    def restore():
        ci_client.search = orig["search"]
        ci_client.create_workspace = orig["create_workspace"]
        ci_client.apply_files = orig["apply_files"]
        ci_client.run = orig["run"]
        ci_client.diff = orig["diff"]
        ci_client.delete_workspace = orig["delete_workspace"]
        ci_client.index_repo = orig["index_repo"]
        generate._generate_files = orig["_generate_files"]

    try:
        # Static stand-ins that don't vary per test.
        ci_client.search = lambda ctx, pid, q, **kw: []
        ci_client.create_workspace = lambda ctx, pid: {"workspace_id": "ws-1"}
        ci_client.diff = lambda ctx, pid, ws: {"diff": "fake diff"}

        apply_calls = []
        ci_client.apply_files = (
            lambda ctx, pid, ws, files, **kw: apply_calls.append(files) or {})
        delete_calls = []
        ci_client.delete_workspace = (
            lambda ctx, pid, ws: delete_calls.append(ws))
        index_calls = []
        ci_client.index_repo = lambda ctx, pid, **kw: index_calls.append(pid)

        print("== succeeds on the first run, no retry needed ==")
        pdir = _make_project(settings, ctx, "proj-ok")
        gen_calls = []

        def fake_gen_ok(ctx, instruction, passages, manifest,
                        prior_failure=None, context=None, images=None,
                        settings=None, **_kw):
            gen_calls.append(prior_failure)
            return [{"path": "app.txt", "content": "generated once\n"}]

        generate._generate_files = fake_gen_ok
        ci_client.run = _run_sequence([{"success": True, "final": {}}])
        apply_calls.clear()

        result = generate.modify_project(ctx, "proj-ok", "say hi", settings)
        check("reports success", result["success"] is True)
        check("model called exactly once (no retry)", len(gen_calls) == 1)
        check("apply_files called exactly once", len(apply_calls) == 1)
        check("changed file promoted to the real checkout",
              open(os.path.join(pdir, "app.txt")).read() == "generated once\n")
        check("a commit was made in the project's checkout",
              "AI: say hi" in subprocess.run(
                  ["git", "log", "--oneline", "-1"], cwd=pdir,
                  capture_output=True, text=True).stdout)
        check("workspace deleted after generate", delete_calls == ["ws-1"])
        check("re-indexed via code-intel", index_calls == ["proj-ok"])

        print("== fails once, corrective retry succeeds ==")
        pdir2 = _make_project(settings, ctx, "proj-retry")
        gen_calls2 = []
        retry_inputs = []

        def fake_gen_retry(ctx, instruction, passages, manifest,
                           prior_failure=None, context=None, images=None,
                        settings=None, **_kw):
            gen_calls2.append(prior_failure)
            retry_inputs.append({"context": context, "images": images,
                                 "passages": passages,
                                 "full": _kw.get("full_paths")})
            if prior_failure is None:
                return [{"path": "app.txt", "content": "attempt 1\n"}]
            return [{"path": "app.txt", "content": "attempt 2 fixed\n"}]

        generate._generate_files = fake_gen_retry
        ci_client.run = _run_sequence([
            {"success": False, "final": {"stdout": "", "stderr": "boom"}},
            {"success": True, "final": {}},
        ])
        apply_calls.clear()

        result = generate.modify_project(
            ctx, "proj-retry", "fix it", settings, context="palette: #f00",
            images=[{"name": "s.png", "media_type": "image/png",
                     "data_base64": "AAAA"}])
        check("eventually succeeds after one retry", result["success"] is True)
        check("model called exactly twice (initial + one retry)",
              len(gen_calls2) == 2)
        check("second call carries the first run's failure",
              gen_calls2[1] is not None and gen_calls2[1].get("success") is False)
        check("apply_files called twice (initial + retry)", len(apply_calls) == 2)
        check("retry keeps the design context and images",
              retry_inputs[1]["context"] == "palette: #f00"
              and retry_inputs[1]["images"] and len(retry_inputs[1]["images"]) == 1)
        check("retry sees the files the first attempt wrote, as full files",
              {"path": "app.txt", "content": "attempt 1\n"} in retry_inputs[1]["passages"]
              and "app.txt" in (retry_inputs[1]["full"] or []))
        check("the RETRY's content is what got promoted",
              open(os.path.join(pdir2, "app.txt")).read() == "attempt 2 fixed\n")

        print("== fails twice: exactly one retry, then gives up ==")
        pdir3 = _make_project(settings, ctx, "proj-fail")
        gen_calls3 = []

        def fake_gen_fail(ctx, instruction, passages, manifest,
                          prior_failure=None, context=None, images=None,
                        settings=None, **_kw):
            gen_calls3.append(prior_failure)
            return [{"path": "app.txt", "content": f"try {len(gen_calls3)}\n"}]

        generate._generate_files = fake_gen_fail
        ci_client.run = _run_sequence([
            {"success": False, "final": {"stderr": "still broken"}},
            {"success": False, "final": {"stderr": "still broken 2"}},
        ])

        one_retry = Settings(data_dir=tmp, max_fix_attempts=1)
        result = generate.modify_project(ctx, "proj-fail", "won't work", one_retry)
        check("reports failure after exhausting the single retry",
              result["success"] is False)
        check("model called exactly twice, never a third time",
              len(gen_calls3) == 2)
        check("failed generate does NOT promote to the real checkout",
              open(os.path.join(pdir3, "app.txt")).read() == "original content\n")
        project = projects.get(settings.data_dir, ctx, "proj-fail")
        check("failure reason recorded on the project", bool(project.error))

        print("== self-repair loop: fixes on the 3rd regeneration ==")
        _make_project(settings, ctx, "proj-loop")
        gen_calls4 = []

        def fake_gen_loop(ctx, instruction, passages, manifest,
                          prior_failure=None, context=None, images=None,
                          settings=None, **_kw):
            gen_calls4.append(prior_failure)
            return [{"path": "app.txt", "content": f"try {len(gen_calls4)}\n"}]

        generate._generate_files = fake_gen_loop
        ci_client.run = _run_sequence([
            {"success": False, "final": {"stderr": "err 1"}},
            {"success": False, "final": {"stderr": "err 2"}},
            {"success": False, "final": {"stderr": "err 3"}},
            {"success": True, "final": {}},
        ])
        result = generate.modify_project(ctx, "proj-loop", "hard one", settings)
        check("loop eventually succeeds", result["success"] is True)
        check("model called 1 + 3 fix attempts", len(gen_calls4) == 4)
        check("reports fix_attempts", result["fix_attempts"] == 3)
        check("each retry sees the latest failure output",
              [c["final"]["stderr"] for c in gen_calls4[1:]]
              == ["err 1", "err 2", "err 3"])

        print("== self-repair loop: capped at max_fix_attempts ==")
        _make_project(settings, ctx, "proj-cap")
        gen_calls5 = []

        def fake_gen_cap(ctx, instruction, passages, manifest,
                         prior_failure=None, context=None, images=None,
                         settings=None, **_kw):
            gen_calls5.append(prior_failure)
            return [{"path": "app.txt", "content": f"cap {len(gen_calls5)}\n"}]

        generate._generate_files = fake_gen_cap
        ci_client.run = _run_sequence(
            [{"success": False, "final": {"stderr": "nope"}}] * 10)
        result = generate.modify_project(ctx, "proj-cap", "never", settings)
        check("gives up after the cap", result["success"] is False)
        check("1 + max_fix_attempts model calls",
              len(gen_calls5) == 1 + settings.max_fix_attempts)

        print("== model returns no files -> GenerateError ==")
        _make_project(settings, ctx, "proj-empty")
        generate._generate_files = (
            lambda ctx, instruction, passages, manifest, prior_failure=None,
            context=None, images=None, settings=None, **_kw: [])
        try:
            generate.modify_project(ctx, "proj-empty", "do nothing useful",
                                    settings)
            check("empty file list raises GenerateError", False)
        except GenerateError:
            check("empty file list raises GenerateError", True)

        print("== _generate_files calls core's generate-files endpoint via httpx ==")
        real_generate_files = orig["_generate_files"]
        core_settings = Settings(data_dir=tmp, core_base_url="http://fake-core:8080",
                                 core_service_token="s3cr3t")
        gctx = TenantContext(tenant_id="tenant_a")
        orig_httpx_request = httpx.request
        try:
            calls = []

            def fake_success(method, url, headers=None, json=None, timeout=None):
                calls.append((method, url, headers, json, timeout))
                return _FakeResp(200, {"files": [
                    {"path": "a.txt", "content": "hi"},
                    {"path": None, "content": "dropped: missing path"},
                ]})

            httpx.request = fake_success
            result_files = real_generate_files(
                gctx, "add a.txt",
                [{"path": "b.txt", "content": "existing"}],
                {"name": "node-react"}, settings=core_settings)

            check("posts to core's generate-files path",
                  calls[0][1] ==
                  "http://fake-core:8080/api/internal/app-builder/generate-files")
            check("forwards X-Service-Token and X-Tenant-Id",
                  calls[0][2]["X-Service-Token"] == "s3cr3t"
                  and calls[0][2]["X-Tenant-Id"] == "tenant_a")
            check("request body carries instruction/retrievedFiles/stack",
                  calls[0][3]["instruction"] == "add a.txt"
                  and calls[0][3]["retrievedFiles"] ==
                  [{"path": "b.txt", "content": "existing"}]
                  and calls[0][3]["stack"] == "node-react"
                  and calls[0][3]["previousFailure"] is None)
            check("parses files from the response, dropping entries with no path",
                  result_files == [{"path": "a.txt", "content": "hi"}])

            print("== _generate_files sends previousFailure only when the "
                  "prior run failed ==")
            calls.clear()
            real_generate_files(
                gctx, "fix it", [], {"name": "node-react"},
                prior_failure={"success": False,
                               "final": {"stdout": "out", "stderr": "err"}},
                settings=core_settings)
            check("previousFailure carries stdout+stderr",
                  calls[0][3]["previousFailure"] == "outerr")

            calls.clear()
            real_generate_files(
                gctx, "no-op", [], {"name": "node-react"},
                prior_failure={"success": True, "final": {}},
                settings=core_settings)
            check("previousFailure omitted when the prior run succeeded",
                  calls[0][3]["previousFailure"] is None)

            print("== _generate_files wraps a non-2xx core response as "
                  "GenerateError ==")

            def fake_403(method, url, headers=None, json=None, timeout=None):
                return _FakeResp(403, {"error": "Invalid X-Service-Token."})

            httpx.request = fake_403
            try:
                real_generate_files(gctx, "x", [], {}, settings=core_settings)
                check("non-2xx raises GenerateError", False)
            except GenerateError as e:
                check("non-2xx raises GenerateError with core's message",
                      str(e) == "Invalid X-Service-Token.")

            print("== _generate_files wraps a transport failure as "
                  "GenerateError ==")

            def fake_unreachable(method, url, headers=None, json=None,
                                 timeout=None):
                raise httpx.ConnectError("connection refused")

            httpx.request = fake_unreachable
            try:
                real_generate_files(gctx, "x", [], {}, settings=core_settings)
                check("transport failure raises GenerateError", False)
            except GenerateError:
                check("transport failure raises GenerateError", True)
        finally:
            httpx.request = orig_httpx_request

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        restore()
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
