"""ACCEPTANCE GATE for the bring-your-own-repo pivot (contract §3).

One full create -> generate -> push -> open-PR cycle, then the three
assertions the contract and the task actually gate on:

  (a) NO Docker container is started — nothing calls code-intel's process
      endpoints, nothing calls preview.py/ingress.py, and the flow does not
      shell out at all (no `git`, no `docker`). Enforced with tripwires that
      fail the test if invoked, not by inspecting after the fact.
  (b) NOTHING is written outside the pushed branch — the only files this
      service creates are its own metadata (project registry, audit log,
      credential store). No project source directory is created at all.
  (c) The stored token is encrypted at rest and never appears in plaintext in
      the store file — nor in the project record, the audit log, or any
      response body.

Plus the positive assertions: the template really landed on the branch, the
starter workflow is there, the AI commit is a second commit on top that did
NOT wipe the template, and the PR points head->base correctly.

## Mocking, stated explicitly

GitHub is mocked in-process (tests/fake_github.py — a real object model with
blobs/trees/commits/refs/pulls, not a return-{} stub). A real GitHub API call
is NOT made and is not possible here: this environment has no GitHub
credentials and no network egress for it, and a test must never create
branches or PRs in a real repository. Every code-intel call and the core
generate-files call (`generate._generate_files`) are mocked the same way the
pre-existing test_scaffold.py / test_generate.py do it, so this needs no
running code-intel and no running core.

Run: python3 -m tests.test_byo_repo   (from the app-builder directory)
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from cryptography.fernet import Fernet  # noqa: E402

from app import (  # noqa: E402
    generate,
    github_client,
    github_credentials,
    ingress,
    preview,
    projects,
    scaffold,
)
from app.codeintel_client import client as ci_client  # noqa: E402
from app.projects import ProjectExistsError  # noqa: E402
from app.scaffold import LocalPreviewDisabledError  # noqa: E402
from app.settings import Settings  # noqa: E402
from app.tenancy import TenantContext  # noqa: E402
from tests.fake_github import FakeGitHub  # noqa: E402

PASS = 0
FAIL = 0

API = "https://api.github.test"
TOKEN = "github_pat_11ZZZ_tenant_secret_pat"
REPO = "acme/widgets"
BRANCH = "app-builder/shopfront"


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


class Tripwire:
    """Records every call and refuses to do anything. Anything the pivot must
    never do gets one of these bound over it."""

    def __init__(self, label: str):
        self.label = label
        self.calls: list[tuple] = []

    def __call__(self, *args, **kwargs):
        self.calls.append((args, kwargs))
        raise AssertionError(f"{self.label} must never be called on the "
                             f"bring-your-own-repo path")


def _walk(root: str) -> set[str]:
    out = set()
    for dirpath, _dirnames, filenames in os.walk(root):
        for fn in filenames:
            out.add(os.path.relpath(os.path.join(dirpath, fn),
                                    root).replace(os.sep, "/"))
    return out


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-byo-")
    key = Fernet.generate_key().decode()
    settings = Settings(
        data_dir=tmp, templates_dir=os.path.join(ROOT, "templates"),
        app_builder_encryption_key=key, github_api_url=API,
        local_preview_enabled=False)
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
        "create_process": ci_client.create_process,
        "get_process": ci_client.get_process,
        "delete_process": ci_client.delete_process,
        "_generate_files": generate._generate_files,
        "sp_run": subprocess.run,
        "sp_popen": subprocess.Popen,
        "sp_check_output": subprocess.check_output,
        "ingress_register": ingress.register_preview_route,
        "start_preview": preview.start_preview,
    }

    def restore():
        github_client.httpx = orig["httpx"]
        for k in ("register_repo", "index_repo", "search", "create_workspace",
                  "apply_files", "run", "diff", "delete_workspace",
                  "create_process", "get_process", "delete_process"):
            setattr(ci_client, k, orig[k])
        generate._generate_files = orig["_generate_files"]
        subprocess.run = orig["sp_run"]
        subprocess.Popen = orig["sp_popen"]
        subprocess.check_output = orig["sp_check_output"]
        ingress.register_preview_route = orig["ingress_register"]
        preview.start_preview = orig["start_preview"]

    try:
        github_client.httpx = fake.shim()

        # ---- (a) tripwires: nothing may start a container or shell out ----
        tw_process = Tripwire("code-intel create_process (Docker preview)")
        tw_subprocess = Tripwire("subprocess.run")
        tw_popen = Tripwire("subprocess.Popen")
        tw_checkout = Tripwire("subprocess.check_output")
        tw_ingress = Tripwire("ingress.register_preview_route")
        tw_preview = Tripwire("preview.start_preview")
        ci_client.create_process = tw_process
        ci_client.get_process = Tripwire("code-intel get_process")
        ci_client.delete_process = Tripwire("code-intel delete_process")
        subprocess.run = tw_subprocess
        subprocess.Popen = tw_popen
        subprocess.check_output = tw_checkout
        ingress.register_preview_route = tw_ingress
        preview.start_preview = tw_preview

        register_calls: list[dict] = []
        index_calls: list[str] = []
        apply_calls: list[list] = []
        ws_deleted: list[str] = []

        def fake_register(c, repo_id, **kwargs):
            register_calls.append({"repo_id": repo_id, **kwargs})
            return {"repo_id": repo_id}

        ci_client.register_repo = fake_register
        ci_client.index_repo = lambda c, rid, **kw: index_calls.append(rid) or {}
        ci_client.search = lambda c, rid, q, **kw: []
        ci_client.create_workspace = lambda c, rid: {"workspace_id": "ws-1"}
        ci_client.apply_files = (
            lambda c, rid, ws, files, **kw: apply_calls.append(files) or {})
        ci_client.run = lambda c, rid, ws, cmd, **kw: {"success": True, "final": {}}
        ci_client.diff = lambda c, rid, ws: {"diff": "--- a\n+++ b\n"}
        ci_client.delete_workspace = (
            lambda c, rid, ws: ws_deleted.append(ws) or {})

        gen_calls: list = []

        def fake_gen(ctx, instruction, passages, manifest, prior_failure=None,
                     context=None, images=None, settings=None, **_kw):
            gen_calls.append({"instruction": instruction, "manifest": manifest})
            return [{"path": "src/App.tsx",
                     "content": "export default () => <h1>Shopfront</h1>;\n"}]

        generate._generate_files = fake_gen

        print("== with no repo connected, creating a project refuses ==")
        try:
            scaffold.create_project(ctx, "nope", "node-react", None, settings)
            check("create_project refuses without a connected repo", False)
        except LocalPreviewDisabledError as e:
            check("create_project refuses without a connected repo", True)
            check("the error tells the caller to connect a repo",
                  "/projects/github/connect" in str(e), extra=str(e))
        check("no project record was created",
              projects.get(tmp, ctx, "nope") is None)

        print("== connect the tenant's repo ==")
        github_credentials.connect(ctx, TOKEN, REPO, settings)
        check("connected", github_credentials.status(
            ctx, settings).get("repo_full_name") == REPO)

        print("== full cycle: create -> generate -> push -> PR ==")
        project = scaffold.create_project(
            ctx, "shopfront", "node-react",
            "build a storefront landing page", settings)

        check("project is ready", project.status == "ready",
              extra=str(project.error))
        check("no error recorded", project.error is None,
              extra=str(project.error))

        gh = project.github or {}
        check("project records the tenant's repo",
              gh.get("repo_full_name") == REPO, extra=str(gh))
        check("project records the branch", gh.get("branch") == BRANCH,
              extra=str(gh))
        check("project records the base branch", gh.get("base_branch") == "main")
        check("project records a browsable branch URL",
              gh.get("branch_url")
              == f"https://github.com/{REPO}/tree/{BRANCH}")

        print("-- the branch really exists in the tenant's repo --")
        check("the branch was created", BRANCH in fake.refs)
        check("the tenant's default branch was NOT moved",
              fake.commit_messages("main") == ["initial commit"],
              extra=str(fake.commit_messages("main")))

        paths = fake.file_paths(BRANCH)
        check("the template landed on the branch",
              {"package.json", "src/App.tsx", "src/main.tsx", "index.html",
               "vite.config.js", "tsconfig.json"} <= paths, extra=str(sorted(paths)))
        check("the template manifest landed too", ".appbuilder.json" in paths)
        check("a starter GitHub Actions workflow landed",
              scaffold.WORKFLOW_PATH in paths, extra=str(sorted(paths)))
        check("the pre-existing repo file was NOT deleted by the scaffold",
              "README.md" in paths, extra=str(sorted(paths)))

        workflow = fake.file_text(BRANCH, scaffold.WORKFLOW_PATH)
        check("the workflow states plainly whose minutes it runs on",
              "this repository's own" in workflow, extra=workflow[:200])
        check("the workflow uses the template's declared container image",
              "container: node:22-bookworm" in workflow)
        check("the workflow runs the template's build command",
              "npm run build" in workflow)
        try:
            import yaml  # type: ignore
            parsed = yaml.safe_load(workflow)
            check("the workflow is valid YAML with a job",
                  isinstance(parsed, dict) and "jobs" in parsed,
                  extra=str(parsed)[:200])
        except ImportError:
            print("  skip  workflow YAML parse (pyyaml not installed)")

        print("-- generate() committed on top, as a second commit --")
        check("the model was called exactly once", len(gen_calls) == 1,
              extra=str(len(gen_calls)))
        check("generate read the manifest back off the BRANCH",
              gen_calls[0]["manifest"].get("name") == "Node + React",
              extra=str(gen_calls[0]["manifest"]))
        check("files were verified in a code-intel workspace first",
              len(apply_calls) == 1)
        check("the scratch workspace was cleaned up", ws_deleted == ["ws-1"])
        msgs = fake.commit_messages(BRANCH)
        check("branch has scaffold + AI commits on top of the base",
              msgs == ["AI: build a storefront landing page",
                       "Scaffold from template node-react", "initial commit"],
              extra=str(msgs))
        check("the AI's file content is what is on the branch",
              fake.file_text(BRANCH, "src/App.tsx")
              == "export default () => <h1>Shopfront</h1>;\n")
        check("the AI commit did NOT wipe the rest of the template",
              "package.json" in fake.file_paths(BRANCH))
        check("commit_sha on the record is the branch tip",
              gh_now(ctx, tmp).get("commit_sha") == fake.refs[BRANCH])

        print("-- the PR was opened, last, against the default branch --")
        check("exactly one PR was opened", len(fake.pulls) == 1)
        pr = fake.pulls[0]
        check("PR head is our branch", pr["head"] == BRANCH)
        check("PR base is the tenant's default branch", pr["base"] == "main")
        check("PR title names the project",
              pr["title"] == "app-builder: shopfront")
        check("PR body carries what the user asked for",
              "build a storefront landing page" in pr["body"])
        check("PR body is honest about whose Actions minutes run the build",
              "this repository's own" in pr["body"], extra=pr["body"][:200])
        check("project records the PR url",
              gh_now(ctx, tmp).get("pr_url")
              == f"https://github.com/{REPO}/pull/1")
        check("project records the PR number",
              gh_now(ctx, tmp).get("pr_number") == 1)

        print("-- code-intel got a GIT registration with the tenant's token --")
        byo = [r for r in register_calls if r.get("source_type") == "git"]
        check("registered as source_type=git", len(byo) >= 1,
              extra=str(register_calls))
        check("no local-path registration happened at all",
              not [r for r in register_calls if r.get("source_type") == "local"],
              extra=str(register_calls))
        check("git_url is the tenant's repo",
              byo[0].get("git_url") == f"https://github.com/{REPO}.git")
        check("git_ref is the new branch", byo[0].get("git_ref") == BRANCH)
        check("auth_token is the tenant's decrypted PAT",
              byo[0].get("auth_token") == TOKEN)
        check("auth_env is NOT used (it cannot be per-tenant)",
              byo[0].get("auth_env") is None)
        check("no local `path` was sent", byo[0].get("path") is None)
        check("re-registered before re-index so a code-intel restart survives",
              len(byo) == 2, extra=f"{len(byo)} git registrations")
        check("the repo was indexed", index_calls == ["shopfront", "shopfront"],
              extra=str(index_calls))

        # ------------------------------------------------------------------ #
        print("\n== (a) no Docker container was started ==")
        check("code-intel create_process never called",
              tw_process.calls == [])
        check("preview.start_preview never called", tw_preview.calls == [])
        check("ingress.register_preview_route never called",
              tw_ingress.calls == [])
        check("subprocess.run never called (no git, no docker)",
              tw_subprocess.calls == [], extra=str(tw_subprocess.calls))
        check("subprocess.Popen never called", tw_popen.calls == [])
        check("subprocess.check_output never called", tw_checkout.calls == [])

        print("== (b) nothing was written outside the pushed branch ==")
        written = _walk(tmp)
        check("no project source directory was created at all",
              not os.path.exists(os.path.join(tmp, "tenants", "tenant_a",
                                              "projects")),
              extra=str(sorted(written)))
        check("the only files written are this service's own metadata",
              written == {"tenants/tenant_a/projects.json",
                          "tenants/tenant_a/audit.jsonl",
                          "tenants/tenant_a/github.json"},
              extra=str(sorted(written)))
        check("no .git directory anywhere under data_dir",
              not any("/.git/" in p or p.startswith(".git/") for p in written))
        check("every generated file went to the branch instead",
              "src/App.tsx" in fake.file_paths(BRANCH))

        print("== (c) the token is encrypted at rest and never leaks ==")
        store = os.path.join(tmp, "tenants", "tenant_a", "github.json")
        raw_store = open(store, "r", encoding="utf-8").read()
        check("token absent from the credential store file",
              TOKEN not in raw_store)
        check("the store holds a Fernet ciphertext",
              json.loads(raw_store)["encrypted_token"].startswith("gAAAA"))
        registry = open(os.path.join(tmp, "tenants", "tenant_a",
                                     "projects.json"), encoding="utf-8").read()
        check("token absent from the project registry", TOKEN not in registry)
        audit_log = open(os.path.join(tmp, "tenants", "tenant_a",
                                      "audit.jsonl"), encoding="utf-8").read()
        check("token absent from the audit log", TOKEN not in audit_log)
        check("the audit log still records the branch and PR",
              "pull_request_opened" in audit_log and BRANCH in audit_log)
        check("token absent from the API-shaped project response",
              TOKEN not in json.dumps(projects.get(tmp, ctx, "shopfront").__dict__,
                                      default=str))
        for f in _walk(tmp):
            body = open(os.path.join(tmp, f), "r", encoding="utf-8",
                        errors="replace").read()
            check(f"token absent from {f}", TOKEN not in body)

        print("\n== a second project on the same repo gets its own branch ==")
        project2 = scaffold.create_project(ctx, "blog", "static-html", None,
                                           settings)
        check("second project ready", project2.status == "ready",
              extra=str(project2.error))
        check("its branch is namespaced by project id",
              (project2.github or {}).get("branch") == "app-builder/blog")
        check("both branches exist independently",
              BRANCH in fake.refs and "app-builder/blog" in fake.refs)
        check("a second PR was opened", len(fake.pulls) == 2)
        blog_wf = fake.file_text("app-builder/blog", scaffold.WORKFLOW_PATH)
        check("a template with no build/test still gets a runnable workflow",
              "Nothing to build" in blog_wf, extra=blog_wf)
        check("static-html's workflow still uses its declared image",
              "container: python:3.12-slim" in blog_wf, extra=blog_wf)
        no_image = scaffold.starter_workflow({"name": "x", "install": "make"})
        check("a manifest with no image emits no container: key",
              "container:" not in no_image, extra=no_image)
        check("...but still emits its install step", "run: make" in no_image)

        print("== an existing branch is a 409, not a silent overwrite ==")
        projects.delete(tmp, ctx, "blog")
        try:
            scaffold.create_project(ctx, "blog", "static-html", None, settings)
            check("existing branch rejected", False)
        except ProjectExistsError as e:
            check("existing branch rejected", True)
            check("the message names the branch", "app-builder/blog" in str(e),
                  extra=str(e))

        print("== connecting a brand-new, zero-commit repo is a clear 404, "
              "not a raw GitHub 409 ==")
        empty_repo = "acme/freshly-created"
        empty_fake = FakeGitHub(api_base=API, token=TOKEN, repo=empty_repo,
                                empty_repo=True)
        ctx_empty = TenantContext(tenant_id="tenant_empty",
                                  subject="admin@acme.test")
        github_client.httpx = empty_fake.shim()
        try:
            github_credentials.connect(ctx_empty, TOKEN, empty_repo, settings)
            try:
                scaffold.create_project(ctx_empty, "first", "static-html",
                                        None, settings)
                check("empty repo is refused", False)
            except github_client.GitHubError as e:
                check("empty repo is refused", True)
                check("the message tells the caller to push a commit",
                      "Push at least one commit" in str(e), extra=str(e))
                check("the raw GitHub 409 wording is not what's shown",
                      "Git Repository is empty" not in str(e), extra=str(e))
        finally:
            github_client.httpx = fake.shim()  # restore for the rest of the suite

        print("== a PR failure leaves the pushed branch intact ==")
        fake.fail_pull_with = 422
        project3 = scaffold.create_project(ctx, "docs", "static-html", None,
                                           settings)
        check("project still ready — the branch is the real work",
              project3.status == "ready")
        check("the branch was pushed", "app-builder/docs" in fake.refs)
        check("the PR failure is recorded, not swallowed",
              "pull request failed" in (project3.error or ""),
              extra=str(project3.error))
        check("no PR url is claimed",
              (project3.github or {}).get("pr_url") is None)
        check("the failure message carries no token",
              TOKEN not in (project3.error or ""))
        fake.fail_pull_with = None

        print("== delete_project never touches the tenant's repo ==")
        refs_before = dict(fake.refs)
        pulls_before = len(fake.pulls)
        ci_client.delete_repo = lambda c, rid: {}
        check("delete returns True",
              scaffold.delete_project(ctx, "docs", settings) is True)
        check("the tenant's branches are all still there",
              fake.refs == refs_before)
        check("no PR was closed or deleted", len(fake.pulls) == pulls_before)

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        restore()
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)


def gh_now(ctx: TenantContext, data_dir: str) -> dict:
    """The project's github state as currently persisted (not the snapshot the
    caller got back), so assertions read the durable record."""
    return projects.get(data_dir, ctx, "shopfront").github or {}


if __name__ == "__main__":
    raise SystemExit(main())
