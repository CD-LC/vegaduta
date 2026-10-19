"""Framework-free tests for app/ingress.py — no real Caddy instance required.

Mocks `httpx.request` (the single entry point `_admin_request` calls) with a
small programmable fake, so this covers: "path" mode builds the right
handle_path-equivalent JSON route body and returns the documented URL,
"subdomain" mode likewise with a Host matcher, idempotent re-registration
takes the PATCH-first fast path without ever calling the server-discovery
GET, front-of-array insertion is used (not append) the first time a project
registers, deregister issues DELETE /id/<route_id> and tolerates 404 (already
gone), and an unreachable/erroring admin API falls back to the raw
host:port URL without raising — with the fallback recorded to the tenant-less
`_ingress` audit directory.

Run: python3 -m tests.test_ingress   (from the app-builder directory)
"""

from __future__ import annotations

import json
import os
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

import httpx  # noqa: E402

from app import ingress  # noqa: E402
from app.settings import Settings  # noqa: E402

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


class FakeResponse:
    def __init__(self, status_code: int, body=None):
        self.status_code = status_code
        self._body = body if body is not None else {}
        self.text = json.dumps(self._body)

    def json(self):
        return self._body


class FakeHttpx:
    """Stands in for `httpx.request`. `plan` is a callable
    (method, path) -> FakeResponse | Exception-to-raise, so each test can
    script exactly what the fake Caddy admin API does without a real
    server."""

    def __init__(self, plan):
        self.plan = plan
        self.calls: list[tuple[str, str, dict | None]] = []

    def __call__(self, method, url, json=None, timeout=None):
        path = url.split("://", 1)[1].split("/", 1)[1]
        path = "/" + path
        self.calls.append((method, path, json))
        result = self.plan(method, path)
        if isinstance(result, Exception):
            raise result
        return result


