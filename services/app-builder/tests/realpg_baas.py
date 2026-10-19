"""REAL-Postgres check for app/baas.py (needs Docker + the deploy-postgres:latest image; not part of the framework-free suite).
Throwaway container only. Prints PASS/FAIL lines; exits 1 on any failure."""
import os
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import psycopg  # noqa: E402

from app import baas, projects  # noqa: E402
from app.settings import Settings  # noqa: E402
from app.tenancy import TenantContext  # noqa: E402

PORT = 55433
FAILS = 0


def check(name, cond, extra=""):
    global FAILS
    print(("  PASS  " if cond else "  FAIL  ") + name + (f"  [{extra}]" if (extra and not cond) else ""))
    if not cond:
        FAILS += 1


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True)


sh("docker", "rm", "-f", "realpg")
r = sh("docker", "run", "-d", "--name", "realpg", "-p", f"{PORT}:5432", "-e", "POSTGRES_USER=vegadutaadmin",
       "-e", "POSTGRES_PASSWORD=adminpw", "-e", "POSTGRES_DB=vegaduta_ai", "deploy-postgres:latest")
assert r.returncode == 0, r.stderr
for _ in range(40):
    if sh("docker", "exec", "realpg", "pg_isready", "-U", "vegadutaadmin").returncode == 0:
        break
    time.sleep(2)
time.sleep(3)

ADMIN = f"postgresql://vegadutaadmin:adminpw@localhost:{PORT}/vegaduta_ai"
print("server:", psycopg.connect(ADMIN).execute("select version()").fetchone()[0][:40])

# ---- the planned prod bootstrap (non-superuser admin) -------------------------------------
BOOT = """
CREATE ROLE app_builder_admin LOGIN PASSWORD 'bootpw' NOSUPERUSER NOCREATEDB CREATEROLE NOINHERIT;
GRANT app_builder_admin TO vegadutaadmin;
CREATE DATABASE app_builder_baas OWNER app_builder_admin;
REVOKE ALL ON DATABASE app_builder_baas FROM PUBLIC;
GRANT CONNECT ON DATABASE app_builder_baas TO PUBLIC;
"""
with psycopg.connect(ADMIN, autocommit=True) as c:
    for stmt in [s.strip() for s in BOOT.split(";") if s.strip()]:
        c.execute(stmt)
with psycopg.connect(f"postgresql://vegadutaadmin:adminpw@localhost:{PORT}/app_builder_baas", autocommit=True) as c:
    c.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC")
    c.execute("GRANT ALL ON SCHEMA public TO app_builder_admin")

BAAS_ADMIN = f"postgresql://app_builder_admin:bootpw@localhost:{PORT}/app_builder_baas"
tmp = tempfile.mkdtemp(prefix="realpg-")
S = Settings(data_dir=tmp, admin_database_url=BAAS_ADMIN)
ctx_a, ctx_b = TenantContext(tenant_id="tenant_a"), TenantContext(tenant_id="tenant_b")
for ctx, pid in ((ctx_a, "alpha"), (ctx_b, "bravo"), (ctx_a, "charlie")):
    projects.upsert(tmp, ctx, projects.Project(project_id=pid, tenant_id=ctx.tenant_id, template_id="node-react",
                                               status="ready", code_intel_repo_id=pid))

print("== provision as the least-privilege admin ==")
ra = baas.provision_backend(ctx_a, "alpha", S)
check("alpha provisioned", ra.get("provisioned") is True, str(ra)[:300])
if not ra.get("provisioned"):
    print("ABORT: provisioning failed ->", ra)
    sh("docker", "rm", "-f", "realpg")
    sys.exit(1)
rb = baas.provision_backend(ctx_b, "bravo", S)
check("bravo provisioned", rb.get("provisioned") is True, str(rb)[:300])
check("admin creds not in project URL", "bootpw" not in ra["database_url"] and "app_builder_admin" not in ra["database_url"])

print("== apply schema + introspection through the real code path ==")
res = baas.apply_schema(ctx_a, "alpha", "create table notes (id int primary key, body text); insert into notes values (1,'secret-a'); alter table notes enable row level security;", S)
check("apply_schema applied", res.get("applied") is True, str(res)[:300])
check("tables listed with RLS flag", any(t["name"] == "notes" and t["rls"] for t in res.get("tables", [])), str(res)[:300])
baas.apply_schema(ctx_b, "bravo", "create table notes (id int primary key, body text); insert into notes values (1,'secret-b');", S)

print("== isolation: connect AS alpha's role ==")
A = psycopg.connect(ra["database_url"], autocommit=True)
check("alpha sees only its own schema in search_path", A.execute("show search_path").fetchone()[0].strip('"') == ra["schema"])
check("alpha owns its table (RLS on, owner bypasses)", A.execute("select body from notes").fetchone()[0] == "secret-a")
try:
    A.execute(f'select * from "{rb["schema"]}".notes')
    check("alpha CANNOT read bravo's schema", False, "read succeeded!")
except psycopg.errors.InsufficientPrivilege:
    check("alpha CANNOT read bravo's schema", True)
try:
    A.execute("create table public.pwn (x int)")
    check("alpha CANNOT create in public", False, "create succeeded!")
except psycopg.errors.InsufficientPrivilege:
    check("alpha CANNOT create in public", True)
try:
    A.execute("create role evil login")
    check("alpha CANNOT create roles", False, "create role succeeded!")
except psycopg.errors.InsufficientPrivilege:
    check("alpha CANNOT create roles", True)
try:
    A.execute("create database evil")
    check("alpha CANNOT create databases", False)
except psycopg.errors.InsufficientPrivilege:
    check("alpha CANNOT create databases", True)
try:
    psycopg.connect(f"postgresql://{ra['role']}:" + ra["database_url"].split(":")[2].split("@")[0] +
                    f"@localhost:{PORT}/vegaduta_ai").execute("select 1")
    plat_connect = True
except Exception:
    plat_connect = False
print(f"  INFO  alpha can CONNECT to the platform DB 'vegaduta_ai' (default PUBLIC connect): {plat_connect}")
if plat_connect:
    P = psycopg.connect(f"postgresql://{ra['role']}:" + ra["database_url"].split(":")[2].split("@")[0] +
                        f"@localhost:{PORT}/vegaduta_ai", autocommit=True)
    P.execute("create table if not exists x_probe_unused (a int)") if False else None
    # platform data must be unreadable: make a table as admin and try to read it as alpha
    with psycopg.connect(ADMIN, autocommit=True) as c:
        c.execute("create table platform_secret (v text)")
        c.execute("insert into platform_secret values ('crown-jewels')")
    try:
        P.execute("select * from public.platform_secret")
        check("alpha CANNOT read platform tables", False, "read succeeded!")
    except psycopg.errors.InsufficientPrivilege:
        check("alpha CANNOT read platform tables", True)

print("== idempotency + cleanup of a project ==")
again = baas.provision_backend(ctx_a, "alpha", S)
check("re-provision returns same URL (no rotation)", again.get("database_url") == ra["database_url"])
sh("docker", "rm", "-f", "realpg")
print("RESULT:", "ALL PASS" if FAILS == 0 else f"{FAILS} FAILED")
sys.exit(1 if FAILS else 0)
