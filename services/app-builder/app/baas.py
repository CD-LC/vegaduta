"""Backend-as-a-service: per-project Postgres schema provisioning.

Connects to the platform's EXISTING Postgres using an operator-supplied
admin connection string (`APP_BUILDER_ADMIN_DATABASE_URL` — this module
never invents platform credentials) and creates one schema per project,
`app_<tenant hash>_<project_id>`, returning a DATABASE_URL scoped to it via
`options=-csearch_path=<schema>` (a schema-qualified connection string
without embedding the schema in the path component, which Postgres
connection strings don't support natively).

ISOLATION MODEL: each project gets its OWN Postgres LOGIN role
(`u_<schema>`: NOSUPERUSER/NOCREATEDB/NOCREATEROLE, connection-limited) that
owns exactly one schema. The role's search_path is that schema and it holds
no grants anywhere else, so a bug or hostile generated app can read, write
and DDL its own schema (including enabling row-level security on its own
tables) but cannot touch another project's schema or the platform's own
tables. The admin URL is used only to provision; it is never handed to a
generated app or persisted on the project. Schema names carry a hash of the
tenant id so two tenants' identically-named projects never share a schema.
Recommended: point APP_BUILDER_ADMIN_DATABASE_URL at a dedicated database (or
cluster) rather than the platform's own, since role catalog metadata is
visible to every role in a database.

Optional feature: a project can scaffold and preview with no database at
all. If `APP_BUILDER_ADMIN_DATABASE_URL` isn't configured,
provision_backend() returns a clear `{"provisioned": False, "reason": ...}`
result instead of raising — this must never crash the service.
"""

from __future__ import annotations

import hashlib
import re
import secrets
import time
from urllib.parse import quote, urlparse, urlunparse

from . import audit, projects
from .settings import SETTINGS, Settings
from .tenancy import TenantContext, tenant_root, validate_project_id

# Postgres unquoted identifiers: only [a-z0-9_], and dashes (valid in
# app-builder's own project_id pattern, see tenancy.py's _PROJECT_ID_RX,
# because project_id doubles as code-intel's repo_id/git-friendly name)
# are NOT safe to interpolate unquoted here. Rather than silently
# transforming dashes (risking a schema-name collision between two
# differently-dashed project ids), this is a strictly NARROWER check on
# top of validate_project_id — dash-containing project ids simply cannot
# provision a backend in this pass.
_SCHEMA_SAFE_RX = re.compile(r"^[a-z][a-z0-9_]{0,58}$")


class BackendProvisionError(RuntimeError):
    pass


def _schema_name(ctx: TenantContext, project_id: str) -> str:
    validate_project_id(project_id)
    tenant_tag = hashlib.sha1(ctx.tenant_id.encode()).hexdigest()[:8]
    candidate = f"app_{tenant_tag}_{project_id}".lower()
    # "u_" role prefix must still fit Postgres' 63-byte identifier limit.
    if not _SCHEMA_SAFE_RX.match(candidate) or len(candidate) > 58:
        raise BackendProvisionError(
            f"project_id {project_id!r} is not safe to use as a Postgres "
            "schema name unquoted (only a-z0-9_ allowed, must start with a "
            "letter, at most ~45 characters) — dashes in particular are "
            "rejected here even though they're valid in project ids "
            "elsewhere")
    return candidate


def _role_name(schema: str) -> str:
    return f"u_{schema}"


def _project_url(admin_url: str, role: str, password: str, schema: str) -> str:
    """Same host/port/database as admin_url but authenticating as the
    project's own role, with the session search_path pinned to its schema."""
    parsed = urlparse(admin_url)
    host = parsed.hostname or ""
    if ":" in host:  # IPv6 literal
        host = f"[{host}]"
    netloc = f"{quote(role, safe='')}:{quote(password, safe='')}@{host}"
    if parsed.port:
        netloc += f":{parsed.port}"
    sep = "&" if parsed.query else ""
    new_query = f"{parsed.query}{sep}options=-csearch_path%3D{schema}"
    return urlunparse(parsed._replace(netloc=netloc, query=new_query))


