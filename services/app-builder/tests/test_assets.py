"""Asset upload/list/delete — the "drop your logo / Figma export in"
surface. Assets commit to assets/ on the project's branch through the
same Git Data API path as generated code; listing comes back from a
recursive tree walk; deletion is a tree-without-the-path commit.

Also asserts filename sanitization (the uploaded name is user content —
the path it lands at is ours), the extension allowlist, and the Pages
scaffold behavior the BYO flow now performs.

Run: python3 -m tests.test_assets   (from the app-builder directory)
"""

from __future__ import annotations

import base64
import os
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from cryptography.fernet import Fernet  # noqa: E402

from app import assets, github_client, github_credentials, scaffold  # noqa: E402
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
BRANCH = "app-builder/site"


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-assets-")
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
    }

    def restore():
        github_client.httpx = orig["httpx"]
        ci_client.register_repo = orig["register_repo"]
        ci_client.index_repo = orig["index_repo"]

    try:
        github_client.httpx = fake.shim()
        ci_client.register_repo = lambda c, rid, **kw: {"repo_id": rid}
        ci_client.index_repo = lambda c, rid, **kw: {}

        github_credentials.connect(ctx, TOKEN, REPO, settings)

        print("== a preview_dir template deploys to Pages on scaffold ==")
        project = scaffold.create_project(ctx, "site", "node-react", None,
                                          settings)
        gh = project.github or {}
        check("pages enabled on the repo", fake.pages is not None)
        check("pages build_type is workflow",
              fake.pages.get("build_type") == "workflow", extra=str(fake.pages))
        check("project records the deterministic pages url",
              gh.get("pages_url") == "https://acme.github.io/widgets/",
              extra=str(gh))
        check("pages_enabled recorded", gh.get("pages_enabled") is True)

        workflow = fake.file_text(BRANCH, scaffold.WORKFLOW_PATH)
        check("workflow has a pages job",
              "\n  pages:\n" in workflow, extra=workflow[:400])
        check("pages job deploys only our branch",
              f"github.ref == 'refs/heads/{BRANCH}'" in workflow)
        check("pages job uploads the template's preview_dir",
              "path: dist" in workflow)
        check("pages job carries the pages/id-token permissions",
              "pages: write" in workflow and "id-token: write" in workflow)
        try:
            import yaml  # type: ignore
            parsed = yaml.safe_load(workflow)
            check("workflow with pages job is valid YAML",
                  isinstance(parsed, dict) and
                  set(parsed.get("jobs", {})) >= {"check", "pages"},
                  extra=str(parsed)[:200])
        except ImportError:
            print("  skip  workflow YAML parse (pyyaml not installed)")
        check("PR body mentions the Pages preview",
              "github.io" in fake.pulls[0]["body"], extra=fake.pulls[0]["body"][:200])

        print("== upload commits under assets/ as ONE commit ==")
        logo_png = b"\x89PNG\r\n\x1a\n" + b"\x00" * 32
        before = len(fake.commit_messages(BRANCH))
        result = assets.upload_asset(ctx, "site", "logo.png", logo_png,
                                     settings)
        check("asset path is assets/<name>", result["path"] == "assets/logo.png")
        check("one commit was added",
              len(fake.commit_messages(BRANCH)) == before + 1)
        check("the bytes are on the branch",
              base64.b64encode(logo_png).decode()
              == _blob_b64(fake, BRANCH, "assets/logo.png"))
        check("commit message names the asset",
              "Asset: logo.png" in fake.commit_messages(BRANCH)[0])

        print("== listing reads the branch tree ==")
        listed = assets.list_assets(ctx, "site", settings)
        check("list_assets finds the upload",
              any(a["path"] == "assets/logo.png" for a in listed),
              extra=str(listed))
        check("non-asset files are excluded",
              all(a["path"].startswith("assets/") for a in listed))

        print("== sanitization: names are ours to choose, not the user's ==")
        for bad, why in [
            ("../evil.sh", "traversal"),
            ("..\\win.exe", "windows traversal"),
            ("script.exe", "disallowed extension"),
            ("", "empty name"),
        ]:
            try:
                assets.upload_asset(ctx, "site", bad, b"x", settings)
                check(f"rejects {why}", False)
            except GenerateError:
                check(f"rejects {why}", True)
        check("spaces/parens are rewritten to a safe name",
              assets.upload_asset(ctx, "site", "my logo (1).png", logo_png,
                                  settings)["path"]
              == "assets/my_logo__1_.png")

        print("== oversized assets are refused ==")
        try:
            assets.upload_asset(ctx, "site", "big.png",
                                b"\x00" * (8 * 1024 * 1024 + 1), settings)
            check("rejects >8MB", False)
        except GenerateError:
            check("rejects >8MB", True)

        print("== delete commits a tree without the path ==")
        assets.delete_asset(ctx, "site", "assets/logo.png", settings)
        check("asset is gone from the branch",
              "assets/logo.png" not in fake.file_paths(BRANCH))
        check("the replacement asset survived the delete",
              "assets/my_logo__1_.png" in fake.file_paths(BRANCH))
        try:
            assets.delete_asset(ctx, "site", "src/App.tsx", settings)
            check("delete is confined to assets/", False)
        except GenerateError:
            check("delete is confined to assets/", True)

        print("== Pages soft-fails when the token can't manage it ==")
        fake2 = FakeGitHub(api_base=API, token=TOKEN, repo="acme/no-pages")
        fake2.fail_pages_with = 403
        ctx2 = TenantContext(tenant_id="tenant_b", subject="b@acme.test")
        github_client.httpx = fake2.shim()
        github_credentials.connect(ctx2, TOKEN, "acme/no-pages", settings)
        p2 = scaffold.create_project(ctx2, "np", "node-react", None, settings)
        check("scaffold still succeeds", p2.status == "ready")
        check("pages_enabled is false, pages_url still shown",
              (p2.github or {}).get("pages_enabled") is False and
              (p2.github or {}).get("pages_url")
              == "https://acme.github.io/no-pages/")
        check("PR body explains the manual step",
              "Settings → Pages" in fake2.pulls[0]["body"],
              extra=fake2.pulls[0]["body"][:300])

    finally:
        restore()

    print(f"\n{PASS} passed, {FAIL} failed")
    return 1 if FAIL else 0


def _blob_b64(fake: FakeGitHub, ref: str, path: str) -> str:
    """Read the stored blob's base64 for `path` at `ref`."""
    commit = fake.commits[fake.refs[ref]]
    blob_sha = fake.trees[commit["tree"]][path]
    return fake.blobs[blob_sha]


if __name__ == "__main__":
    sys.exit(main())
