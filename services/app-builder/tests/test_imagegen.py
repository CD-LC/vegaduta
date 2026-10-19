"""AI image generation -> assets/. core's call is monkeypatched (no network);
the commit path is the real assets pipeline against the fake GitHub.

Covers: per-kind prompt scaffolding, kind/size/prompt validation, magic-byte
and size caps, provider-unavailable error, and the committed asset path.

Run: python3 -m tests.test_imagegen   (from the app-builder directory)
"""

from __future__ import annotations

import base64
import os
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from cryptography.fernet import Fernet  # noqa: E402

from app import assets, github_client, github_credentials, imagegen, scaffold  # noqa: E402
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

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def raises(fn, needle: str) -> bool:
    try:
        fn()
    except GenerateError as e:
        return needle in str(e)
    return False


def b64(b: bytes) -> str:
    return base64.b64encode(b).decode()


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-imagegen-")
    settings = Settings(
        data_dir=tmp, templates_dir=os.path.join(ROOT, "templates"),
        app_builder_encryption_key=Fernet.generate_key().decode(),
        github_api_url=API)
    ctx = TenantContext(tenant_id="tenant_a", subject="admin@acme.test")
    fake = FakeGitHub(api_base=API, token=TOKEN, repo=REPO)
    orig = (github_client.httpx, ci_client.register_repo,
            ci_client.index_repo, imagegen._call_core)
    try:
        github_client.httpx = fake.shim()
        ci_client.register_repo = lambda c, rid, **kw: {"repo_id": rid}
        ci_client.index_repo = lambda c, rid, **kw: {}
        github_credentials.connect(ctx, TOKEN, REPO, settings)
        scaffold.create_project(ctx, "site", "node-react", None, settings)

        print("== prompt scaffolding ==")
        lp = imagegen.build_prompt("logo", "  a fox   for   Acme ")
        check("logo prompt embeds the user text once, whitespace-normalized",
              "a fox for Acme" in lp and lp.count("a fox") == 1, lp)
        check("logo scaffold forbids text artifacts", "No text" in lp)
        check("hero differs from logo",
              imagegen.build_prompt("hero", "x") != imagegen.build_prompt("logo", "x"))
        check("default sizes per kind",
              imagegen.resolve_size("hero", None) == "1536x1024"
              and imagegen.resolve_size("logo", None) == "1024x1024")

        print("== validation before any core call ==")
        calls = []
        imagegen._call_core = lambda *a, **k: calls.append(1) or {}
        check("bad kind rejected",
              raises(lambda: imagegen.generate_asset(ctx, "site", "x", "poster", None, settings), "unknown image kind"))
        check("empty prompt rejected",
              raises(lambda: imagegen.generate_asset(ctx, "site", "   ", "logo", None, settings), "prompt is required"))
        check("overlong prompt rejected",
              raises(lambda: imagegen.generate_asset(ctx, "site", "a" * 1001, "logo", None, settings), "too long"))
        check("bad size rejected",
              raises(lambda: imagegen.generate_asset(ctx, "site", "x", "logo", "9x9", settings), "unsupported size"))
        check("core never called for invalid input", calls == [])

        print("== success commits under assets/ ==")
        seen = {}

        def ok_core(c, prompt, size, s):
            seen.update(prompt=prompt, size=size)
            return {"contentBase64": b64(PNG), "model": "m1", "provider": "OPENAI"}
        imagegen._call_core = ok_core
        r = imagegen.generate_asset(ctx, "site", "a blue fox", "logo", None, settings)
        check("path under assets/ with .png",
              r["path"].startswith("assets/logo-a-blue-fox-") and r["path"].endswith(".png"), r["path"])
        check("scaffolded prompt + default size sent to core",
              "No text" in seen["prompt"] and seen["size"] == "1024x1024")
        check("metadata returned", r["kind"] == "logo" and r["provider"] == "OPENAI")
        check("asset listed on the branch",
              any(a["path"] == r["path"] for a in assets.list_assets(ctx, "site", settings)))

        print("== bad bytes ==")
        imagegen._call_core = lambda *a: {"contentBase64": b64(b"<html>not an image</html>")}
        check("non-image bytes rejected",
              raises(lambda: imagegen.generate_asset(ctx, "site", "x", "icon", None, settings), "unsupported file"))
        imagegen._call_core = lambda *a: {"contentBase64": "!!!notb64"}
        check("invalid base64 rejected",
              raises(lambda: imagegen.generate_asset(ctx, "site", "x", "icon", None, settings), "invalid image data"))
        imagegen._call_core = lambda *a: {}
        check("empty response rejected",
              raises(lambda: imagegen.generate_asset(ctx, "site", "x", "icon", None, settings), "no image"))
        big = PNG + b"\x00" * (assets._MAX_ASSET_BYTES + 1)
        imagegen._call_core = lambda *a: {"contentBase64": b64(big)}
        check("oversize image rejected",
              raises(lambda: imagegen.generate_asset(ctx, "site", "x", "hero", None, settings), "too large"))

        print("== provider unavailable surfaces clearly ==")

        def no_provider(*a):
            raise GenerateError("no image provider available - add a key")
        imagegen._call_core = no_provider
        check("provider-unavailable error propagates",
              raises(lambda: imagegen.generate_asset(ctx, "site", "x", "logo", None, settings), "no image provider available"))

        print("== _call_core maps core's 409 code ==")

        class Resp:
            status_code = 409
            content = b"x"

            def json(self):
                return {"error": "raw", "code": "no_image_provider"}
        imagegen._call_core = orig[3]
        real_request = imagegen.httpx.request
        imagegen.httpx.request = lambda *a, **k: Resp()
        try:
            check("409 no_image_provider -> clear message",
                  raises(lambda: imagegen._call_core(ctx, "p", "1024x1024", settings), "no image provider available"))
        finally:
            imagegen.httpx.request = real_request
    finally:
        github_client.httpx, ci_client.register_repo, ci_client.index_repo, imagegen._call_core = orig

    print(f"\n{PASS} passed, {FAIL} failed")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
