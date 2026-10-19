"""generate/apply — the edge path (files generated client-side by WebLLM
or Chrome's built-in model) lands through the same workspace/verify/
commit pipeline as the server-driven generate().

Asserts:
  * happy path: client files are applied in a scratch workspace, the check
    command runs, ONE commit lands on the tenant's branch, the workspace is
    cleaned up, and the response carries the github fields the UI shows;
  * a failed check commits NOTHING and returns check_output for the
    client-side corrective retry (the server must not retry — the model is
    on the client);
  * path/size validation rejects bad file sets with a 422-style
    GenerateError before anything touches a workspace;
  * no LLM call is ever made (generate._generate_files is tripwired);
  * the audit entry records origin=client.

Run: python3 -m tests.test_apply   (from the app-builder directory)
"""

from __future__ import annotations

import os
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from cryptography.fernet import Fernet  # noqa: E402

from app import generate, github_client, github_credentials, scaffold  # noqa: E402
from app.codeintel_client import client as ci_client  # noqa: E402
from app.generate import GenerateError  # noqa: E402
from app.settings import Settings  # noqa: E402
from app.tenancy import TenantContext  # noqa: E402
from tests.fake_github import FakeGitHub  # noqa: E402

PASS = 0
FAIL = 0

API = "https://api.github.test"
TOKEN = "github_pat_11ZZZ_tenant_secret_pat"
REPO = "acme/widgets"
BRANCH = "app-builder/shop"


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-apply-")
    key = Fernet.generate_key().decode()
    settings = Settings(
        data_dir=tmp, templates_dir=os.path.join(ROOT, "templates"),
        app_builder_encryption_key=key, github_api_url=API)
    ctx = TenantContext(tenant_id="tenant_a", subject="admin@acme.test")

    fake = FakeGitHub(api_base=API, token=TOKEN, repo=REPO)

    orig = {
        "httpx": github_client.httpx,
        "register_repo": ci_client.register_repo,
        "index_repo": ci_client.index_repo,
        "search": ci_client.search,
        "create_workspace": ci_client.create_workspace,
        "apply_files": ci_client.apply_files,
        "run": ci_client.run,
        "diff": ci_client.diff,
        "delete_workspace": ci_client.delete_workspace,
        "_generate_files": generate._generate_files,
    }

    def restore():
        github_client.httpx = orig["httpx"]
        for k in ("register_repo", "index_repo", "search", "create_workspace",
                  "apply_files", "run", "diff", "delete_workspace"):
            setattr(ci_client, k, orig[k])
        generate._generate_files = orig["_generate_files"]

    run_results = [{"success": True, "final": {"stdout": "ok", "stderr": ""}}]
    apply_calls: list[list] = []
    ws_deleted: list[str] = []

    try:
        github_client.httpx = fake.shim()
        ci_client.register_repo = lambda c, rid, **kw: {"repo_id": rid}
        ci_client.index_repo = lambda c, rid, **kw: {}
        ci_client.search = lambda c, rid, q, **kw: []
        ci_client.create_workspace = lambda c, rid: {"workspace_id": "ws-1"}
        ci_client.apply_files = (
            lambda c, rid, ws, files, **kw: apply_calls.append(files) or {})
        ci_client.run = lambda c, rid, ws, cmd, **kw: run_results[0]
        ci_client.diff = lambda c, rid, ws: {"diff": "--- a\n+++ b\n"}
        ci_client.delete_workspace = (
            lambda c, rid, ws: ws_deleted.append(ws) or {})

        # The edge path must never invoke the hosted model.
        def llm_tripwire(*a, **kw):
            raise AssertionError(
                "generate/apply must not call the hosted model")
        generate._generate_files = llm_tripwire

        print("== setup: connected repo + scaffolded project ==")
        github_credentials.connect(ctx, TOKEN, REPO, settings)
        scaffold.create_project(ctx, "shop", "node-react", None, settings)
        assert BRANCH in fake.refs
        apply_calls.clear()
        ws_deleted.clear()

        print("== happy path: client files -> verify -> commit ==")
        client_files = [
            {"path": "src/App.tsx",
             "content": "export default () => <h1>Edge-built</h1>;\n"},
            {"path": "src/New.tsx", "content": "export const x = 1;\n"},
        ]
        result = generate.apply_files_to_project(
            ctx, "shop", "on-device: add a heading", client_files, settings)

        check("success", result["success"] is True)
        check("files applied in the workspace first",
              len(apply_calls) == 1 and len(apply_calls[0]) == 2)
        check("workspace cleaned up", ws_deleted == ["ws-1"])
        check("diff returned", "a/" in result["diff"] or "+++" in result["diff"])
        check("files echoed for the instant preview",
              result["files"] == client_files)
        check("github fields ride along",
              result["branch"] == BRANCH and
              result["pr_url"] == f"https://github.com/{REPO}/pull/1",
              extra=str(result))
        check("pages_url rides along",
              result.get("pages_url") == "https://acme.github.io/widgets/",
              extra=str(result))
        check("ONE commit landed on the tenant's branch",
              fake.file_text(BRANCH, "src/New.tsx") == "export const x = 1;\n")
        check("commit message describes the turn",
              fake.commit_messages(BRANCH)[0] == "AI: on-device: add a heading",
              extra=str(fake.commit_messages(BRANCH)))
        audit = open(os.path.join(tmp, "tenants", "tenant_a", "audit.jsonl"),
                     encoding="utf-8").read()
        check("audit records origin=client",
              '"generate_apply"' in audit and '"client"' in audit)

        print("== failed check: nothing committed, check_output returned ==")
        run_results[0] = {"success": False,
                          "final": {"stdout": "", "stderr": "tsc: error TS1005"}}
        apply_calls.clear()
        ws_deleted.clear()
        before_msgs = fake.commit_messages(BRANCH)

        result = generate.apply_files_to_project(
            ctx, "shop", "on-device: broken change",
            [{"path": "src/Broken.tsx", "content": "const = bad"}],
            settings)

        check("success is false", result["success"] is False)
        check("check_output carries the failure for the client retry",
              "TS1005" in result.get("check_output", ""), extra=str(result))
        check("NOTHING was committed on failure",
              fake.commit_messages(BRANCH) == before_msgs and
              "src/Broken.tsx" not in fake.file_paths(BRANCH))
        check("workspace still cleaned up", ws_deleted == ["ws-1"])
        check("files still echoed (client may show what was attempted)",
              result["files"][0]["path"] == "src/Broken.tsx")
        run_results[0] = {"success": True, "final": {"stdout": "ok", "stderr": ""}}

        print("== validation: bad file sets are rejected early ==")
        for bad, why in [
            ([], "empty list"),
            ([{"path": "../escape.ts", "content": "x"}], "path escape"),
            ([{"path": "/abs.ts", "content": "x"}], "absolute path"),
            ([{"path": "x.ts"}], "missing content"),
            ([{"path": "x.ts", "content": "x"}] * 101, "over the file cap"),
        ]:
            try:
                generate.apply_files_to_project(ctx, "shop", "bad", bad,
                                                settings)
                check(f"rejects {why}", False)
            except GenerateError:
                check(f"rejects {why}", True)

    finally:
        restore()

    print(f"\n{PASS} passed, {FAIL} failed")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
