"""Route-level tests for the new bring-your-own-repo HTTP surface (contract
§1) — proving the endpoints the `web` agent will call actually exist, return
the documented shapes and status codes, and never hand a PAT back out.

Exercises the real FastAPI app through starlette's TestClient (no server, no
network). GitHub is mocked in-process via tests/fake_github.py; `auth_mode`
is set to "none" (the documented local-dev mode) so the tests are about the
routes, not about Keycloak.

Run: python3 -m tests.test_github_routes   (from the app-builder directory)
"""

from __future__ import annotations

import os
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from cryptography.fernet import Fernet  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import github_client, main  # noqa: E402
from app.codeintel_client import client as ci_client  # noqa: E402
from app.settings import Settings  # noqa: E402
from tests.fake_github import FakeGitHub  # noqa: E402

PASS = 0
FAIL = 0

API = "https://api.github.test"
TOKEN = "github_pat_11ROUTES_secret"
REPO = "acme/widgets"
HDRS = {"X-Tenant-Id": "tenant_a"}


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def main_() -> int:
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-routes-")
    key = Fernet.generate_key().decode()
    orig_settings = main.SETTINGS
    orig_httpx = github_client.httpx
    fake = FakeGitHub(api_base=API, token=TOKEN, repo=REPO)
    github_client.httpx = fake.shim()

    # code-intel is mocked: this file is about app-builder's HTTP surface, and
    # without this the create route would talk to whatever happens to be
    # listening on APP_BUILDER_CODE_INTEL_URL on the machine running the tests.
    ci_orig = {"register_repo": ci_client.register_repo,
               "index_repo": ci_client.index_repo,
               "delete_repo": ci_client.delete_repo}
    registrations: list[dict] = []
    ci_client.register_repo = (
        lambda c, rid, **kw: registrations.append({"repo_id": rid, **kw}) or {})
    ci_client.index_repo = lambda c, rid, **kw: {}
    ci_client.delete_repo = lambda c, rid: {}

    main.SETTINGS = Settings(
        data_dir=tmp, templates_dir=os.path.join(ROOT, "templates"),
        auth_mode="none", app_builder_encryption_key=key, github_api_url=API,
        local_preview_enabled=False)
    client = TestClient(main.app)

    try:
        print("== GET /health advertises whether connect can work at all ==")
        r = client.get("/health")
        check("health 200", r.status_code == 200)
        check("health reports github_ready=true with a key set",
              r.json().get("github_ready") is True, extra=r.text)
        check("health reports local previews off",
              r.json().get("local_preview_enabled") is False)
        check("health never exposes the key itself",
              key not in r.text, extra=r.text)

        print("== GET /projects/github/status before connecting ==")
        r = client.get("/projects/github/status", headers=HDRS)
        check("status 200", r.status_code == 200)
        check("status body is {connected: false}",
              r.json() == {"connected": False}, extra=r.text)

        print("== POST /projects/github/connect validation ==")
        r = client.post("/projects/github/connect", headers=HDRS,
                        json={"token": "x", "repo_full_name": "not-a-repo"})
        check("malformed repo_full_name is a 422 from the request model",
              r.status_code == 422, extra=r.text)

        r = client.post("/projects/github/connect", headers=HDRS,
                        json={"token": "ghp_wrong", "repo_full_name": REPO})
        check("a bad token is a 401", r.status_code == 401, extra=r.text)
        check("the 401 body uses the platform error shape",
              set(r.json()) == {"timestamp", "status", "error"}, extra=r.text)
        check("the rejected token is not echoed back",
              "ghp_wrong" not in r.text, extra=r.text)

        r = client.post("/projects/github/connect", headers=HDRS,
                        json={"token": TOKEN, "repo_full_name": "someone/else"})
        check("a repo the token cannot see is a 404", r.status_code == 404,
              extra=r.text)

        print("== POST /projects/github/connect success ==")
        r = client.post("/projects/github/connect", headers=HDRS,
                        json={"token": TOKEN, "repo_full_name": REPO})
        check("connect 200", r.status_code == 200, extra=r.text)
        body = r.json()
        check("connected=true", body.get("connected") is True)
        check("repo echoed", body.get("repo_full_name") == REPO)
        check("default branch discovered", body.get("default_branch") == "main")
        check("the token is NOT in the connect response", TOKEN not in r.text,
              extra=r.text)
        check("the ciphertext is not in the connect response",
              "encrypted_token" not in body, extra=str(sorted(body)))

        print("== GET status after connecting is still write-only ==")
        r = client.get("/projects/github/status", headers=HDRS)
        check("status connected", r.json().get("connected") is True)
        check("status never returns the token", TOKEN not in r.text)
        check("status never returns the ciphertext",
              "encrypted_token" not in r.text, extra=r.text)

        print("== route ordering: 'github' is not eaten by {project_id} ==")
        r = client.get("/projects/github", headers=HDRS)
        check("GET /projects/github (one segment) is a 404 project lookup",
              r.status_code == 404, extra=r.text)
        r = client.get("/projects/github/status", headers=HDRS)
        check("GET /projects/github/status still resolves to the status route",
              r.status_code == 200 and "connected" in r.json(), extra=r.text)

        print("== tenant isolation over HTTP ==")
        r = client.get("/projects/github/status",
                       headers={"X-Tenant-Id": "tenant_b"})
        check("another tenant sees no connection",
              r.json() == {"connected": False}, extra=r.text)

        print("== the legacy Docker preview routes are gated OFF ==")
        r = client.post("/projects/anything/preview/start", headers=HDRS)
        check("preview/start refuses with 409", r.status_code == 409,
              extra=r.text)
        check("the refusal points at the GitHub PR instead",
              "GitHub" in r.json().get("error", ""), extra=r.text)
        r = client.post("/projects/anything/preview/stop", headers=HDRS)
        check("preview/stop refuses with 409", r.status_code == 409)
        r = client.get("/projects/anything/preview", headers=HDRS)
        check("GET preview reports {active:false, enabled:false} calmly",
              r.status_code == 200
              and r.json() == {"active": False, "enabled": False}, extra=r.text)

        print("== POST /projects with a repo connected pushes a branch ==")
        r = client.post("/projects", headers=HDRS,
                        json={"project_id": "site", "template_id": "static-html"})
        check("create 201", r.status_code == 201, extra=r.text)
        created = r.json()
        check("response carries the branch for the UI to link to",
              (created.get("github") or {}).get("branch") == "app-builder/site",
              extra=r.text)
        check("response carries the PR url for the UI to link to",
              (created.get("github") or {}).get("pr_url")
              == f"https://github.com/{REPO}/pull/1", extra=r.text)
        check("the create response contains no token", TOKEN not in r.text)
        check("the branch really exists in the tenant's repo",
              "app-builder/site" in fake.refs)
        check("code-intel was registered over git, not a local path",
              [r for r in registrations if r.get("source_type") == "git"]
              and not [r for r in registrations
                       if r.get("source_type") == "local"],
              extra=str(registrations))

        r = client.get("/projects/site", headers=HDRS)
        check("GET /projects/{id} returns the same github block",
              (r.json().get("github") or {}).get("pr_number") == 1, extra=r.text)
        check("no token in the project GET either", TOKEN not in r.text)

        r = client.get("/projects", headers=HDRS)
        check("GET /projects lists it", len(r.json()) == 1, extra=r.text)
        check("no token in the project list", TOKEN not in r.text)

        print("== DELETE /projects/github/connect ==")
        r = client.delete("/projects/github/connect", headers=HDRS)
        check("disconnect 200", r.status_code == 200, extra=r.text)
        check("disconnect reports it happened",
              r.json() == {"disconnected": True}, extra=r.text)
        r = client.get("/projects/github/status", headers=HDRS)
        check("status is disconnected again",
              r.json() == {"connected": False}, extra=r.text)
        check("the tenant's branch survives a disconnect",
              "app-builder/site" in fake.refs)

        print("== creating a project with nothing connected is a 409 ==")
        r = client.post("/projects", headers=HDRS,
                        json={"project_id": "orphan", "template_id": "static-html"})
        check("create refused with 409", r.status_code == 409, extra=r.text)
        check("the 409 names the connect endpoint",
              "/projects/github/connect" in r.json().get("error", ""),
              extra=r.text)

        print("== an unset encryption key is a 503, not a 500 ==")
        main.SETTINGS = Settings(
            data_dir=tmp, templates_dir=os.path.join(ROOT, "templates"),
            auth_mode="none", app_builder_encryption_key="",
            github_api_url=API, local_preview_enabled=False)
        r = client.post("/projects/github/connect", headers=HDRS,
                        json={"token": TOKEN, "repo_full_name": REPO})
        check("connect without a key is 503", r.status_code == 503, extra=r.text)
        check("the 503 explains the deployment gap",
              "APP_BUILDER_ENCRYPTION_KEY" in r.json().get("error", ""),
              extra=r.text)
        r = client.get("/health")
        check("health reports github_ready=false without a key",
              r.json().get("github_ready") is False, extra=r.text)

        print("== no token appears anywhere in what was persisted ==")
        for dirpath, _d, filenames in os.walk(tmp):
            for fn in filenames:
                p = os.path.join(dirpath, fn)
                text = open(p, "r", encoding="utf-8", errors="replace").read()
                rel = os.path.relpath(p, tmp).replace(os.sep, "/")
                check(f"token absent from {rel}", TOKEN not in text)

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        main.SETTINGS = orig_settings
        github_client.httpx = orig_httpx
        for k, v in ci_orig.items():
            setattr(ci_client, k, v)
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main_())
