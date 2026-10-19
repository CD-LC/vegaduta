"""REAL-Postgres end-to-end check for the data API (needs Docker + the
deploy-postgres:latest image; not part of the framework-free suite).

Provisions two projects with baas.py using the same least-privilege admin as
prod, turns the Data API on, then drives it over HTTP (TestClient) against the
real database: type round-trips, constraint errors, injection attempts verified
against the live tables, row-cap rollback, and cross-project isolation.

Run: python -m tests.realpg_dataapi   (from the app-builder directory)
"""

from __future__ import annotations

import dataclasses
import os
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import psycopg  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import baas, dataapi, dataapi_db, projects  # noqa: E402
from app import main as appmain  # noqa: E402
from app.settings import Settings  # noqa: E402
from app.tenancy import TenantContext  # noqa: E402

PORT = 55434
FAILS = 0


def check(name, cond, extra=""):
    global FAILS
    print(
        ("  PASS  " if cond else "  FAIL  ")
        + name
        + (f"  [{extra}]" if (extra and not cond) else "")
    )
    if not cond:
        FAILS += 1


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True)


sh("docker", "rm", "-f", "realpg_dataapi")
r = sh(
    "docker",
    "run",
    "-d",
    "--name",
    "realpg_dataapi",
    "-p",
    f"{PORT}:5432",
    "-e",
    "POSTGRES_USER=vegadutaadmin",
    "-e",
    "POSTGRES_PASSWORD=adminpw",
    "-e",
    "POSTGRES_DB=vegaduta_ai",
    "deploy-postgres:latest",
)
assert r.returncode == 0, r.stderr
for _ in range(40):
    if sh("docker", "exec", "realpg_dataapi", "pg_isready", "-U", "vegadutaadmin").returncode == 0:
        break
    time.sleep(2)
time.sleep(3)

ADMIN = f"postgresql://vegadutaadmin:adminpw@localhost:{PORT}/vegaduta_ai"
with psycopg.connect(ADMIN, autocommit=True) as c:
    for stmt in [
        "CREATE ROLE app_builder_admin LOGIN PASSWORD 'bootpw' "
        "NOSUPERUSER NOCREATEDB CREATEROLE NOINHERIT",
        "GRANT app_builder_admin TO vegadutaadmin",
        "CREATE DATABASE app_builder_baas OWNER app_builder_admin",
        "REVOKE ALL ON DATABASE app_builder_baas FROM PUBLIC",
        "GRANT CONNECT ON DATABASE app_builder_baas TO PUBLIC",
    ]:
        c.execute(stmt)
with psycopg.connect(
    f"postgresql://vegadutaadmin:adminpw@localhost:{PORT}/app_builder_baas", autocommit=True
) as c:
    c.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC")
    c.execute("GRANT ALL ON SCHEMA public TO app_builder_admin")

tmp = tempfile.mkdtemp(prefix="realpg-dataapi-")
S = Settings(
    data_dir=tmp,
    auth_mode="none",
    public_base_url="https://builder.example.test",
    admin_database_url=f"postgresql://app_builder_admin:bootpw@localhost:{PORT}/app_builder_baas",
    data_api_anon_rpm_per_key=100000,
    data_api_anon_rpm_per_ip=100000,
    data_api_service_rpm_per_key=100000,
)
dataapi.SETTINGS = S
appmain.SETTINGS = S
ctx = TenantContext(tenant_id="default")
for pid in ("alpha", "bravo"):
    projects.upsert(
        tmp,
        ctx,
        projects.Project(
            project_id=pid,
            tenant_id="default",
            template_id="node-react",
            status="ready",
            code_intel_repo_id=pid,
        ),
    )
    res = baas.provision_backend(ctx, pid, S)
    assert res.get("provisioned"), res

