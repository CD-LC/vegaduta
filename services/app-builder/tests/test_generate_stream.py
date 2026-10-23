"""Framework-free tests for the live-progress generate stream:
- modify_project's `progress` callback emits stages in pipeline order
- the model's plain-English `summary` flows from core's reply into the result
- _generate_files parses `summary` (and tolerates its absence)
- POST /generate/stream yields ordered SSE events ending in a "done" event
  carrying the same body the sync /generate route returns

Run: python3 -m tests.test_generate_stream   (from the app-builder directory)
"""

from __future__ import annotations

import asyncio
import json
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

import httpx  # noqa: E402

from app import generate, projects  # noqa: E402
import app.main as app_main  # noqa: E402
from app.codeintel_client import client as ci_client  # noqa: E402
from app.api_models import GenerateRequest  # noqa: E402
from app.generate import _FilesResult  # noqa: E402
from app.settings import Settings  # noqa: E402
from app.tenancy import TenantContext, project_root  # noqa: E402

PASS = 0
FAIL = 0


def _sse_body(resp) -> str:
    """Consume a StreamingResponse's async iterator synchronously."""
    async def collect():
        out = []
        async for c in resp.body_iterator:
            out.append(c.encode() if isinstance(c, str) else c)
        return b"".join(out)
    return asyncio.run(collect()).decode()


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


