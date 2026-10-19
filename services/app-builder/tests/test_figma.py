"""Figma import: URL parsing, tree->spec reduction, caps, error mapping, SSRF
guards, and that the token never appears in the response, audit log, error
bodies or captured logging. Figma HTTP is faked by replacing figma.httpx.

Run: python -m tests.test_figma   (from the app-builder directory)
"""

from __future__ import annotations

import io
import json
import logging
import os
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from fastapi.testclient import TestClient  # noqa: E402

from app import audit, figma, main, projects  # noqa: E402
from app.settings import Settings  # noqa: E402
from app.tenancy import TenantContext, tenant_root  # noqa: E402

PASS = 0
FAIL = 0
TOKEN = "figd_SECRET_TOKEN_12345"
KEY = "AbCdEfGhIjKlMnOp"
PNG = b"\x89PNG\r\n" + b"x" * 100
IMG_URL = "https://figma-alpha-api.s3.us-west-2.amazonaws.com/images/abc"


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


class Resp:
    def __init__(self, status=200, body=None, content=b""):
        self.status_code = status
        self._body = body
        self.content = content

    def json(self):
        return self._body


DOC = {
    "name": "Landing", "type": "FRAME", "layoutMode": "VERTICAL",
    "itemSpacing": 16, "paddingTop": 24, "paddingRight": 24,
    "paddingBottom": 24, "paddingLeft": 24, "cornerRadius": 12,
    "absoluteBoundingBox": {"width": 1440, "height": 900},
    "fills": [{"type": "SOLID", "color": {"r": 1, "g": 1, "b": 1}}],
    "children": [
        {"name": "Title", "type": "TEXT", "characters": "Hello   world",
         "style": {"fontFamily": "Inter", "fontSize": 32, "fontWeight": 700},
         "fills": [{"type": "SOLID", "color": {"r": 0, "g": 0, "b": 0}}]},
        {"name": "CTA", "type": "INSTANCE", "layoutMode": "HORIZONTAL",
         "itemSpacing": 8, "primaryAxisAlignItems": "CENTER",
         "fills": [{"type": "SOLID", "color": {"r": 0.2, "g": 0.4, "b": 1}}],
         "children": []},
        {"name": "Hidden", "type": "FRAME", "visible": False},
    ],
}


class FakeHttpx:
    """Stands in for the httpx module inside app.figma."""
    Timeout = figma.httpx.Timeout
    HTTPError = figma.httpx.HTTPError
    TimeoutException = figma.httpx.TimeoutException

    def __init__(self, nodes_status=200, image_ok=True, image_url=IMG_URL):
        self.calls: list[dict] = []
        self.nodes_status = nodes_status
        self.image_ok = image_ok
        self.image_url = image_url

    def get(self, url, headers=None, params=None, timeout=None, follow_redirects=None):
        self.calls.append({"url": url, "headers": headers or {},
                           "follow": follow_redirects})
        if url.startswith(figma.FIGMA_API + "/v1/files/"):
            if self.nodes_status != 200:
                return Resp(self.nodes_status)
            return Resp(200, {"nodes": {"1:2": {"document": DOC}}})
        if url.startswith(figma.FIGMA_API + "/v1/images/"):
            return Resp(200, {"images": {"1:2": self.image_url}})
        if url == self.image_url:
            return Resp(200, content=PNG if self.image_ok else b"x" * (figma.MAX_IMAGE_BYTES + 1))
        return Resp(500)


def expect_error(fn, status: int):
    try:
        fn()
    except figma.FigmaError as e:
        return e.status == status, e.message
    return False, "no error raised"