DDL = """
create table notes (
  id bigint generated always as identity primary key,
  body text not null,
  done boolean not null default false,
  score numeric(10,2),
  meta jsonb,
  tags text[],
  ref uuid default gen_random_uuid(),
  created timestamptz default now(),
  email text unique
);
create table secrets (id int primary key, value text);
insert into secrets values (1, 'alpha-top-secret');
"""
assert baas.apply_schema(ctx, "alpha", DDL, S)["applied"]
assert baas.apply_schema(
    ctx,
    "bravo",
    "create table notes (id int primary key, body text); "
    "insert into notes values (1,'BRAVO-ROW'); create table bravo_only (id int);",
    S,
)["applied"]

c = TestClient(appmain.app)
cfgA = c.post("/projects/alpha/data/enable").json()
cfgB = c.post("/projects/bravo/data/enable").json()
anonA, svcA = cfgA["anon_key"], cfgA["service_key"]
anonB, svcB = cfgB["anon_key"], cfgB["service_key"]
HA, SA = {"apikey": anonA}, {"apikey": svcA}
HB, SB = {"apikey": anonB}, {"apikey": svcB}
check(
    "alpha introspection sees its real tables + column types",
    {t["name"] for t in cfgA["tables"]} == {"notes", "secrets"},
)
r = c.put(
    "/projects/alpha/data/policies", json={"policies": {"notes": {"select": True, "insert": True}}}
)
check("alpha policies saved", r.status_code == 200, r.text)
c.put("/projects/bravo/data/policies", json={"policies": {"notes": {"select": True}}})

print("== insert + type round-trips (real PG) ==")
r = c.post(
    "/data/v1/notes",
    headers=HA,
    json={
        "body": "first",
        "score": 12.5,
        "meta": {"a": [1, 2], "b": None},
        "tags": ["x", "y"],
        "email": "a@x.io",
        "done": True,
    },
)
check(
    "anon insert -> 201 with the row",
    r.status_code == 201 and r.json()[0]["body"] == "first",
    r.text,
)
row = r.json()[0]
check("identity id generated", isinstance(row["id"], int))
check("numeric comes back as a JSON number", row["score"] == 12.5)
check("jsonb round-trips as an object", row["meta"] == {"a": [1, 2], "b": None})
check("text[] round-trips as a list", row["tags"] == ["x", "y"])
check(
    "uuid + timestamptz serialise as strings",
    isinstance(row["ref"], str) and len(row["ref"]) == 36 and "T" in row["created"],
    str(row),
)
r = c.post("/data/v1/notes", headers=HA, json=[{"body": f"bulk{i}", "score": i} for i in range(5)])
check("bulk insert of 5 rows", r.status_code == 201 and len(r.json()) == 5, r.text)

print("== select / filters / ordering ==")
r = c.get("/data/v1/notes?select=id,body&order=id.asc&limit=2", headers=HA)
check(
    "select columns + limit",
    r.status_code == 200 and len(r.json()) == 2 and set(r.json()[0]) == {"id", "body"},
    r.text,
)
r = c.get("/data/v1/notes?body=eq.first", headers=HA)
check("eq filter", len(r.json()) == 1 and r.json()[0]["body"] == "first")
r = c.get("/data/v1/notes?score=gte.3&score=lt.5&order=score.asc", headers=HA)
check(
    "range filter on numeric (two params on one column)",
    [x["score"] for x in r.json()] == [3, 4],
    r.text,
)
r = c.get("/data/v1/notes?body=in.(bulk1,bulk3,nope)&order=body.asc", headers=HA)
check("in filter", [x["body"] for x in r.json()] == ["bulk1", "bulk3"], r.text)
r = c.get("/data/v1/notes?body=ilike.*BULK*&limit=100", headers=HA)
check("ilike with * wildcard", len(r.json()) == 5)
r = c.get("/data/v1/notes?meta=is.null", headers=HA)
check("is.null", len(r.json()) == 5)
r = c.get("/data/v1/notes?done=is.true", headers=HA)
check("is.true on boolean", len(r.json()) == 1)
r = c.get("/data/v1/notes?body=not.like.bulk*", headers=HA)
check("not.like", [x["body"] for x in r.json()] == ["first"])
r = c.get("/data/v1/notes?order=id.desc&limit=1&offset=1", headers=HA)
check("order + offset", r.status_code == 200 and len(r.json()) == 1)
r = c.get("/data/v1/notes?id=eq.abc", headers=HA)
check(
    "type error becomes a clean 400 (not a 500)",
    r.status_code == 400 and r.json()["code"] == "invalid_value",
    r.text,
)

