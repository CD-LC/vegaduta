"""Framework-free tests for app/baas.py — no real Postgres required.

Covers: provision_backend() degrades gracefully (never raises) when
APP_BUILDER_ADMIN_DATABASE_URL isn't configured, the schema-name safety
check rejects dash-containing project ids, and (with a monkeypatched
psycopg) a configured admin URL provisions successfully and persists onto
the Project record.

Run: python3 -m tests.test_baas   (from the app-builder directory)
"""

from __future__ import annotations

import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import baas, projects  # noqa: E402
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


class _FakeCursor:
    executed: list[str] = []
    rows: list[tuple] = []
    fail_on: str | None = None

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def execute(self, sql):
        if _FakeCursor.fail_on and _FakeCursor.fail_on in sql:
            raise RuntimeError("permission denied for schema other")
        _FakeCursor.executed.append(sql)

    def fetchall(self):
        return _FakeCursor.rows


class _FakeConn:
    def __init__(self, url):
        self.url = url
        _FakePsycopgModule.urls.append(url)

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def cursor(self):
        return _FakeCursor()

    def commit(self):
        pass


class _FakePsycopgModule:
    """Stands in for the `psycopg` package in sys.modules so baas.py's
    `import psycopg` picks this up instead of needing the real driver (or a
    real Postgres) installed for the test."""
    urls: list[str] = []

    @staticmethod
    def connect(url, autocommit=False):
        return _FakeConn(url)


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-baas-")
    try:
        ctx = TenantContext(tenant_id="tenant_a")

        print("== no APP_BUILDER_ADMIN_DATABASE_URL configured ==")
        settings_unconfigured = Settings(data_dir=tmp, admin_database_url="")
        projects.upsert(tmp, ctx, projects.Project(
            project_id="myapp", tenant_id="tenant_a",
            template_id="node-react", status="ready",
            code_intel_repo_id="myapp"))
        result = baas.provision_backend(ctx, "myapp", settings_unconfigured)
        check("degrades gracefully with no admin URL (no crash)",
              result["provisioned"] is False)
        check("gives a clear reason", "not configured" in result["reason"])

        print("== unknown project ==")
        result = baas.provision_backend(ctx, "no-such-project",
                                        Settings(data_dir=tmp,
                                                admin_database_url="postgres://x"))
        check("unknown project reported, not raised",
              result["provisioned"] is False)

        print("== dash in project_id rejected as unsafe schema name ==")
        projects.upsert(tmp, ctx, projects.Project(
            project_id="my-app", tenant_id="tenant_a",
            template_id="node-react", status="ready",
            code_intel_repo_id="my-app"))
        result = baas.provision_backend(
            ctx, "my-app", Settings(data_dir=tmp,
                                    admin_database_url="postgres://x"))
        check("dash-containing project id rejected",
              result["provisioned"] is False
              and "not safe" in result["reason"])

        print("== provisioning succeeds against a fake psycopg ==")
        had_real_psycopg = "psycopg" in sys.modules
        prior_module = sys.modules.get("psycopg")
        sys.modules["psycopg"] = _FakePsycopgModule
        try:
            settings_ok = Settings(data_dir=tmp,
                                   admin_database_url="postgresql://admin:adminpw@db:5432/platform")
            result = baas.provision_backend(ctx, "myapp", settings_ok)

            check("provisioning reports success", result.get("provisioned") is True,
                  extra=str(result))
            schema = result.get("schema", "")
            check("schema name carries tenant hash + project id",
                  schema.startswith("app_") and schema.endswith("_myapp")
                  and schema != "app_myapp", extra=schema)
            check("dedicated role named after the schema",
                  result.get("role") == f"u_{schema}")
            url = result.get("database_url", "")
            check("database_url authenticates as the project role, not admin",
                  f"u_{schema}:" in url and "adminpw" not in url
                  and "//admin" not in url, extra=url)
            check("database_url keeps host/port/db and pins search_path",
                  "@db:5432/platform" in url
                  and f"options=-csearch_path%3D{schema}" in url)
            joined = "\n".join(_FakeCursor.executed)
            check("CREATE ROLE is unprivileged and connection-limited",
                  "NOSUPERUSER" in joined and "NOCREATEROLE" in joined
                  and "CONNECTION LIMIT" in joined)
            check("schema created owned by the role",
                  f'CREATE SCHEMA IF NOT EXISTS "{schema}" AUTHORIZATION "u_{schema}"' in joined)
            check("role search_path pinned to its schema",
                  f'ALTER ROLE "u_{schema}" SET search_path = "{schema}"' in joined)
            check("public schema access revoked from the role",
                  f'REVOKE ALL ON SCHEMA public FROM "u_{schema}"' in joined)

            project = projects.get(tmp, ctx, "myapp")
            check("backend record persisted onto the project",
                  project.backend is not None
                  and project.backend.get("role") == f"u_{schema}")
            check("admin credentials are never persisted",
                  "adminpw" not in str(project.backend))

            print("== re-provisioning is idempotent (no password rotation) ==")
            _FakeCursor.executed.clear()
            again = baas.provision_backend(ctx, "myapp", settings_ok)
            check("same database_url returned", again.get("database_url") == url)
            check("no SQL issued the second time", not _FakeCursor.executed)

            print("== two tenants, same project id -> different schemas ==")
            ctx_b = TenantContext(tenant_id="tenant_b")
            projects.upsert(tmp, ctx_b, projects.Project(
                project_id="myapp", tenant_id="tenant_b",
                template_id="node-react", status="ready",
                code_intel_repo_id="myapp"))
            other = baas.provision_backend(ctx_b, "myapp", settings_ok)
            check("tenant B gets its own schema and role",
                  other.get("schema") != schema and other.get("role") != result.get("role"))

            print("== legacy record (no role) is upgraded in place ==")
            projects.upsert(tmp, ctx, projects.Project(
                project_id="legacy", tenant_id="tenant_a",
                template_id="node-react", status="ready",
                code_intel_repo_id="legacy",
                backend={"schema": "app_legacy",
                         "database_url": "postgresql://admin:adminpw@db/platform"}))
            _FakeCursor.executed.clear()
            up = baas.provision_backend(ctx, "legacy", settings_ok)
            check("legacy schema name kept", up.get("schema") == "app_legacy")
            check("upgraded record has a role and no admin creds",
                  up.get("role") == "u_app_legacy" and "adminpw" not in up["database_url"])
            check("legacy tables handed to the new role",
                  any("ALTER TABLE %I.%I OWNER TO %I" in q for q in _FakeCursor.executed))

            print("== apply_schema runs as the project role ==")
            _FakePsycopgModule.urls.clear()
            _FakeCursor.executed.clear()
            _FakeCursor.rows = [("notes", "id", "integer", "NO", False),
                                ("notes", "body", "text", "YES", False)]
            res = baas.apply_schema(ctx, "myapp", "CREATE TABLE notes (id int, body text);", settings_ok)
            check("apply reports success", res.get("applied") is True, extra=str(res))
            check("connected with the project role url, not admin",
                  bool(_FakePsycopgModule.urls)
                  and all(f"u_{schema}:" in u for u in _FakePsycopgModule.urls),
                  extra=str(_FakePsycopgModule.urls))
            check("statement timeout set before the migration",
                  _FakeCursor.executed[0].startswith("SET LOCAL statement_timeout"))
            check("tables introspected after apply",
                  bool(res["tables"]) and res["tables"][0]["name"] == "notes"
                  and len(res["tables"][0]["columns"]) == 2
                  and res["tables"][0]["columns"][1]["nullable"] is True)

            print("== apply_schema failure + guards ==")
            _FakeCursor.fail_on = "FORBIDDEN"
            bad = baas.apply_schema(ctx, "myapp", "GRANT ALL ON FORBIDDEN;", settings_ok)
            _FakeCursor.fail_on = None
            check("DB error surfaces as applied:false with reason",
                  bad.get("applied") is False and "permission denied" in bad["reason"])
            check("empty sql rejected",
                  baas.apply_schema(ctx, "myapp", "  ", settings_ok)["applied"] is False)
            check("oversized sql rejected",
                  baas.apply_schema(ctx, "myapp", "x" * (200 * 1024 + 1), settings_ok)["applied"] is False)
            noback = baas.apply_schema(ctx, "no-such", "select 1", settings_ok)
            check("unknown project can't apply", noback["applied"] is False)
        finally:
            if had_real_psycopg:
                sys.modules["psycopg"] = prior_module
            else:
                del sys.modules["psycopg"]

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