def main_() -> int:
    print("== URL parsing ==")
    check("design url + dashed node", figma.parse_figma_url(
        f"https://www.figma.com/design/{KEY}/Name?node-id=1-2&t=x") == (KEY, "1:2"))
    check("file url + colon node", figma.parse_figma_url(
        f"https://figma.com/file/{KEY}/Name?node-id=1:2") == (KEY, "1:2"))
    check("percent-encoded node", figma.parse_figma_url(
        f"https://www.figma.com/design/{KEY}/N?node-id=1%3A2") == (KEY, "1:2"))
    check("no node -> None", figma.parse_figma_url(
        f"https://www.figma.com/file/{KEY}/N") == (KEY, None))
    check("branch url uses branch key", figma.parse_figma_url(
        f"https://www.figma.com/design/{KEY}/branch/ZzYyXxWwVvUu/N?node-id=3-4")
        == ("ZzYyXxWwVvUu", "3:4"))
    for bad in ["http://www.figma.com/file/" + KEY, "https://evil.com/file/" + KEY,
                "https://figma.com.evil.com/file/" + KEY, "https://www.figma.com/proto/" + KEY,
                "https://www.figma.com/file/x", "notaurl"]:
        ok, _ = expect_error(lambda b=bad: figma.parse_figma_url(b), 400)
        check(f"rejects {bad[:40]}", ok)
    ok, _ = expect_error(lambda: figma.parse_figma_url(
        f"https://www.figma.com/file/{KEY}/N?node-id=abc"), 400)
    check("rejects garbage node-id", ok)

    print("== tree -> spec ==")
    spec = figma.reduce_tree(DOC)
    check("layout hints", "flex-col gap 16 pad 24/24/24/24" in spec, spec)
    check("fill + radius", "fill #ffffff" in spec and "radius 12" in spec)
    check("text style + collapsed whitespace",
          "Inter 32px 700 #000000" in spec and '"Hello world"' in spec, spec)
    check("row flex with justify", "flex-row gap 8 justify-center" in spec)
    check("hidden nodes skipped", "Hidden" not in spec)
    big = {"name": "R", "type": "FRAME", "children": [
        {"name": f"n{i}", "type": "FRAME"} for i in range(2000)]}
    capped = figma.reduce_tree(big, budget=1000)
    check("budget cap enforced", len(capped) < 1300 and "truncated" in capped)

    print("== import_frame ==")
    fake = FakeHttpx()
    figma.httpx = fake
    out = figma.import_frame(f"https://www.figma.com/design/{KEY}/N?node-id=1-2", TOKEN)
    check("context has spec", out["context"].startswith("FIGMA DESIGN SPEC"))
    check("image attached as png", out["image"]["media_type"] == "image/png")
    check("token sent as header only to api.figma.com",
          all(c["headers"].get("X-Figma-Token") == TOKEN
              for c in fake.calls if c["url"].startswith(figma.FIGMA_API)))
    cdn = [c for c in fake.calls if c["url"] == IMG_URL][0]
    check("token NOT sent to image CDN", "X-Figma-Token" not in cdn["headers"])
    check("redirects not followed", all(c["follow"] is False for c in fake.calls))
    check("token not in result", TOKEN not in json.dumps(out))

    figma.httpx = FakeHttpx(image_url="https://evil.example/x.png")
    out = figma.import_frame(f"https://www.figma.com/design/{KEY}/N?node-id=1-2", TOKEN)
    check("non-figma image host refused (spec still returned)",
          "image" not in out and "context" in out)
    figma.httpx = FakeHttpx(image_url="http://figma-alpha-api.s3.amazonaws.com/x")
    out = figma.import_frame(f"https://www.figma.com/design/{KEY}/N?node-id=1-2", TOKEN)
    check("http image url refused", "image" not in out)
    figma.httpx = FakeHttpx(image_ok=False)
    out = figma.import_frame(f"https://www.figma.com/design/{KEY}/N?node-id=1-2", TOKEN)
    check("oversize image dropped", "image" not in out)

    print("== error mapping ==")
    u = f"https://www.figma.com/design/{KEY}/N?node-id=1-2"
    for st, want in [(403, 400), (401, 400), (404, 404), (429, 429), (500, 502)]:
        figma.httpx = FakeHttpx(nodes_status=st)
        ok, msg = expect_error(lambda: figma.import_frame(u, TOKEN), want)
        check(f"figma {st} -> {want}", ok and TOKEN not in msg, msg)
    ok, _ = expect_error(lambda: figma.import_frame(u, "  "), 400)
    check("empty token rejected", ok)
    ok, _ = expect_error(lambda: figma.import_frame(
        f"https://www.figma.com/design/{KEY}/N", TOKEN), 400)
    check("missing node-id rejected", ok)

    print("== route: no token in response, audit, or logs ==")
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-figma-")
    orig = main.SETTINGS
    main.SETTINGS = Settings(data_dir=tmp, templates_dir=os.path.join(ROOT, "templates"),
                             auth_mode="none")
    ctx = TenantContext(tenant_id="tenant_a", subject="tester")
    os.makedirs(tenant_root(tmp, ctx), exist_ok=True)
    projects.upsert(tmp, ctx, projects.Project(
        project_id="site", tenant_id="tenant_a", template_id="static-html",
        status="ready", code_intel_repo_id="r"))
    buf = io.StringIO()
    h = logging.StreamHandler(buf)
    logging.getLogger().addHandler(h)
    logging.getLogger().setLevel(logging.DEBUG)
    client = TestClient(main.app)
    hd = {"X-Tenant-Id": "tenant_a"}
    try:
        figma.httpx = FakeHttpx()
        r = client.post("/projects/site/design/figma", headers=hd, json={"url": u, "token": TOKEN})
        check("200 with context + image", r.status_code == 200
              and "context" in r.json() and "image" in r.json(), r.text)
        check("token not in body", TOKEN not in r.text)
        check("audit has file key, no token",
              any(e.get("kind") == "figma_imported" and e.get("file_key") == KEY
                  for e in audit.tail(tenant_root(tmp, ctx)))
              and TOKEN not in json.dumps(audit.tail(tenant_root(tmp, ctx))))
        figma.httpx = FakeHttpx(nodes_status=403)
        r = client.post("/projects/site/design/figma", headers=hd, json={"url": u, "token": TOKEN})
        check("bad token -> 400 without token", r.status_code == 400 and TOKEN not in r.text, r.text)
        r = client.post("/projects/nope/design/figma", headers=hd, json={"url": u, "token": TOKEN})
        check("unknown project 404", r.status_code == 404 and TOKEN not in r.text)
        big = TOKEN + "x" * 600
        r = client.post("/projects/site/design/figma", headers=hd, json={"url": u, "token": big})
        check("oversize token rejected without echo", r.status_code in (400, 422) and TOKEN not in r.text, r.text[:200])
        r = client.post("/projects/site/design/figma", headers=hd, json={"token": TOKEN})
        check("missing url: 422 without token echo", r.status_code == 422 and TOKEN not in r.text, r.text[:200])
        check("token never logged", TOKEN not in buf.getvalue())
    finally:
        logging.getLogger().removeHandler(h)
        main.SETTINGS = orig

    print(f"\n{PASS} passed, {FAIL} failed")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main_())