_MAX_SCHEMA_SQL_BYTES = 200 * 1024
_CONNECTION_LIMIT = 20


def _driver():
    try:
        import psycopg  # type: ignore
    except ImportError:
        return None
    return psycopg


def _unavailable(reason: str) -> dict:
    return {"provisioned": False, "reason": reason}


def provision_backend(ctx: TenantContext, project_id: str,
                      settings: Settings = SETTINGS) -> dict:
    """Idempotent. First call creates the project's role + schema and stores
    the role-scoped database_url on the project; later calls return that
    record untouched (rotating the password would break a running app).
    A record from before per-project roles existed (no `role`) is upgraded in
    place: its schema keeps its name but is handed to a fresh role."""
    if not settings.admin_database_url:
        return _unavailable(
            "APP_BUILDER_ADMIN_DATABASE_URL is not configured on this "
            "deployment — backend provisioning is an optional feature, "
            "scaffolding/preview work without it")

    project = projects.get(settings.data_dir, ctx, project_id)
    if project is None:
        return _unavailable(f"unknown project: {project_id}")
    if projects.is_archived(project):
        return _unavailable(f"project {project_id} is archived")

    existing = project.backend or {}
    if existing.get("role") and existing.get("database_url"):
        return {"provisioned": True, **existing}

    try:
        schema = existing.get("schema") or _schema_name(ctx, project_id)
        if not _SCHEMA_SAFE_RX.match(schema):
            raise BackendProvisionError(f"unsafe stored schema name {schema!r}")
    except BackendProvisionError as e:
        return _unavailable(str(e))
    role = _role_name(schema)
    password = secrets.token_hex(24)  # hex only: safe inside a SQL literal

    psycopg = _driver()
    if psycopg is None:
        return _unavailable("psycopg is not installed on this deployment "
                            "(pip install 'psycopg[binary]')")

    role_attrs = (f"LOGIN PASSWORD '{password}' NOSUPERUSER NOCREATEDB "
                  f"NOCREATEROLE NOINHERIT CONNECTION LIMIT {_CONNECTION_LIMIT}")
    try:
        with psycopg.connect(settings.admin_database_url, autocommit=True) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "DO $$ BEGIN "
                    f"IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{role}') THEN "
                    f'CREATE ROLE "{role}" {role_attrs}; '
                    f'ELSE ALTER ROLE "{role}" {role_attrs}; '
                    "END IF; END $$")
                # PG16: a CREATEROLE admin that is not a superuser gets ADMIN
                # OPTION on the roles it creates but NOT the right to SET ROLE
                # to them, and both `CREATE SCHEMA ... AUTHORIZATION role` and
                # `ALTER ... OWNER TO role` need it. Grant it to ourselves.
                # (Pre-16 has no WITH SET syntax - and no such restriction.)
                try:
                    cur.execute(f'GRANT "{role}" TO CURRENT_USER WITH SET TRUE')
                except psycopg.errors.SyntaxError:
                    pass
                cur.execute(f'CREATE SCHEMA IF NOT EXISTS "{schema}" AUTHORIZATION "{role}"')
                # Covers a legacy admin-owned schema: hand it and its tables to
                # the role so RLS/DDL work and nothing stays admin-owned.
                cur.execute(f'ALTER SCHEMA "{schema}" OWNER TO "{role}"')
                cur.execute(
                    "DO $$ DECLARE t record; BEGIN "
                    f"FOR t IN SELECT tablename FROM pg_tables WHERE schemaname = '{schema}' LOOP "
                    f"EXECUTE format('ALTER TABLE %I.%I OWNER TO %I', '{schema}', t.tablename, '{role}'); "
                    "END LOOP; END $$")
                cur.execute(f'REVOKE ALL ON SCHEMA public FROM "{role}"')
                cur.execute(f'ALTER ROLE "{role}" SET search_path = "{schema}"')
    except Exception as e:
        return _unavailable(f"provisioning failed: {str(e)[:500]}")

    record = {"schema": schema, "role": role,
              "database_url": _project_url(settings.admin_database_url,
                                           role, password, schema),
              "provisioned_at": time.time()}
    project.backend = record
    projects.upsert(settings.data_dir, ctx, project)

    audit.record(tenant_root(settings.data_dir, ctx), {
        "kind": "backend_provisioned", "project_id": project_id,
        "actor": ctx.subject, "schema": schema, "role": role})

    return {"provisioned": True, **record}


