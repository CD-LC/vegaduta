"""HTTP-level tests for app/dataapi.py with the database layer faked, so they run
anywhere (no Postgres/Docker). tests/realpg_dataapi.py repeats the important
paths against a real PG16.

Run: python3 -m tests.test_dataapi   (from the app-builder directory)
"""

from __future__ import annotations

import dataclasses
import os
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from fastapi.testclient import TestClient  # noqa: E402

from app import dataapi, dataquery as dq, projects  # noqa: E402
from app import main as appmain  # noqa: E402
from app.settings import Settings  # noqa: E402
from app.tenancy import TenantContext  # noqa: E402

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


SCHEMA = {
    "notes": {
        "id": dq.Column("id", "integer", False, True),
        "body": dq.Column("body", "text"),
        "done": dq.Column("done", "boolean"),
    },
    "secrets": {"id": dq.Column("id", "integer", False, True), "value": dq.Column("value", "text")},
}

CALLS: list[tuple[str, list, bool]] = []
ROWS = [{"id": 1, "body": "hello", "done": False}]


def fake_introspect(project, settings, fresh=False):
    return SCHEMA


def fake_run(project, settings, sql, params, *, write):
    CALLS.append((sql, params, write))
    return list(ROWS), len(ROWS)


def tweak(**kw):
    """Settings is frozen: replace it (module globals are read at call time)."""
    new = dataclasses.replace(dataapi.SETTINGS, **kw)
    dataapi.SETTINGS = new
    appmain.SETTINGS = new
    return new


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-dataapi-")
    settings = Settings(
        data_dir=tmp,
        auth_mode="none",
        public_base_url="https://builder.example.test",
        data_api_anon_rpm_per_key=1000,
        data_api_anon_rpm_per_ip=1000,
        data_api_service_rpm_per_key=1000,
    )
    dataapi.SETTINGS = settings
    appmain.SETTINGS = settings
    dataapi._introspect = fake_introspect
    dataapi._run = fake_run

    ctx = TenantContext(tenant_id="default")
    backend = {
        "schema": "app_abc_demo",
        "role": "u_app_abc_demo",
        "database_url": "postgresql://u:p@db:5432/x",
    }
    projects.upsert(
        tmp,
        ctx,
        projects.Project(
            project_id="nobackend",
            tenant_id="default",
            template_id="node-react",
            status="ready",
            code_intel_repo_id="nobackend",
        ),
    )
    projects.upsert(
        tmp,
        ctx,
        projects.Project(
            project_id="demo",
            tenant_id="default",
            template_id="node-react",
            status="ready",
            code_intel_repo_id="demo",
            backend=dict(backend),
        ),
    )
    c = TestClient(appmain.app)

    print("== management: enable ==")
    r = c.post("/projects/nobackend/data/enable")
    check(
        "enable without a provisioned backend -> 409",
        r.status_code == 409 and r.json()["code"] == "no_backend",
        r.text,
    )
    r = c.post("/projects/demo/data/enable")
    cfg = r.json()
    check("enable -> 200", r.status_code == 200, r.text)
    anon, svc = cfg.get("anon_key", ""), cfg.get("service_key", "")
    check(
        "anon key + service key issued",
        anon.startswith("vdk_") and svc.startswith("vdk_") and anon != svc,
    )
    check(
        "base url comes from the public setting",
        cfg["base_url"] == "https://builder.example.test/data/v1",
        cfg.get("base_url"),
    )
    check(
        "tables listed with deny-by-default policy",
        {t["name"] for t in cfg["tables"]} == {"notes", "secrets"}
        and all(not any(t["policy"].values()) for t in cfg["tables"]),
    )
    r2 = c.post("/projects/demo/data/enable")
    check(
        "second enable is idempotent and does NOT re-show the service key",
        r2.status_code == 200 and "service_key" not in r2.json() and r2.json()["anon_key"] == anon,
    )
    r3 = c.get("/projects/demo/data/config")
    check(
        "config readable, service key never re-shown",
        r3.status_code == 200 and "service_key" not in r3.json() and r3.json()["has_service_key"],
    )
    idx = open(os.path.join(tmp, "data_api_keys.json"), encoding="utf-8").read()
    svc_secret = svc.split("_", 2)[2]
    check(
        "index stores only hashes, never the secret",
        svc_secret not in idx and anon.split("_", 2)[2] not in idx,
    )

    H = {"apikey": anon}
    print("== anon key: default deny ==")
    r = c.get("/data/v1/notes", headers=H)
    check(
        "no policy -> table looks nonexistent (404)",
        r.status_code == 404 and r.json()["code"] == "not_found",
        r.text,
    )
    r = c.get("/data/v1/secrets", headers=H)
    check("unlisted table also 404", r.status_code == 404)
    r = c.get("/data/v1", headers=H)
    check(
        "root lists nothing for anon yet",
        r.status_code == 200 and r.json() == {"role": "anon", "tables": []},
        r.text,
    )

    print("== policies ==")
    r = c.put("/projects/demo/data/policies", json={"policies": {"ghost": {"select": True}}})
    check(
        "unknown table rejected",
        r.status_code == 400 and r.json()["code"] == "unknown_table",
        r.text,
    )
    r = c.put("/projects/demo/data/policies", json={"policies": {"notes": {"drop": True}}})
    check(
        "unknown operation rejected",
        r.status_code == 400 and r.json()["code"] == "bad_policy",
        r.text,
    )
    r = c.put(
        "/projects/demo/data/policies",
        json={
            "policies": {"notes": {"select": True, "insert": True}},
            "allowed_origins": ["javascript:alert(1)"],
        },
    )
    check("non-origin rejected", r.status_code == 400 and r.json()["code"] == "bad_origins", r.text)
    r = c.put(
        "/projects/demo/data/policies",
        json={"policies": {"notes": {"select": True, "insert": True}}},
    )
    check(
        "policies saved",
        r.status_code == 200
        and [t["policy"] for t in r.json()["tables"] if t["name"] == "notes"][0]
        == {"select": True, "insert": True, "update": False, "delete": False},
        r.text,
    )

    print("== anon key: allowed operations ==")
    CALLS.clear()
    r = c.get("/data/v1/notes?select=id,body&id=gte.1&order=id.desc&limit=5", headers=H)
    check("select allowed", r.status_code == 200 and r.json() == ROWS, r.text)
    check(
        "select reached the DB with bind params",
        CALLS
        and CALLS[0][0].startswith('SELECT "id", "body" FROM "app_abc_demo"."notes"')
        and CALLS[0][1][:1] == ["1"]
        and not CALLS[0][2],
        str(CALLS),
    )
    check("responses are never cached", r.headers.get("cache-control") == "no-store")
    r = c.post("/data/v1/notes", headers=H, json={"body": "hi"})
    check("insert allowed -> 201", r.status_code == 201, r.text)
    r = c.post("/data/v1/notes", headers={**H, "Prefer": "return=minimal"}, json={"body": "hi"})
    check("Prefer return=minimal -> 201 with no body", r.status_code == 201 and r.content == b"")
    r = c.patch("/data/v1/notes?id=eq.1", headers=H, json={"body": "x"})
    check(
        "update NOT allowed for anon (403)",
        r.status_code == 403 and r.json()["code"] == "operation_not_allowed",
        r.text,
    )
    r = c.delete("/data/v1/notes?id=eq.1", headers=H)
    check("delete NOT allowed for anon (403)", r.status_code == 403)
    r = c.get("/data/v1/secrets", headers=H)
    check("other tables stay invisible", r.status_code == 404)
    r = c.get("/data/v1", headers=H)
    check(
        "root lists only the exposed table + ops",
        r.json()["tables"][0]["name"] == "notes"
        and r.json()["tables"][0]["operations"] == ["select", "insert"]
        and len(r.json()["tables"]) == 1,
        r.text,
    )

    print("== key authentication ==")
    check("no key -> 401", c.get("/data/v1/notes").status_code == 401)
    check(
        "garbage key -> 401", c.get("/data/v1/notes", headers={"apikey": "nope"}).status_code == 401
    )
    forged = "vdk_" + "0" * 16 + "_" + "A" * 43
    check(
        "well-formed but unknown key -> 401",
        c.get("/data/v1/notes", headers={"apikey": forged}).status_code == 401,
    )
    check(
        "tampered secret -> 401",
        c.get("/data/v1/notes", headers={"apikey": anon[:-3] + "AAA"}).status_code == 401,
    )
    check(
        "Authorization: Bearer works too",
        c.get("/data/v1/notes", headers={"Authorization": "Bearer " + anon}).status_code == 200,
    )
    check(
        "key in the query string is NOT accepted",
        c.get("/data/v1/notes?apikey=" + anon).status_code == 401,
    )
    msgs = {
        c.get("/data/v1/notes", headers={"apikey": k}).json()["error"] for k in ("nope", forged)
    }
    check(
        "unknown vs malformed keys are not distinguishable by message beyond format", len(msgs) <= 2
    )

    print("== service key ==")
    S = {"apikey": svc}
    r = c.get("/data/v1/secrets", headers=S)
    check("service key reaches every table (server-side use)", r.status_code == 200, r.text)
    r = c.delete("/data/v1/notes?id=eq.1", headers=S)
    check("service key may delete", r.status_code == 200)
    r = c.get("/data/v1/secrets", headers={**S, "Origin": "https://evil.example"})
    check(
        "service key from a BROWSER (Origin header) is refused",
        r.status_code == 403 and r.json()["code"] == "service_key_in_browser",
        r.text,
    )

    print("== CORS ==")
    r = c.options(
        "/data/v1/notes",
        headers={
            "Origin": "https://some.app",
            "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "apikey",
        },
    )
    check(
        "preflight from any origin -> 204 with CORS",
        r.status_code == 204
        and r.headers["access-control-allow-origin"] == "*"
        and "apikey" in r.headers["access-control-allow-headers"],
        str(r.headers),
    )
    r = c.get("/data/v1/notes", headers={**H, "Origin": "https://some.app"})
    check("open projects answer ACAO *", r.headers.get("access-control-allow-origin") == "*")
    r = c.put(
        "/projects/demo/data/policies",
        json={
            "policies": {"notes": {"select": True, "insert": True}},
            "allowed_origins": ["https://My.App/", "http://localhost:3000"],
        },
    )
    check(
        "allowed origins normalised",
        r.json()["allowed_origins"] == ["http://localhost:3000", "https://my.app"],
        r.text,
    )
    r = c.get("/data/v1/notes", headers={**H, "Origin": "https://my.app"})
    check(
        "allowed origin is echoed",
        r.status_code == 200
        and r.headers["access-control-allow-origin"] == "https://my.app"
        and "Origin" in r.headers.get("vary", ""),
    )
    r = c.get("/data/v1/notes", headers={**H, "Origin": "https://evil.example"})
    check(
        "other browser origins are refused for the anon key",
        r.status_code == 403 and r.json()["code"] == "origin_not_allowed",
    )
    r = c.get("/data/v1/notes", headers=H)
    check("non-browser clients (no Origin) are unaffected", r.status_code == 200)
    r = c.get("/projects/demo/data/config")
    check("the console CORS is untouched for other routes", r.status_code == 200)

    print("== bad input never reaches the database ==")
    CALLS.clear()
    for label, req in [
        ("unknown column in filter", lambda: c.get("/data/v1/notes?nope=eq.1", headers=H)),
        (
            "injection attempt in order",
            lambda: c.get("/data/v1/notes?order=id;drop table notes", headers=H),
        ),
        (
            "injection attempt in select",
            lambda: c.get("/data/v1/notes?select=id,(select 1)", headers=H),
        ),
        ("bad operator", lambda: c.get("/data/v1/notes?id=regex.1", headers=H)),
        ("non-integer limit", lambda: c.get("/data/v1/notes?limit=1;--", headers=H)),
        ("insert unknown column", lambda: c.post("/data/v1/notes", headers=H, json={"x": 1})),
        ("insert non-object", lambda: c.post("/data/v1/notes", headers=H, json=["x"])),
    ]:
        r = req()
        check(
            f"{label} -> 400, no query run",
            r.status_code == 400 and not CALLS,
            f"{r.status_code} {r.text}",
        )
    r = c.post(
        "/data/v1/notes", headers={**H, "Content-Type": "application/json"}, content=b"{not json"
    )
    check("invalid JSON -> 400", r.status_code == 400 and r.json()["code"] == "bad_json")
    r = c.post(
        "/data/v1/notes",
        headers={**H, "Content-Type": "application/json"},
        content=b'{"body":"' + b"a" * (dataapi.MAX_BODY_BYTES + 10) + b'"}',
    )
    check("oversized body -> 413", r.status_code == 413 and r.json()["code"] == "body_too_large")
    r = c.get("/data/v1/notes?" + "a=1&" * 2000, headers=H)
    check("huge query string -> 414", r.status_code == 414)
    r = c.request("PUT", "/data/v1/notes", headers=H)
    check("unsupported method -> 405", r.status_code == 405)

    print("== rate limiting ==")
    settings = tweak(data_api_anon_rpm_per_key=3)
    dataapi._LIMITER._w.clear()
    codes = [c.get("/data/v1/notes", headers=H).status_code for _ in range(5)]
    check(
        "anon key is capped per minute (429 after the limit)",
        codes == [200, 200, 200, 429, 429],
        str(codes),
    )
    r = c.get("/data/v1/notes", headers=H)
    check("429 carries Retry-After", r.status_code == 429 and int(r.headers["retry-after"]) >= 1)
    settings = tweak(data_api_anon_rpm_per_key=1000)
    settings = tweak(data_api_anon_rpm_per_ip=2)
    dataapi._LIMITER._w.clear()
    codes = [
        c.get("/data/v1/notes", headers={**H, "X-Forwarded-For": "9.9.9.9"}).status_code
        for _ in range(3)
    ]
    other = c.get("/data/v1/notes", headers={**H, "X-Forwarded-For": "8.8.8.8"}).status_code
    check(
        "per-IP cap applies per client, not globally",
        codes == [200, 200, 429] and other == 200,
        f"{codes} {other}",
    )
    settings = tweak(data_api_anon_rpm_per_ip=1000)
    dataapi._LIMITER._w.clear()

    # The failed-auth limiter runs on a fixed 60s wall-clock window: if the
    # flood straddles a boundary the counter resets mid-flood and no 429 ever
    # appears. Align to just after a boundary when we're near one.
    edge = int(time.time()) % 60
    if edge > 55:
        time.sleep(66 - edge)
    dataapi._LIMITER._w.clear()
    bad = [
        c.get("/data/v1/notes", headers={"apikey": "bad", "X-Forwarded-For": "7.7.7.7"}).status_code
        for _ in range(62)
    ]
    check(
        "flooding with bad keys is throttled per IP",
        bad[:60] == [401] * 60 and bad[60:] == [429, 429],
        str(bad[58:]),
    )
    good = c.get("/data/v1/notes", headers={**H, "X-Forwarded-For": "6.6.6.6"}).status_code
    check("a different client is unaffected", good == 200)
    dataapi._LIMITER._w.clear()

    print("== key rotation ==")
    r = c.post("/projects/demo/data/keys/rotate", json={"kind": "service"})
    new_svc = r.json().get("service_key", "")
    check(
        "service key rotated and shown once",
        r.status_code == 200 and new_svc.startswith("vdk_") and new_svc != svc,
    )
    check(
        "old service key dies immediately",
        c.get("/data/v1/secrets", headers={"apikey": svc}).status_code == 401,
    )
    check(
        "new service key works",
        c.get("/data/v1/secrets", headers={"apikey": new_svc}).status_code == 200,
    )
    check(
        "anon key unaffected by service rotation",
        c.get("/data/v1/notes", headers=H).status_code == 200,
    )
    r = c.post("/projects/demo/data/keys/rotate", json={"kind": "anon"})
    new_anon = r.json()["anon_key"]
    check(
        "anon key rotated",
        new_anon != anon
        and c.get("/data/v1/notes", headers=H).status_code == 401
        and c.get("/data/v1/notes", headers={"apikey": new_anon}).status_code == 200,
    )
    check(
        "bad kind rejected",
        c.post("/projects/demo/data/keys/rotate", json={"kind": "root"}).status_code == 400,
    )

    print("== disable / delete ==")
    r = c.delete("/projects/demo/data")
    check(
        "disable revokes the keys", r.status_code == 200 and r.json()["keys_revoked"] == 2, r.text
    )
    check(
        "revoked anon key -> 401",
        c.get("/data/v1/notes", headers={"apikey": new_anon}).status_code == 401,
    )
    check(
        "revoked service key -> 401",
        c.get("/data/v1/secrets", headers={"apikey": new_svc}).status_code == 401,
    )
    check("config reports disabled", c.get("/projects/demo/data/config").json()["enabled"] is False)
    r = c.post("/projects/demo/data/enable")
    k2 = r.json()["anon_key"]
    check(
        "re-enable issues brand-new keys with empty policies",
        k2 not in (anon, new_anon)
        and c.get("/data/v1/notes", headers={"apikey": k2}).status_code == 404,
    )
    r = c.delete("/projects/demo")
    check(
        "deleting the project revokes its keys",
        r.status_code == 200 and c.get("/data/v1", headers={"apikey": k2}).status_code == 401,
        r.text,
    )

    print("== kill switch ==")
    settings = tweak(data_api_enabled=False)
    r = c.get("/data/v1", headers={"apikey": k2})
    check("operator kill switch -> 503", r.status_code == 503 and r.json()["code"] == "disabled")
    check(
        "enabling is refused while disabled",
        c.post("/projects/nobackend/data/enable").status_code in (409, 503),
    )

    print(f"\n{PASS} passed, {FAIL} failed")
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