print("== constraint errors ==")
r = c.post("/data/v1/notes", headers=HA, json={"body": "dup", "email": "a@x.io"})
check("unique violation -> 409", r.status_code == 409 and r.json()["code"] == "conflict", r.text)
r = c.post("/data/v1/notes", headers=HA, json={"done": True})
check(
    "not-null violation -> 409",
    r.status_code == 409 and r.json()["code"] == "constraint_violation",
    r.text,
)
r = c.post("/data/v1/notes", headers=HA, json={"body": "x", "score": "not-a-number"})
check("bad numeric -> 400", r.status_code == 400, r.text)

print("== policy enforcement against the real table ==")
check(
    "anon cannot update",
    c.patch("/data/v1/notes?id=gt.0", headers=HA, json={"body": "pwned"}).status_code == 403,
)
check("anon cannot delete", c.delete("/data/v1/notes?id=gt.0", headers=HA).status_code == 403)
check("anon cannot even see 'secrets'", c.get("/data/v1/secrets", headers=HA).status_code == 404)
r = c.get("/data/v1/secrets", headers=SA)
check(
    "service key can read 'secrets'",
    r.status_code == 200 and r.json()[0]["value"] == "alpha-top-secret",
)
r = c.get("/data/v1/secrets", headers={**SA, "Origin": "https://evil.example"})
check("service key from a browser is refused", r.status_code == 403)

print("== update / delete via the service key ==")
r = c.patch(
    "/data/v1/notes?body=eq.bulk0", headers=SA, json={"body": "bulk0-edited", "meta": {"k": 1}}
)
check(
    "update returns the changed row",
    r.status_code == 200
    and r.json()[0]["body"] == "bulk0-edited"
    and r.json()[0]["meta"] == {"k": 1},
    r.text,
)
check(
    "update with no filter refused",
    c.patch("/data/v1/notes", headers=SA, json={"body": "x"}).status_code == 400,
)
check("delete with no filter refused", c.delete("/data/v1/notes", headers=SA).status_code == 400)
r = c.delete("/data/v1/notes?body=eq.bulk4", headers=SA)
check("delete returns the removed row", r.status_code == 200 and len(r.json()) == 1)

print("== injection attempts, verified against the live tables ==")
# Verify as alpha's OWN role (the table owner): that is the only vantage that can count its rows.
direct = psycopg.connect(projects.get(tmp, ctx, "alpha").backend["database_url"], autocommit=True)
schemaA = projects.get(tmp, ctx, "alpha").backend["schema"]


def count(table="notes"):
    return direct.execute(f'select count(*) from "{schemaA}"."{table}"').fetchone()[0]


before = count()
evil = ["1' OR '1'='1", "'; DROP TABLE notes; --", "\\'; delete from notes; --", 'x" or "1"="1']
for e in evil:
    r = c.get("/data/v1/notes", params={"body": "eq." + e}, headers=HA)
    check(
        f"value {e[:22]!r}: handled as data (200, no rows)",
        r.status_code == 200 and r.json() == [],
        r.text,
    )
    r = c.post("/data/v1/notes", headers=HA, json={"body": e})
    check(
        f"insert of {e[:22]!r}: stored verbatim",
        r.status_code == 201 and r.json()[0]["body"] == e,
        r.text,
    )