def _project_conn(ctx: TenantContext, project_id: str, settings: Settings):
    """(psycopg module, project) or a ready-to-return failure dict."""
    project = projects.get(settings.data_dir, ctx, project_id)
    if project is None:
        return _unavailable(f"unknown project: {project_id}")
    if projects.is_archived(project):
        return _unavailable(f"project {project_id} is archived")
    backend = project.backend or {}
    if not backend.get("database_url") or not backend.get("role"):
        return _unavailable("no backend provisioned for this project yet")
    psycopg = _driver()
    if psycopg is None:
        return _unavailable("psycopg is not installed on this deployment")
    return psycopg, project


def apply_schema(ctx: TenantContext, project_id: str, sql: str,
                 settings: Settings = SETTINGS) -> dict:
    """Run migration SQL AS THE PROJECT'S ROLE, in one transaction. Postgres'
    own privileges are the sandbox: the role can only alter its schema."""
    if not sql or not sql.strip():
        return {"applied": False, "reason": "sql is empty"}
    if len(sql.encode("utf-8", errors="replace")) > _MAX_SCHEMA_SQL_BYTES:
        return {"applied": False, "reason": "sql too large (>200KB)"}
    got = _project_conn(ctx, project_id, settings)
    if isinstance(got, dict):
        return {"applied": False, "reason": got["reason"]}
    psycopg, project = got
    try:
        with psycopg.connect(project.backend["database_url"]) as conn:
            with conn.cursor() as cur:
                cur.execute("SET LOCAL statement_timeout = '15s'")
                cur.execute(sql)
            conn.commit()
    except Exception as e:
        return {"applied": False, "reason": f"schema apply failed: {str(e)[:500]}"}
    audit.record(tenant_root(settings.data_dir, ctx), {
        "kind": "backend_schema_applied", "project_id": project_id,
        "actor": ctx.subject, "sql_bytes": len(sql)})
    return {"applied": True, **list_tables(ctx, project_id, settings)}


def list_tables(ctx: TenantContext, project_id: str,
                settings: Settings = SETTINGS) -> dict:
    """{tables:[{name, rls, columns:[{name,type,nullable}]}]} for the
    project's schema, read as the project's role."""
    got = _project_conn(ctx, project_id, settings)
    if isinstance(got, dict):
        return {"tables": [], "reason": got["reason"]}
    psycopg, project = got
    schema = project.backend["schema"]
    try:
        with psycopg.connect(project.backend["database_url"], autocommit=True) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT c.table_name, c.column_name, c.data_type, c.is_nullable, "
                    "COALESCE(t.rowsecurity, false) "
                    "FROM information_schema.columns c "
                    "LEFT JOIN pg_tables t ON t.schemaname = c.table_schema "
                    "AND t.tablename = c.table_name "
                    f"WHERE c.table_schema = '{schema}' "
                    "ORDER BY c.table_name, c.ordinal_position")
                rows = cur.fetchall()
    except Exception as e:
        return {"tables": [], "reason": f"introspection failed: {str(e)[:500]}"}
    tables: dict[str, dict] = {}
    for name, col, typ, nullable, rls in rows:
        t = tables.setdefault(name, {"name": name, "rls": bool(rls), "columns": []})
        t["columns"].append({"name": col, "type": typ,
                             "nullable": nullable == "YES"})
    return {"tables": list(tables.values())}