def _settings(tmp: str, **overrides) -> Settings:
    kwargs = dict(data_dir=tmp, caddy_admin_url="http://fake-caddy:2019",
                 preview_domain="preview.staging.example.com",
                 ingress_mode="path",
                 preview_path_base_url="http://localhost:8899")
    kwargs.update(overrides)
    return Settings(**kwargs)


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-ingress-")
    orig_request = httpx.request
    try:
        print("== path mode: fresh registration inserts at routes/0 ==")
        settings = _settings(tmp, ingress_mode="path")

        def plan_fresh_path(method, path):
            if method == "PATCH" and path == "/id/preview-proj1":
                return FakeResponse(404, {"error": "unknown id"})
            if method == "GET" and path == "/config/apps/http/servers":
                return FakeResponse(200, {
                    "srv0": {"listen": [":8899"], "routes": [{"handle": []}]},
                })
            if method == "PUT" and path == "/config/apps/http/servers/srv0/routes/0":
                return FakeResponse(200, {})
            raise AssertionError(f"unexpected call {method} {path}")

        fake = FakeHttpx(plan_fresh_path)
        httpx.request = fake
        url = ingress.register_preview_route(
            "proj1", "10.0.0.5", 4000, settings=settings)

        check("returns the path-mode URL",
              url == "http://localhost:8899/preview/proj1/", extra=url)
        methods = [c[0] for c in fake.calls]
        check("tried PATCH first, then discovered the server, then PUT-inserted",
              methods == ["PATCH", "GET", "PUT"], extra=str(methods))

        insert_body = fake.calls[-1][2]
        check("inserted route carries the deterministic @id",
              insert_body["@id"] == "preview-proj1")
        check("path match targets /preview/proj1/*",
              insert_body["match"] == [{"path": ["/preview/proj1/*"]}])
        check("first handler strips the /preview/proj1 prefix",
              insert_body["handle"][0] == {
                  "handler": "rewrite", "strip_path_prefix": "/preview/proj1"})
        check("second handler reverse_proxies to the upstream host:port",
              insert_body["handle"][1]["handler"] == "reverse_proxy"
              and insert_body["handle"][1]["upstreams"] == [
                  {"dial": "10.0.0.5:4000"}])
        check("inserted at index 0 of the routes array (before the catch-all)",
              fake.calls[-1][1] ==
              "/config/apps/http/servers/srv0/routes/0")

        print("== path mode: re-registering the SAME project takes the "
              "PATCH-only fast path (no server lookup) ==")

        def plan_reregister(method, path):
            if method == "PATCH" and path == "/id/preview-proj1":
                return FakeResponse(200, {})
            raise AssertionError(f"unexpected call {method} {path}")

        fake2 = FakeHttpx(plan_reregister)
        httpx.request = fake2
        url2 = ingress.register_preview_route(
            "proj1", "10.0.0.6", 4001, settings=settings)
        check("still returns the path-mode URL", url2 == url)
        check("only issued the PATCH — no GET/PUT needed on update",
              [c[0] for c in fake2.calls] == ["PATCH"])

        print("== subdomain mode: fresh registration uses a Host matcher ==")
        settings_sub = _settings(tmp, ingress_mode="subdomain")

        def plan_fresh_sub(method, path):
            if method == "PATCH" and path == "/id/preview-proj2":
                return FakeResponse(404)
            if method == "GET" and path == "/config/apps/http/servers":
                return FakeResponse(200, {
                    "srv0": {"listen": [":8899"], "routes": []},
                })
            if method == "PUT" and path == "/config/apps/http/servers/srv0/routes/0":
                return FakeResponse(201, {})
            raise AssertionError(f"unexpected call {method} {path}")

        fake3 = FakeHttpx(plan_fresh_sub)
        httpx.request = fake3
        url3 = ingress.register_preview_route(
            "proj2", "10.0.0.7", 5000, settings=settings_sub)
        check("returns the https subdomain URL",
              url3 == "https://proj2.preview.staging.example.com", extra=url3)
        sub_body = fake3.calls[-1][2]
        check("subdomain route matches on Host, not path",
              sub_body["match"] == [{"host": ["proj2.preview.staging.example.com"]}])
        check("subdomain route still reverse_proxies to upstream",
              sub_body["handle"][0]["upstreams"] == [
                  {"dial": "10.0.0.7:5000"}])

        print("== server discovery skips servers not listening on :8899 ==")
        settings_disc = _settings(tmp, ingress_mode="path")

        def plan_multi_server(method, path):
            if method == "PATCH" and path == "/id/preview-proj3":
                return FakeResponse(404)
            if method == "GET" and path == "/config/apps/http/servers":
                return FakeResponse(200, {
                    "srv-other": {"listen": [":9000"], "routes": []},
                    "srv-real": {"listen": [":8899"], "routes": []},
                })
            if method == "PUT" and path == "/config/apps/http/servers/srv-real/routes/0":
                return FakeResponse(200, {})
            raise AssertionError(f"unexpected call {method} {path}")

        fake4 = FakeHttpx(plan_multi_server)
        httpx.request = fake4
        url4 = ingress.register_preview_route(
            "proj3", "10.0.0.8", 6000, settings=settings_disc)
        check("picked the :8899 server, not the first key in the dict",
              url4 == "http://localhost:8899/preview/proj3/", extra=url4)

        print("== deregister: DELETE /id/<route_id>, tolerates 404 ==")

        def plan_delete_ok(method, path):
            check("deletes the exact deterministic id",
                  method == "DELETE" and path == "/id/preview-proj1")
            return FakeResponse(204)

        httpx.request = FakeHttpx(plan_delete_ok)
        ingress.deregister_preview_route("proj1", settings=settings)  # no raise

        def plan_delete_already_gone(method, path):
            return FakeResponse(404)

        httpx.request = FakeHttpx(plan_delete_already_gone)
        try:
            ingress.deregister_preview_route("proj1", settings=settings)
            check("404 on delete (already gone) does not raise", True)
        except Exception as e:  # noqa: BLE001
            check("404 on delete (already gone) does not raise", False, str(e))

        print("== Caddy admin API unreachable: falls back, never raises ==")
        settings_fb = _settings(tmp, ingress_mode="path")

        def plan_unreachable(method, path):
            return httpx.ConnectError("connection refused")

        httpx.request = FakeHttpx(plan_unreachable)
        try:
            fb_url = ingress.register_preview_route(
                "proj-down", "10.0.0.9", 7000, settings=settings_fb)
            check("returns the raw host:port URL as fallback",
                  fb_url == "http://10.0.0.9:7000", extra=fb_url)
        except Exception as e:  # noqa: BLE001
            check("register_preview_route never raises on transport failure",
                  False, str(e))

        audit_path = os.path.join(settings_fb.data_dir, "_ingress", "audit.jsonl")
        check("fallback was recorded to the _ingress audit log",
              os.path.exists(audit_path))
        if os.path.exists(audit_path):
            with open(audit_path, encoding="utf-8") as f:
                events = [json.loads(line) for line in f if line.strip()]
            check("audit event names the failed project",
                  any(e.get("project_id") == "proj-down"
                      and e.get("kind") == "preview_ingress_fallback"
                      for e in events), extra=str(events))

        print("== deregister also falls back to a logged no-op on failure "
              "(never raises) ==")
        httpx.request = FakeHttpx(plan_unreachable)
        try:
            ingress.deregister_preview_route("proj-down", settings=settings_fb)
            check("deregister never raises on transport failure", True)
        except Exception as e:  # noqa: BLE001
            check("deregister never raises on transport failure", False, str(e))

        print("== unexpected non-404 status on PATCH also falls back ==")
        settings_500 = _settings(tmp, ingress_mode="path")

        def plan_server_error(method, path):
            if method == "PATCH":
                return FakeResponse(500, {"error": "boom"})
            raise AssertionError(f"unexpected call {method} {path}")

        httpx.request = FakeHttpx(plan_server_error)
        fb_url2 = ingress.register_preview_route(
            "proj-500", "10.0.0.10", 8000, settings=settings_500)
        check("500 from Caddy also degrades to the raw host:port URL",
              fb_url2 == "http://10.0.0.10:8000", extra=fb_url2)

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        httpx.request = orig_request
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