for hostile in [
    "id;drop table notes",
    "id desc, (select pg_sleep(5))",
    'body"; drop table notes;--',
]:
    r = c.get("/data/v1/notes", params={"order": hostile}, headers=HA)
    check(f"order={hostile[:26]!r} rejected", r.status_code == 400, r.text)
    r = c.get("/data/v1/notes", params={"select": hostile}, headers=HA)
    check(f"select={hostile[:26]!r} rejected", r.status_code == 400, r.text)
r = c.get("/data/v1/notes", params={'body"; drop table notes;--': "eq.1"}, headers=HA)
check("hostile column name in a filter rejected", r.status_code == 400)
r = c.post("/data/v1/notes", headers=HA, json={'body"); drop table notes;--': "x"})
check("hostile column name in an insert body rejected", r.status_code == 400)
check(
    "table and data survived every attempt",
    count() == before + len(evil) and count("secrets") == 1,
    f"{count()} vs {before + len(evil)}",
)

print("== row-cap rollback ==")
batch = [{"body": f"cap{i}"} for i in range(100)]
for _ in range(12):
    assert c.post("/data/v1/notes", headers=SA, json=batch).status_code == 201
n_before = count()
r = c.delete("/data/v1/notes?id=gt.0", headers=SA)
check(
    "deleting >1000 rows is refused",
    r.status_code == 400 and r.json()["code"] == "too_many_rows",
    r.text,
)
check("...and rolled back (nothing deleted)", count() == n_before)
r = c.get("/data/v1/notes?limit=100000", headers=HA)
check("select limit is capped at 1000", r.status_code == 200 and len(r.json()) == 1000)

print("== cross-project isolation ==")
r = c.get("/data/v1/notes?select=body", headers=HB)
check(
    "bravo's key reads BRAVO's notes only",
    r.status_code == 200 and [x["body"] for x in r.json()] == ["BRAVO-ROW"],
    r.text,
)
r = c.get("/data/v1/bravo_only", headers=SA)
check("alpha's service key cannot reach bravo's table (404)", r.status_code == 404)
r = c.get("/data/v1/bravo_only", headers=SB)
check("bravo's own service key can", r.status_code == 200)
check(
    "alpha's anon key is useless against bravo's data (policy + schema)",
    c.get("/data/v1/notes?select=body", headers=HA).json()[0]["body"] != "BRAVO-ROW",
)
r = c.get("/data/v1/notes?body=eq.BRAVO-ROW", headers=HA)
check("alpha cannot find bravo's row by filtering", r.status_code == 200 and r.json() == [])
sch_b = projects.get(tmp, ctx, "bravo").backend["schema"]
try:
    psycopg.connect(projects.get(tmp, ctx, "alpha").backend["database_url"]).execute(
        f'select * from "{sch_b}".notes'
    )
    check("alpha's DB role cannot read bravo's schema (defence in depth)", False)
except psycopg.errors.InsufficientPrivilege:
    check("alpha's DB role cannot read bravo's schema (defence in depth)", True)

print("== bounded database use ==")
S2 = dataclasses.replace(S, data_api_max_conns=1)
dataapi.SETTINGS = S2
dataapi_db._DB_SEM = None
check(
    "works with a pool of 1 connection",
    c.get("/data/v1/notes?limit=1", headers=HA).status_code == 200,
)
dataapi.SETTINGS = S
dataapi_db._DB_SEM = None
check(
    "connections are released after requests (no leak)",
    all(c.get("/data/v1/notes?limit=1", headers=HA).status_code == 200 for _ in range(30)),
)
left = (
    psycopg.connect(ADMIN, autocommit=True)
    .execute(
        "select count(*) from pg_stat_activity "
        "where usename like 'u_app_%' and pid <> pg_backend_pid()"
    )
    .fetchone()[0]
    - 1
)  # minus our own `direct` connection
check("no connections left open by the proxy", left <= 1, str(left))

sh("docker", "rm", "-f", "realpg_dataapi")
print("RESULT:", "ALL PASS" if FAILS == 0 else f"{FAILS} FAILED")
sys.exit(1 if FAILS else 0)
