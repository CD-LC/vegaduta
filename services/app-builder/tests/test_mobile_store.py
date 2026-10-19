"""Store-ready mobile output: validation, generated files, template manifest,
release workflow (YAML parse + graceful-skip guard), the android release
config patch script, and the store-config apply route logic.

Run: python3 -m tests.test_mobile_store   (from the app-builder directory)
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from cryptography.fernet import Fernet  # noqa: E402

from app import github_client, github_credentials, mobile_store, scaffold  # noqa: E402
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
TDIR = os.path.join(ROOT, "templates", "capacitor-app")

GOOD = {
    "app_name": "Acme Shop",
    "package_id": "com.acme.shop",
    "version_name": "1.2.0",
    "short_description": "Order anything from Acme in two taps.",
    "full_description": "Acme Shop lets you browse, order and track.\nSecond line.",
}


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def rejects(**overrides) -> str | None:
    cfg = dict(GOOD)
    cfg.update(overrides)
    try:
        mobile_store.validate_store_config(cfg)
    except GenerateError as e:
        return str(e)
    return None


def test_validation():
    print("== validation ==")
    ok = mobile_store.validate_store_config(GOOD)
    check("good config passes", ok["package_id"] == "com.acme.shop")
    check("title 30 ok / 31 rejected",
          rejects(app_name="a" * 30) is None
          and "at most 30" in (rejects(app_name="a" * 31) or ""))
    check("short 80 ok / 81 rejected",
          rejects(short_description="s" * 80) is None
          and "at most 80" in (rejects(short_description="s" * 81) or ""))
    check("full 4000 ok / 4001 rejected",
          rejects(full_description="f" * 4000) is None
          and "at most 4000" in (rejects(full_description="f" * 4001) or ""))
    for bad in ["myapp", "Com.Acme.App", "com.acme.1app", "com..app",
                "com.acme.app!", "com.acme.class", "", "com.acme-app.x"]:
        check(f"package id {bad!r} rejected", rejects(package_id=bad) is not None)
    for good in ["com.acme.app", "io.a_b.c9", "app.builder.generated"]:
        check(f"package id {good!r} accepted", rejects(package_id=good) is None)
    check("multi-line title rejected", rejects(app_name="a\nb") is not None)
    check("empty short rejected", rejects(short_description="   ") is not None)
    check("bad version rejected", rejects(version_name="v1") is not None)
    check("beta version accepted", rejects(version_name="1.0.0-beta.1") is None)


def test_generated_files():
    print("== generated files ==")
    cfg = mobile_store.validate_store_config(GOOD)
    files = {f["path"]: f["content"] for f in mobile_store.render_metadata_files(cfg)}
    a = "fastlane/metadata/android/en-US"
    check("android title", files[f"{a}/title.txt"] == "Acme Shop\n")
    check("android short", files[f"{a}/short_description.txt"].startswith("Order anything"))
    check("android full multi-line preserved", "\nSecond line." in files[f"{a}/full_description.txt"])
    check("changelog default within 500 chars",
          0 < len(files[f"{a}/changelogs/default.txt"]) <= 500
          and "1.2.0" in files[f"{a}/changelogs/default.txt"])
    check("ios metadata present",
          all(f"fastlane/metadata/ios/en-US/{n}.txt" in files
              for n in ("name", "subtitle", "description", "keywords", "release_notes")))
    check("ios subtitle <= 30", len(files["fastlane/metadata/ios/en-US/subtitle.txt"].strip()) <= 30)
    check("privacy stub flagged as stub", "STUB" in files["PRIVACY-POLICY.md"])
    chk = files["STORE-CHECKLIST.md"]
    check("checklist tailored (package id + secrets + accessibility)",
          "com.acme.shop" in chk and "ANDROID_KEYSTORE_BASE64" in chk
          and "AccessibilityService" in chk and "Data safety" in chk)
    check("store-config.json roundtrips",
          json.loads(files[mobile_store.STORE_CONFIG_PATH]) == cfg)

    edits = {f["path"]: f["content"] for f in mobile_store.render_config_edits(
        cfg, json.dumps({"appId": "x.y", "appName": "Old", "webDir": "dist",
                         "plugins": {"K": 1}}), json.dumps({"name": "n", "version": "0.0.1"}))}
    cap = json.loads(edits["capacitor.config.json"])
    check("capacitor config edited, other keys kept",
          cap["appId"] == "com.acme.shop" and cap["appName"] == "Acme Shop"
          and cap["plugins"] == {"K": 1} and cap["webDir"] == "dist")
    check("package.json version set", json.loads(edits["package.json"])["version"] == "1.2.0")
    unreadable = mobile_store.render_config_edits(cfg, None, None)
    check("missing capacitor config falls back to webDir dist",
          json.loads(unreadable[0]["content"])["webDir"] == "dist" and len(unreadable) == 1)


def test_template():
    print("== template manifest + workflow ==")
    settings = Settings(data_dir=tempfile.mkdtemp(), templates_dir=os.path.join(ROOT, "templates"))
    manifest = scaffold.get_template_manifest("capacitor-app", settings)
    paths = {f["path"] for f in scaffold.template_files("capacitor-app", settings)}
    for p in ("assets/icon.png", "PRIVACY-POLICY.md", "STORE-CHECKLIST.md",
              "store/store-config.json", "scripts/android-release-config.mjs",
              "scripts/generate-icons.mjs",
              "fastlane/metadata/android/en-US/title.txt",
              "fastlane/metadata/android/en-US/changelogs/default.txt",
              "fastlane/metadata/ios/en-US/name.txt"):
        check(f"template ships {p}", p in paths)
    icon = [f for f in scaffold.template_files("capacitor-app", settings)
            if f["path"] == "assets/icon.png"][0]["content"]
    check("icon is a PNG", icon[:8] == b"\x89PNG\r\n\x1a\n")
    pkg = json.load(open(os.path.join(TDIR, "package.json"), encoding="utf-8"))
    check("package.json has assets script + @capacitor/assets",
          "assets" in pkg["scripts"] and "@capacitor/assets" in pkg["devDependencies"])
    # template files equal the render of the default config (no drift)
    rendered = {f["path"]: f["content"] for f in mobile_store.render_metadata_files(
        mobile_store.default_config())}
    drift = [p for p, c in rendered.items()
             if open(os.path.join(TDIR, p), encoding="utf-8", newline="").read().replace("\r\n", "\n") != c]
    check("template metadata == render(default_config)", not drift, str(drift))

    wf = scaffold.starter_workflow(manifest, "app-builder/x")
    check("workflow has release job + AAB build",
          "android-release:" in wf and "bundleRelease" in wf and "app-release-aab" in wf)
    check("workflow references all four secrets",
          all(f"secrets.{s}" in wf for s in (
              "ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD",
              "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")))
    check("debug + ios jobs retained", "assembleDebug" in wf and "\n  ios:" in wf)
    check("no unreplaced {branch}", "{branch}" not in wf)
    check("versionCode from run number, name from tag",
          "github.run_number" in wf and "git describe --tags" in wf)
    check("icons wired into CI", "generate-icons.mjs --android" in wf)

    try:
        import yaml
    except ImportError:
        print("  skip  yaml parse (PyYAML not installed)")
        return
    doc = yaml.safe_load(wf)
    jobs = doc["jobs"]
    check("workflow YAML parses; jobs present",
          {"check", "android", "android-release", "ios"} <= set(jobs), str(list(jobs)))
    steps = jobs["android-release"]["steps"]
    gate_idx = [i for i, s in enumerate(steps) if s.get("id") == "gate"]
    check("first step is the secrets gate", gate_idx == [0])
    later = steps[1:]
    guarded = [s for s in later if "steps.gate.outputs.signed == 'true'" in str(s.get("if", ""))]
    check("EVERY step after the gate is skipped when secrets are absent",
          len(guarded) == len(later), f"{len(guarded)}/{len(later)}")
    check("gate emits a notice, not a failure",
          "::notice" in steps[0]["run"] and "exit 1" not in steps[0]["run"])


def test_release_script():
    print("== android-release-config.mjs ==")
    node = shutil.which("node")
    if not node:
        print("  skip  node not installed")
        return
    d = tempfile.mkdtemp()
    os.makedirs(os.path.join(d, "app"))
    with open(os.path.join(d, "variables.gradle"), "w") as f:
        f.write("ext {\n    minSdkVersion = 23\n    compileSdkVersion = 35\n    targetSdkVersion = 35\n}\n")
    with open(os.path.join(d, "app", "build.gradle"), "w") as f:
        f.write('android {\n    defaultConfig {\n        versionCode 1\n        versionName "1.0"\n    }\n'
                "    buildTypes {\n        release {\n            minifyEnabled false\n        }\n    }\n}\n")
    env = dict(os.environ, VERSION_CODE="42", VERSION_NAME="2.3.4")
    script = os.path.join(TDIR, "scripts", "android-release-config.mjs")
    for _ in range(2):  # idempotent
        r = subprocess.run([node, script, d], env=env, capture_output=True, text=True)
        check("script exits 0", r.returncode == 0, r.stderr)
    v = open(os.path.join(d, "variables.gradle")).read()
    g = open(os.path.join(d, "app", "build.gradle")).read()
    check("targetSdk/compileSdk 36", "targetSdkVersion = 36" in v and "compileSdkVersion = 36" in v
          and "minSdkVersion = 23" in v)
    check("versionCode/name applied", "versionCode 42" in g and 'versionName "2.3.4"' in g)
    check("signing config from env + release uses it",
          "ANDROID_KEYSTORE_PATH" in g and "signingConfig signingConfigs.release" in g)
    bt = g.split("buildTypes {", 1)[1] if "buildTypes {" in g else ""
    check("signingConfig is inside buildTypes.release (not signingConfigs)",
          "signingConfig signingConfigs.release" in bt
          and "signingConfig signingConfigs.release" not in g.split("buildTypes {", 1)[0])
    check("16KB packaging setting", "useLegacyPackaging = false" in g)
    check("idempotent: signing block injected once", g.count("signingConfigs {") == 1
          and g.count("signingConfig signingConfigs.release") == 1)


def test_route_logic():
    print("== store-config apply ==")
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-mobile-")
    settings = Settings(
        data_dir=tmp, templates_dir=os.path.join(ROOT, "templates"),
        app_builder_encryption_key=Fernet.generate_key().decode(), github_api_url=API)
    ctx = TenantContext(tenant_id="tenant_a", subject="admin@acme.test")
    fake = FakeGitHub(api_base=API, token=TOKEN, repo=REPO)
    orig_httpx = github_client.httpx
    saved = {k: getattr(ci_client, k) for k in (
        "register_repo", "index_repo", "search", "create_workspace",
        "apply_files", "run", "diff", "delete_workspace")}
    applied: list[list] = []
    try:
        github_client.httpx = fake.shim()
        ci_client.register_repo = lambda c, rid, **kw: {"repo_id": rid}
        ci_client.index_repo = lambda c, rid, **kw: {}
        ci_client.search = lambda c, rid, q, **kw: []
        ci_client.create_workspace = lambda c, rid: {"workspace_id": "ws-1"}
        ci_client.apply_files = lambda c, rid, ws, files, **kw: applied.append(files) or {}
        ci_client.run = lambda c, rid, ws, cmd, **kw: {"success": True, "final": {"stdout": "", "stderr": ""}}
        ci_client.diff = lambda c, rid, ws: {"diff": "+++ b\n"}
        ci_client.delete_workspace = lambda c, rid, ws: {}

        github_credentials.connect(ctx, TOKEN, REPO, settings)
        scaffold.create_project(ctx, "mob", "capacitor-app", None, settings)
        scaffold.create_project(ctx, "web", "node-react", None, settings)

        st = mobile_store.store_status(ctx, "mob", settings)
        check("status lists the scaffolded store files",
              "assets/icon.png" in st["paths"] and "STORE-CHECKLIST.md" in st["paths"])
        check("status config defaults reported", st["config"]["package_id"] == "app.builder.generated")

        try:
            mobile_store.apply_store_config(ctx, "web", GOOD, settings)
            check("non-capacitor project refused", False)
        except GenerateError as e:
            check("non-capacitor project refused", "capacitor-app" in str(e))
        try:
            mobile_store.apply_store_config(ctx, "mob", dict(GOOD, package_id="bad"), settings)
            check("invalid config refused before any commit", False)
        except GenerateError:
            check("invalid config refused before any commit", not applied)

        res = mobile_store.apply_store_config(ctx, "mob", GOOD, settings)
        check("apply succeeded", res["success"] is True, str(res))
        paths = {f["path"] for f in applied[-1]}
        check("metadata + capacitor config + package.json in ONE apply",
              {"capacitor.config.json", "package.json", "STORE-CHECKLIST.md",
               "fastlane/metadata/android/en-US/title.txt"} <= paths)
        cap = [f for f in applied[-1] if f["path"] == "capacitor.config.json"][0]
        check("capacitor.config.json got new appId", json.loads(cap["content"])["appId"] == "com.acme.shop")
        st2 = mobile_store.store_status(ctx, "mob", settings)
        check("status after apply reads the stored config",
              st2["config"]["app_name"] == "Acme Shop" and st2["config"]["package_id"] == "com.acme.shop"
              and st2["has_store_config"] is True, str(st2["config"]))
    finally:
        github_client.httpx = orig_httpx
        for k, v in saved.items():
            setattr(ci_client, k, v)


def main() -> int:
    test_validation()
    test_generated_files()
    test_template()
    test_release_script()
    test_route_logic()
    print(f"\n{PASS} passed, {FAIL} failed")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
