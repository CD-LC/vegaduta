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

import hashlib
import json
import logging
import os
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

import httpx  # noqa: E402

from app import ingress  # noqa: E402
from app.settings import Settings  # noqa: E402
from app.tenancy import TenantValidationError  # noqa: E402

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


TENANT = "tenant_a"
KEY = hashlib.sha256(TENANT.encode()).hexdigest()[:16]


def _settings(tmp: str, **overrides) -> Settings:
    kwargs = dict(
        data_dir=tmp,
        caddy_admin_url="http://fake-caddy:2019",
        preview_domain="preview.staging.example.com",
        ingress_mode="path",
        preview_path_base_url="http://localhost:8899",
    )
    kwargs.update(overrides)
    return Settings(**kwargs)


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-ingress-")
    orig_request = httpx.request
    try:
        print("== path mode: fresh registration inserts at routes/0 ==")
        settings = _settings(tmp, ingress_mode="path")

        def plan_fresh_path(method, path):
            if method == "PATCH" and path == f"/id/preview-{KEY}-proj1":
                return FakeResponse(404, {"error": "unknown id"})
            if method == "GET" and path == "/config/apps/http/servers":
                return FakeResponse(
                    200,
                    {
                        "srv0": {"listen": [":8899"], "routes": [{"handle": []}]},
                    },
                )
            if method == "PUT" and path == "/config/apps/http/servers/srv0/routes/0":
                return FakeResponse(200, {})
            raise AssertionError(f"unexpected call {method} {path}")

        fake = FakeHttpx(plan_fresh_path)
        httpx.request = fake
        url = ingress.register_preview_route(TENANT, "proj1", "10.0.0.5", 4000, settings=settings)

        check(
            "returns the path-mode URL",
            url == f"http://localhost:8899/preview/{KEY}/proj1/",
            extra=url,
        )
        methods = [c[0] for c in fake.calls]
        check(
            "tried PATCH first, then discovered the server, then PUT-inserted",
            methods == ["PATCH", "GET", "PUT"],
            extra=str(methods),
        )

        insert_body = fake.calls[-1][2]
        check(
            "inserted route carries the deterministic @id",
            insert_body["@id"] == f"preview-{KEY}-proj1",
        )
        check(
            "path match targets /preview/<tenant key>/proj1/*",
            insert_body["match"] == [{"path": [f"/preview/{KEY}/proj1/*"]}],
        )
        check(
            "first handler strips the /preview/<tenant key>/proj1 prefix",
            insert_body["handle"][0]
            == {"handler": "rewrite", "strip_path_prefix": f"/preview/{KEY}/proj1"},
        )
        check(
            "second handler reverse_proxies to the upstream host:port",
            insert_body["handle"][1]["handler"] == "reverse_proxy"
            and insert_body["handle"][1]["upstreams"] == [{"dial": "10.0.0.5:4000"}],
        )
        check(
            "inserted at index 0 of the routes array (before the catch-all)",
            fake.calls[-1][1] == "/config/apps/http/servers/srv0/routes/0",
        )

        print(
            "== path mode: re-registering the SAME project takes the "
            "PATCH-only fast path (no server lookup) =="
        )

        def plan_reregister(method, path):
            if method == "PATCH" and path == f"/id/preview-{KEY}-proj1":
                return FakeResponse(200, {})
            raise AssertionError(f"unexpected call {method} {path}")

        fake2 = FakeHttpx(plan_reregister)
        httpx.request = fake2
        url2 = ingress.register_preview_route(TENANT, "proj1", "10.0.0.6", 4001, settings=settings)
        check("still returns the path-mode URL", url2 == url)
        check(
            "only issued the PATCH — no GET/PUT needed on update",
            [c[0] for c in fake2.calls] == ["PATCH"],
        )

        print("== subdomain mode: fresh registration uses a Host matcher ==")
        settings_sub = _settings(tmp, ingress_mode="subdomain")

        def plan_fresh_sub(method, path):
            if method == "PATCH" and path == f"/id/preview-{KEY}-proj2":
                return FakeResponse(404)
            if method == "GET" and path == "/config/apps/http/servers":
                return FakeResponse(
                    200,
                    {
                        "srv0": {"listen": [":8899"], "routes": []},
                    },
                )
            if method == "PUT" and path == "/config/apps/http/servers/srv0/routes/0":
                return FakeResponse(201, {})
            raise AssertionError(f"unexpected call {method} {path}")

        fake3 = FakeHttpx(plan_fresh_sub)
        httpx.request = fake3
        url3 = ingress.register_preview_route(
            TENANT, "proj2", "10.0.0.7", 5000, settings=settings_sub
        )
        check(
            "returns the https subdomain URL",
            url3 == f"https://{KEY}-proj2.preview.staging.example.com",
            extra=url3,
        )
        sub_body = fake3.calls[-1][2]
        check(
            "subdomain route matches on Host, not path",
            sub_body["match"] == [{"host": [f"{KEY}-proj2.preview.staging.example.com"]}],
        )
        check(
            "subdomain route still reverse_proxies to upstream",
            sub_body["handle"][0]["upstreams"] == [{"dial": "10.0.0.7:5000"}],
        )

        print("== server discovery skips servers not listening on :8899 ==")
        settings_disc = _settings(tmp, ingress_mode="path")

        def plan_multi_server(method, path):
            if method == "PATCH" and path == f"/id/preview-{KEY}-proj3":
                return FakeResponse(404)
            if method == "GET" and path == "/config/apps/http/servers":
                return FakeResponse(
                    200,
                    {
                        "srv-other": {"listen": [":9000"], "routes": []},
                        "srv-real": {"listen": [":8899"], "routes": []},
                    },
                )
            if method == "PUT" and path == "/config/apps/http/servers/srv-real/routes/0":
                return FakeResponse(200, {})
            raise AssertionError(f"unexpected call {method} {path}")

        fake4 = FakeHttpx(plan_multi_server)
        httpx.request = fake4
        url4 = ingress.register_preview_route(
            TENANT, "proj3", "10.0.0.8", 6000, settings=settings_disc
        )
        check(
            "picked the :8899 server, not the first key in the dict",
            url4 == f"http://localhost:8899/preview/{KEY}/proj3/",
            extra=url4,
        )

        print("== deregister: DELETE /id/<route_id>, tolerates 404 ==")

        def plan_delete_ok(method, path):
            check(
                "deletes the exact deterministic id",
                method == "DELETE" and path == f"/id/preview-{KEY}-proj1",
            )
            return FakeResponse(204)

        httpx.request = FakeHttpx(plan_delete_ok)
        ingress.deregister_preview_route(TENANT, "proj1", settings=settings)  # no raise

        def plan_delete_already_gone(method, path):
            return FakeResponse(404)

        httpx.request = FakeHttpx(plan_delete_already_gone)
        try:
            ingress.deregister_preview_route(TENANT, "proj1", settings=settings)
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
                TENANT, "proj-down", "10.0.0.9", 7000, settings=settings_fb
            )
            check(
                "returns the raw host:port URL as fallback",
                fb_url == "http://10.0.0.9:7000",
                extra=fb_url,
            )
        except Exception as e:  # noqa: BLE001
            check("register_preview_route never raises on transport failure", False, str(e))

        audit_path = os.path.join(settings_fb.data_dir, "_ingress", "audit.jsonl")
        check("fallback was recorded to the _ingress audit log", os.path.exists(audit_path))
        if os.path.exists(audit_path):
            with open(audit_path, encoding="utf-8") as f:
                events = [json.loads(line) for line in f if line.strip()]
            check(
                "audit event names the failed project",
                any(
                    e.get("project_id") == "proj-down"
                    and e.get("kind") == "preview_ingress_fallback"
                    for e in events
                ),
                extra=str(events),
            )

        print("== deregister also falls back to a logged no-op on failure (never raises) ==")
        httpx.request = FakeHttpx(plan_unreachable)
        try:
            ingress.deregister_preview_route(TENANT, "proj-down", settings=settings_fb)
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
            TENANT, "proj-500", "10.0.0.10", 8000, settings=settings_500
        )
        check(
            "500 from Caddy also degrades to the raw host:port URL",
            fb_url2 == "http://10.0.0.10:8000",
            extra=fb_url2,
        )

        print("== two tenants, SAME project id -> distinct routes, no overwrite ==")

        class FakeCaddy:
            """Tiny stateful Caddy: routes addressed by @id, PATCH replaces
            an existing id, PUT .../routes/0 inserts at the front."""

            def __init__(self):
                self.routes: list[dict] = []

            def __call__(self, method, path):
                if path == "/config/apps/http/servers":
                    return FakeResponse(200, {"srv0": {"listen": [":8899"]}})
                if path.startswith("/id/"):
                    rid = path[len("/id/") :]
                    idx = next((i for i, r in enumerate(self.routes) if r["@id"] == rid), None)
                    if method == "PATCH":
                        if idx is None:
                            return FakeResponse(404)
                        self.routes[idx] = self.pending
                        return FakeResponse(200)
                    if method == "DELETE":
                        if idx is not None:
                            del self.routes[idx]
                        return FakeResponse(200 if idx is not None else 404)
                if method == "PUT" and path.endswith("/routes/0"):
                    self.routes.insert(0, self.pending)
                    return FakeResponse(200)
                raise AssertionError(f"unexpected call {method} {path}")

        caddy = FakeCaddy()

        def caddy_request(method, url, json=None, timeout=None):
            caddy.pending = json
            path = "/" + url.split("://", 1)[1].split("/", 1)[1]
            result = caddy(method, path)
            return result

        httpx.request = caddy_request
        settings_mt = _settings(tmp, ingress_mode="path")
        url_a = ingress.register_preview_route(
            "tenant_a", "shop", "10.1.0.1", 3000, settings=settings_mt
        )
        url_b = ingress.register_preview_route(
            "tenant_b", "shop", "10.2.0.2", 4000, settings=settings_mt
        )
        check(
            "both registrations produced a route (no overwrite)",
            len(caddy.routes) == 2,
            extra=str([r["@id"] for r in caddy.routes]),
        )
        check("route ids differ", len({r["@id"] for r in caddy.routes}) == 2)
        check("URLs differ", url_a != url_b, extra=f"{url_a} {url_b}")
        paths = {r["match"][0]["path"][0] for r in caddy.routes}
        check("matched paths differ", len(paths) == 2, extra=str(paths))
        by_dial = {r["handle"][1]["upstreams"][0]["dial"]: r for r in caddy.routes}
        check(
            "tenant A's route still points at tenant A's upstream",
            "10.1.0.1:3000" in by_dial
            and f"/{ingress._tenant_key('tenant_a')}/"
            in by_dial["10.1.0.1:3000"]["match"][0]["path"][0],
        )
        check("tenant B's route points at tenant B's upstream", "10.2.0.2:4000" in by_dial)

        ingress.deregister_preview_route("tenant_b", "shop", settings=settings_mt)
        check(
            "deregistering B's route leaves A's route in place",
            [r["handle"][1]["upstreams"][0]["dial"] for r in caddy.routes] == ["10.1.0.1:3000"],
        )

        print("== subdomain mode is namespaced too ==")
        caddy_sub = FakeCaddy()

        def caddy_sub_request(method, url, json=None, timeout=None):
            caddy_sub.pending = json
            path = "/" + url.split("://", 1)[1].split("/", 1)[1]
            return caddy_sub(method, path)

        httpx.request = caddy_sub_request
        settings_sub2 = _settings(tmp, ingress_mode="subdomain")
        ingress.register_preview_route("tenant_a", "shop", "10.1.0.1", 3000, settings=settings_sub2)
        ingress.register_preview_route("tenant_b", "shop", "10.2.0.2", 4000, settings=settings_sub2)
        hosts = {r["match"][0]["host"][0] for r in caddy_sub.routes}
        check("two tenants get two different preview hosts", len(hosts) == 2, extra=str(hosts))

        print("== legacy (un-namespaced) route: recognised and removable ==")
        check(
            "old path-mode URL is recognised as legacy",
            ingress.is_legacy_preview_url(
                "http://localhost:8899/preview/shop/", "shop", settings=settings_mt
            ),
        )
        check(
            "old subdomain URL is recognised as legacy",
            ingress.is_legacy_preview_url(
                "https://shop.preview.staging.example.com", "shop", settings=settings_mt
            ),
        )
        check(
            "a namespaced URL is not legacy",
            not ingress.is_legacy_preview_url(url_a, "shop", settings=settings_mt),
        )
        check(
            "a raw host:port fallback URL is not legacy",
            not ingress.is_legacy_preview_url("http://10.0.0.5:3000", "shop", settings=settings_mt),
        )
        check(
            "no URL is not legacy",
            not ingress.is_legacy_preview_url(None, "shop", settings=settings_mt),
        )

        deleted: list[str] = []

        def plan_legacy_delete(method, path):
            deleted.append(f"{method} {path}")
            return FakeResponse(200)

        httpx.request = FakeHttpx(plan_legacy_delete)
        ingress.deregister_preview_route("tenant_a", "shop", settings=settings_mt, legacy=True)
        check(
            "legacy=True deletes the old preview-<project_id> id",
            deleted == ["DELETE /id/preview-shop"],
            extra=str(deleted),
        )

        print("== ids are validated before they reach a route ==")
        httpx.request = FakeHttpx(plan_unreachable)
        for bad_tenant, bad_project in (
            ("tenant_a", "shop\n"),
            ("tenant_a", "a/../b"),
            ("Tenant\n", "shop"),
        ):
            try:
                ingress.register_preview_route(
                    bad_tenant, bad_project, "10.0.0.1", 1, settings=settings_mt
                )
                check(f"rejects {bad_tenant!r}/{bad_project!r}", False)
            except TenantValidationError:
                check(f"rejects {bad_tenant!r}/{bad_project!r}", True)

        print("== fallback log line cannot be forged through the reason ==")
        records: list[str] = []

        class Capture(logging.Handler):
            def emit(self, record):
                records.append(record.getMessage())

        handler = Capture()
        ingress.log.addHandler(handler)
        try:
            ingress._record_fallback(settings_fb, "shop\r\n2026 WARNING forged", "boom\nforged")
        finally:
            ingress.log.removeHandler(handler)
        check(
            "exactly one record, no CR/LF in it",
            len(records) == 1 and "\n" not in records[0] and "\r" not in records[0],
            extra=repr(records),
        )

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        httpx.request = orig_request
        import shutil

        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