class _FakeResp:
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


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-stream-")
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
        "get_or_404": app_main._projects.get_or_404,
        "_modify_project": app_main._modify_project,
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
        app_main._projects.get_or_404 = orig["get_or_404"]
        app_main._modify_project = orig["_modify_project"]

    try:
        ci_client.search = lambda ctx, pid, q, **kw: []
        ci_client.create_workspace = lambda ctx, pid: {"workspace_id": "ws-1"}
        ci_client.diff = lambda ctx, pid, ws: {"diff": "fake diff"}
        ci_client.apply_files = lambda ctx, pid, ws, files, **kw: {}
        ci_client.delete_workspace = lambda ctx, pid, ws: None
        ci_client.index_repo = lambda ctx, pid, **kw: None
        ci_client.run = lambda ctx, pid, ws, command, **kw: {"success": True, "final": {}}

        print("== progress callback emits stages in pipeline order ==")
        _make_project(settings, ctx, "proj-prog")

        def fake_gen(ctx, instruction, passages, manifest,
                     prior_failure=None, context=None, images=None,
                     settings=None, **_kw):
            return [{"path": "app.txt", "content": "v2\n"}]

        generate._generate_files = fake_gen
        stages = []
        result = generate.modify_project(
            ctx, "proj-prog", "change it", settings,
            progress=lambda stage, detail=None: stages.append(
                (stage, detail)))
        names = [s[0] for s in stages]
        check("stages in order: context -> writing -> files -> checking "
              "-> publishing",
              names == ["context", "writing", "files", "checking", "publishing"],
              f"got {names}")
        files_evt = next((d for s, d in stages if s == "files"), None)
        check("files event carries the applied set",
              files_evt is not None
              and files_evt["files_changed"] == ["app.txt"]
              and files_evt["files"][0]["path"] == "app.txt")
        check("checking event carries an attempt number",
              next((d for s, d in stages if s == "checking"), {}).get("attempt") == 1)
        check("a no-op (or absent) progress callback is fine",
              result["success"] is True)

        print("== summary flows from the model into the result ==")
        _make_project(settings, ctx, "proj-summary")

        def fake_gen_summary(*a, **_kw):
            r = _FilesResult([{"path": "app.txt", "content": "v3\n"}])
            r.summary = "Added a dark header and a contact form."
            return r

        generate._generate_files = fake_gen_summary
        result = generate.modify_project(ctx, "proj-summary", "restyle", settings)
        check("result carries the model's summary",
              result.get("summary") == "Added a dark header and a contact form.")

        generate._generate_files = fake_gen  # plain list: no summary attr
        result = generate.modify_project(ctx, "proj-summary", "restyle", settings)
        check("no summary -> key absent (older core behaviour)",
              "summary" not in result)

        print("== _generate_files parses summary from core's response ==")
        real_gen = orig["_generate_files"]
        core_settings = Settings(data_dir=tmp, core_base_url="http://fake-core:8080",
                                 core_service_token="s3cr3t")
        orig_request = httpx.request
        try:
            httpx.request = lambda *a, **k: _FakeResp(200, {
                "files": [{"path": "a.txt", "content": "x"}],
                "summary": "  Made the header blue. "})
            files = real_gen(ctx, "blue header", [], {"name": "s"}, settings=core_settings)
            check("summary parsed and trimmed",
                  getattr(files, "summary", None) == "Made the header blue.")

            httpx.request = lambda *a, **k: _FakeResp(200, {
                "files": [{"path": "a.txt", "content": "x"}]})
            files = real_gen(ctx, "x", [], {"name": "s"}, settings=core_settings)
            check("absent summary -> None", getattr(files, "summary", None) is None)

            httpx.request = lambda *a, **k: _FakeResp(200, {
                "files": [{"path": "a.txt", "content": "x"}],
                "summary": {"not": "a string"}})
            files = real_gen(ctx, "x", [], {"name": "s"}, settings=core_settings)
            check("non-string summary -> None",
                  getattr(files, "summary", None) is None)
        finally:
            httpx.request = orig_request

        print("== /generate/stream emits ordered SSE events ==")
        _make_project(settings, ctx, "proj-sse")
        # The route pre-validates through the module-global SETTINGS (whose
        # data_dir is not this test's tmp) - stub the check; _modify_project
        # is faked below, so the project record is never re-read.
        app_main._projects.get_or_404 = lambda *a, **k: object()

        def fake_modify(ctx, project_id, instruction, settings=None,
                        context=None, images=None, progress=None):
            if progress:
                progress("context")
                progress("writing")
                progress("files", {"files": [{"path": "app.txt", "content": "v2\n"}],
                                   "files_changed": ["app.txt"]})
                progress("checking", {"attempt": 1, "total": 1})
                progress("publishing")
            return {"success": True, "files_changed": ["app.txt"],
                    "summary": "Did a thing.", "diff": "d"}

        app_main._modify_project = fake_modify
        resp = app_main.generate_stream("proj-sse", GenerateRequest(instruction="restyle"), ctx)
        check("SSE content type", resp.media_type == "text/event-stream")
        body = _sse_body(resp)
        events = [json.loads(line[6:]) for line in body.splitlines()
                  if line.startswith("data: ")]
        names = [e["stage"] for e in events]
        check("ordered stage events then done",
              names == ["context", "writing", "files", "checking",
                        "publishing", "done"],
              f"got {names}")
        check("done carries the pipeline result",
              events[-1].get("result", {}).get("summary") == "Did a thing.")
        check("files event carries the applied set",
              events[2]["files_changed"] == ["app.txt"])

        print("== /generate/stream wraps a pipeline failure as an error event ==")

        def fake_modify_fail(*a, **k):
            raise generate.GenerateError("core unreachable at http://core:8080")

        app_main._modify_project = fake_modify_fail
        resp = app_main.generate_stream("proj-sse", GenerateRequest(instruction="x"), ctx)
        body = _sse_body(resp)
        events = [json.loads(line[6:]) for line in body.splitlines()
                  if line.startswith("data: ")]
        check("error event ends the stream",
              len(events) == 1 and events[0]["stage"] == "error")
        check("error event carries the message and a status",
              events[0]["status"] == 500 and "core unreachable" in events[0]["error"])

        print("== /generate/stream fails before the stream opens on a bad project ==")
        app_main._modify_project = orig["_modify_project"]

        def missing(data_dir, ctx, project_id):
            raise projects.ProjectNotFoundError("no such project")

        app_main._projects.get_or_404 = missing
        try:
            app_main.generate_stream("proj-nope", GenerateRequest(instruction="x"), ctx)
            check("pre-stream validation raises", False)
        except Exception:
            check("pre-stream validation raises (no SSE opened)", True)

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        restore()
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
