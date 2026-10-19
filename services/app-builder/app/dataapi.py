"""Public data API: HTTPS access to a project's own database schema.

Why this exists: `baas.provision_backend` hands out a DATABASE_URL, but the
database lives on a private VNet host, so an app deployed to GitHub Pages,
Vercel or a phone cannot use it - and exposing Postgres to the internet is not
an option. This module is the safe door instead: a PostgREST-style REST surface
(`/data/v1/{table}`) in front of exactly one project's schema.

Security model (read this before changing anything):

* **Two key kinds per project.** `anon` is meant to ship inside client code, so
  it can do nothing a table policy has not explicitly allowed (default: deny
  everything). `service` can reach every table of the project but is for
  servers only - a request carrying an `Origin` header (i.e. from a browser)
  presenting a service key is refused, so it cannot be leaked via a bundle.
* **A key resolves to exactly one (tenant, project)** through an index file;
  only a SHA-256 of the secret is stored (the anon key is also kept readable on
  the project because it is public by design).
* **No SQL from user text.** `dataquery` only emits identifiers that exist in
  the introspected schema and binds every value.
* **Defence in depth below the app:** every request runs as the project's own
  Postgres role (see baas.py) - it cannot see another project's schema even if
  this module had a bug. Each request has statement/lock timeouts, a row cap,
  a body cap and a bounded share of the (small, shared) database's connections.
* **Rate limits** per key (authoritative) and per client IP (best effort).
* **Row-level security is NOT applied here**: the proxy connects as the table
  owner, which bypasses RLS unless a table uses FORCE ROW LEVEL SECURITY. Table
  policies are the control; per-end-user row isolation needs end-user auth,
  which this layer does not provide.
"""

from __future__ import annotations

import json
import re
import time

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse, Response
from starlette.concurrency import run_in_threadpool

from . import audit, projects
from . import dataquery as dq
from .auth import authenticate
from .dataapi_db import introspect as _introspect
from .dataapi_db import run as _run
from .dataapi_errors import DataError
from .dataapi_keys import KeyInfo, authenticate_key, register_key, remove_project_keys
from .dataapi_ratelimit import LIMITER as _LIMITER
from .dataapi_ratelimit import client_ip as _client_ip
from .settings import SETTINGS, Settings
from .tenancy import TenantContext, tenant_root, validate_project_id

OPS = ("select", "insert", "update", "delete")
_METHOD_OP = {"GET": "select", "POST": "insert", "PATCH": "update", "DELETE": "delete"}
_ORIGIN_RX = re.compile(r"^https?://[A-Za-z0-9.\-]{1,253}(:\d{1,5})?$")
MAX_BODY_BYTES = 256 * 1024
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
MAX_TABLE_NAME = 128
_PROJECT_TTL = 5.0
_FAILED_AUTH_PER_MINUTE = 60

# Key index, rate limiter, database IO and CORS live in the dataapi_* modules;
# `_introspect` / `_run` / `_LIMITER` are bound here by name so this module's
# request logic (and the tests that fake the database) use one lookup point.

_PROJECT_CACHE: dict[tuple[str, str], tuple[float, object]] = {}


def revoke_project(ctx: TenantContext, project_id: str, settings: Settings = SETTINGS) -> int:
    """Drop every key of a project (project deleted or Data API disabled)."""
    revoked = remove_project_keys(settings, ctx.tenant_id, project_id)
    _PROJECT_CACHE.pop((ctx.tenant_id, project_id), None)
    return revoked


def _get_project(settings: Settings, info: KeyInfo):
    ck = (info.tenant_id, info.project_id)
    hit = _PROJECT_CACHE.get(ck)
    if hit and time.time() - hit[0] < _PROJECT_TTL:
        return hit[1]
    p = projects.get(settings.data_dir, TenantContext(tenant_id=info.tenant_id), info.project_id)
    _PROJECT_CACHE[ck] = (time.time(), p)
    return p


def _data_cfg(project) -> dict | None:
    cfg = (project.backend or {}).get("data_api") if project else None
    return cfg if cfg and cfg.get("enabled") else None


# ------------------------------------------------------------- request logic


def _origin_headers(cfg: dict, origin: str | None) -> dict:
    allowed = cfg.get("allowed_origins") or []
    if not allowed:
        return {"Access-Control-Allow-Origin": "*"}
    if origin and origin.lower() in allowed:
        return {"Access-Control-Allow-Origin": origin, "Vary": "Origin"}
    return {}


def _check_origin(cfg: dict, info: KeyInfo, origin: str | None) -> None:
    if info.kind == "service" and origin:
        raise DataError(
            403,
            "service_key_in_browser",
            "service keys must never be used from a browser; use "
            "the anon key (and table policies) in client code",
        )
    allowed = cfg.get("allowed_origins") or []
    if info.kind == "anon" and allowed and origin and origin.lower() not in allowed:
        raise DataError(
            403, "origin_not_allowed", "this origin is not allowed for this project's API key"
        )


def _allowed(info: KeyInfo, cfg: dict, table: str, op: str) -> bool:
    if info.kind == "service":
        return True
    return bool(((cfg.get("policies") or {}).get(table) or {}).get(op))


def _authorize(settings: Settings, headers: dict, ip: str):
    if not settings.data_api_enabled:
        raise DataError(503, "disabled", "the data API is disabled on this deployment")
    presented = headers.get("apikey")
    if not presented:
        authz = headers.get("authorization", "")
        if authz.lower().startswith("bearer "):
            presented = authz[7:]
    try:
        info = authenticate_key(presented, settings)
    except DataError:
        # Throttle key guessing/flooding per client IP (best effort).
        wait = _LIMITER.hit(f"f:{ip}", _FAILED_AUTH_PER_MINUTE)
        if wait is not None:
            raise DataError(
                429, "rate_limited", "too many failed attempts", {"Retry-After": str(max(1, wait))}
            ) from None
        raise
    per_key = (
        settings.data_api_anon_rpm_per_key
        if info.kind == "anon"
        else settings.data_api_service_rpm_per_key
    )
    wait = _LIMITER.hit(f"k:{info.key_id}", per_key)
    if wait is None and info.kind == "anon":
        wait = _LIMITER.hit(f"i:{info.key_id}:{ip}", settings.data_api_anon_rpm_per_ip)
    if wait is not None:
        raise DataError(
            429, "rate_limited", "too many requests", {"Retry-After": str(max(1, wait))}
        )
    project = _get_project(settings, info)
    cfg = _data_cfg(project)
    if cfg is None or not (project.backend or {}).get("database_url"):
        # Same answer as a bad key: do not reveal that the project exists.
        raise DataError(401, "invalid_api_key", "invalid API key")
    _check_origin(cfg, info, headers.get("origin"))
    return info, project, cfg


def handle_table(
    settings: Settings,
    table: str,
    method: str,
    headers: dict,
    query: list[tuple[str, str]],
    body: object,
    ip: str,
):
    """-> (status, json_text | None, response_headers)."""
    info, project, cfg = _authorize(settings, headers, ip)
    rh = _origin_headers(cfg, headers.get("origin"))
    op = _METHOD_OP.get(method)
    if op is None:
        raise DataError(405, "method_not_allowed", "method not allowed")
    if len(table) > MAX_TABLE_NAME:
        raise DataError(404, "not_found", "unknown table")
    schema = _introspect(project, settings)
    columns = schema.get(table)
    exposed = info.kind == "service" or table in (cfg.get("policies") or {})
    if columns is None or not exposed:
        raise DataError(404, "not_found", "unknown table", rh)
    if not _allowed(info, cfg, table, op):
        raise DataError(
            403, "operation_not_allowed", f"{op} is not allowed on this table for this key", rh
        )
    schema_name = project.backend["schema"]
    minimal = "return=minimal" in headers.get("prefer", "").replace(" ", "")
    try:
        if op == "select":
            params_in = dict(query)  # last value wins for select/order/limit/offset
            sql, params = dq.build_select(
                schema_name,
                table,
                columns,
                dq.parse_select(params_in.get("select"), columns),
                dq.parse_filters(query, columns),
                dq.parse_order(params_in.get("order"), columns),
                dq.parse_limit(params_in.get("limit")),
                dq.parse_offset(params_in.get("offset")),
            )
            rows, _ = _run(project, settings, sql, params, write=False)
            status = 200
        elif op == "insert":
            sql, params = dq.build_insert(schema_name, table, columns, body, returning=not minimal)
            rows, _ = _run(project, settings, sql, params, write=True)
            status = 201
        elif op == "update":
            sql, params = dq.build_update(
                schema_name,
                table,
                columns,
                body,
                dq.parse_filters(query, columns),
                returning=not minimal,
            )
            rows, _ = _run(project, settings, sql, params, write=True)
            status = 200
        else:
            sql, params = dq.build_delete(
                schema_name, table, columns, dq.parse_filters(query, columns), returning=not minimal
            )
            rows, _ = _run(project, settings, sql, params, write=True)
            status = 200
    except dq.QueryError as e:
        raise DataError(e.status, e.code, e.message, rh) from e
    except DataError as e:
        e.headers = {**rh, **e.headers}
        raise
    if minimal and op != "select":
        return status, None, rh
    text = dq.dumps(rows)
    if len(text) > MAX_RESPONSE_BYTES:
        raise DataError(
            413,
            "response_too_large",
            "the result is too large; use limit/offset or select fewer columns",
            rh,
        )
    return status, text, rh


def describe(settings: Settings, headers: dict, ip: str):
    """GET /data/v1 -> the tables this key can see (and with which operations)."""
    info, project, cfg = _authorize(settings, headers, ip)
    rh = _origin_headers(cfg, headers.get("origin"))
    schema = _introspect(project, settings)
    out = []
    for name, cols in schema.items():
        if info.kind == "service":
            ops = list(OPS)
        else:
            pol = (cfg.get("policies") or {}).get(name)
            if pol is None:
                continue
            ops = [o for o in OPS if pol.get(o)]
        out.append(
            {
                "name": name,
                "operations": ops,
                "columns": [
                    {"name": c.name, "type": c.data_type, "nullable": c.nullable}
                    for c in cols.values()
                ],
            }
        )
    return {"role": info.kind, "tables": out}, rh


# ------------------------------------------------------------------- routes

router = APIRouter()


def _err(e: DataError) -> JSONResponse:
    return JSONResponse(
        status_code=e.status, headers=e.headers, content={"error": e.message, "code": e.code}
    )


async def _read_json_body(request: Request) -> object:
    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > MAX_BODY_BYTES:
        raise DataError(413, "body_too_large", f"body exceeds {MAX_BODY_BYTES} bytes")
    raw = b""
    async for chunk in request.stream():
        raw += chunk
        if len(raw) > MAX_BODY_BYTES:
            raise DataError(413, "body_too_large", f"body exceeds {MAX_BODY_BYTES} bytes")
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise DataError(400, "bad_json", "request body must be valid JSON") from None


@router.get("/data/v1")
async def data_root(request: Request):
    headers = {k.lower(): v for k, v in request.headers.items()}
    ip = _client_ip(headers, request.client.host if request.client else "")
    try:
        payload, rh = await run_in_threadpool(describe, SETTINGS, headers, ip)
    except DataError as e:
        return _err(e)
    return JSONResponse(payload, headers={"Cache-Control": "no-store", **rh})


@router.api_route("/data/v1/{table}", methods=["GET", "POST", "PATCH", "DELETE"])
async def data_table(table: str, request: Request):
    headers = {k.lower(): v for k, v in request.headers.items()}
    ip = _client_ip(headers, request.client.host if request.client else "")
    try:
        if len(request.url.query) > 4096:
            raise DataError(414, "query_too_long", "query string too long")
        body = None
        if request.method in ("POST", "PATCH"):
            body = await _read_json_body(request)
        status, text, rh = await run_in_threadpool(
            handle_table,
            SETTINGS,
            table,
            request.method,
            headers,
            request.query_params.multi_items(),
            body,
            ip,
        )
    except DataError as e:
        return _err(e)
    common = {"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", **rh}
    if text is None:
        return Response(status_code=status, headers=common)
    return Response(content=text, status_code=status, media_type="application/json", headers=common)


# -------------------------------------------------------------- management
# Authenticated (Keycloak) routes the console uses to turn the API on, manage
# keys and decide which tables the anon key may touch.

mgmt = APIRouter()


def _ctx(request: Request) -> TenantContext:
    return authenticate(dict(request.headers), SETTINGS)


def _project_with_backend(ctx: TenantContext, project_id: str, settings: Settings):
    validate_project_id(project_id)
    project = projects.get_or_404(settings.data_dir, ctx, project_id)
    backend = project.backend or {}
    if not backend.get("database_url") or not backend.get("schema"):
        raise DataError(409, "no_backend", "provision this project's backend first")
    return project


def _save(settings: Settings, ctx: TenantContext, project) -> None:
    projects.upsert(settings.data_dir, ctx, project)
    _PROJECT_CACHE.pop((ctx.tenant_id, project.project_id), None)


def _base_url(request: Request, settings: Settings) -> str:
    base = settings.public_base_url or str(request.base_url).rstrip("/")
    return base + "/data/v1"


def _policy_view(cfg: dict, schema: dict) -> list[dict]:
    pols = cfg.get("policies") or {}
    return [
        {
            "name": t,
            "columns": [
                {"name": c.name, "type": c.data_type, "nullable": c.nullable} for c in cols.values()
            ],
            "policy": {o: bool((pols.get(t) or {}).get(o)) for o in OPS},
        }
        for t, cols in schema.items()
    ]


def get_config(
    ctx: TenantContext,
    project_id: str,
    base_url: str,
    settings: Settings = SETTINGS,
    *,
    service_key: str | None = None,
) -> dict:
    project = _project_with_backend(ctx, project_id, settings)
    cfg = (project.backend or {}).get("data_api")
    if not cfg or not cfg.get("enabled"):
        return {"enabled": False, "available": settings.data_api_enabled}
    tables, reason = [], None
    try:
        tables = _policy_view(cfg, _introspect(project, settings, fresh=True))
    except DataError as e:
        reason = e.message
    out = {
        "enabled": True,
        "available": settings.data_api_enabled,
        "base_url": base_url,
        "anon_key": cfg["anon_key"],
        "has_service_key": bool(cfg.get("service_key_id")),
        "allowed_origins": cfg.get("allowed_origins") or [],
        "tables": tables,
        "limits": {
            "max_rows": dq.MAX_LIMIT,
            "max_insert_rows": dq.MAX_INSERT_ROWS,
            "max_body_bytes": MAX_BODY_BYTES,
            "statement_timeout_ms": settings.data_api_statement_timeout_ms,
            "anon_requests_per_minute": settings.data_api_anon_rpm_per_key,
        },
    }
    if reason:
        out["tables_error"] = reason
    if service_key:
        out["service_key"] = service_key  # shown exactly once
    return out


def enable(
    ctx: TenantContext, project_id: str, base_url: str, settings: Settings = SETTINGS
) -> dict:
    if not settings.data_api_enabled:
        raise DataError(503, "disabled", "the data API is disabled on this deployment")
    project = _project_with_backend(ctx, project_id, settings)
    cfg = (project.backend or {}).get("data_api")
    if cfg and cfg.get("enabled"):
        return get_config(ctx, project_id, base_url, settings)
    anon_id, anon_key = register_key(settings, ctx, project_id, "anon")
    svc_id, svc_key = register_key(settings, ctx, project_id, "service")
    project.backend["data_api"] = {
        "enabled": True,
        "anon_key": anon_key,
        "anon_key_id": anon_id,
        "service_key_id": svc_id,
        "policies": {},
        "allowed_origins": [],
        "enabled_at": time.time(),
    }
    _save(settings, ctx, project)
    audit.record(
        tenant_root(settings.data_dir, ctx),
        {"kind": "data_api_enabled", "project_id": project_id, "actor": ctx.subject},
    )
    return get_config(ctx, project_id, base_url, settings, service_key=svc_key)


def rotate(
    ctx: TenantContext, project_id: str, kind: str, base_url: str, settings: Settings = SETTINGS
) -> dict:
    if kind not in ("anon", "service"):
        raise DataError(400, "bad_kind", "kind must be 'anon' or 'service'")
    project = _project_with_backend(ctx, project_id, settings)
    cfg = (project.backend or {}).get("data_api")
    if not cfg or not cfg.get("enabled"):
        raise DataError(409, "not_enabled", "the data API is not enabled for this project")
    key_id, key = register_key(settings, ctx, project_id, kind)
    if kind == "anon":
        cfg["anon_key"], cfg["anon_key_id"] = key, key_id
    else:
        cfg["service_key_id"] = key_id
    _save(settings, ctx, project)
    audit.record(
        tenant_root(settings.data_dir, ctx),
        {
            "kind": "data_api_key_rotated",
            "project_id": project_id,
            "actor": ctx.subject,
            "key_kind": kind,
        },
    )
    return get_config(
        ctx, project_id, base_url, settings, service_key=key if kind == "service" else None
    )


def set_policies(
    ctx: TenantContext,
    project_id: str,
    policies: dict,
    allowed_origins: list | None,
    base_url: str,
    settings: Settings = SETTINGS,
) -> dict:
    project = _project_with_backend(ctx, project_id, settings)
    cfg = (project.backend or {}).get("data_api")
    if not cfg or not cfg.get("enabled"):
        raise DataError(409, "not_enabled", "the data API is not enabled for this project")
    schema = _introspect(project, settings, fresh=True)
    clean: dict = {}
    for table, ops in (policies or {}).items():
        if table not in schema:
            raise DataError(400, "unknown_table", f"unknown table: {str(table)[:80]!r}")
        if not isinstance(ops, dict):
            raise DataError(400, "bad_policy", "each policy must be an object")
        bad = [o for o in ops if o not in OPS]
        if bad:
            raise DataError(400, "bad_policy", f"unknown operation: {str(bad[0])[:20]!r}")
        entry = {o: bool(ops.get(o)) for o in OPS}
        if any(entry.values()):
            clean[table] = entry
    cfg["policies"] = clean
    if allowed_origins is not None:
        if len(allowed_origins) > 20:
            raise DataError(400, "bad_origins", "at most 20 allowed origins")
        norm = []
        for o in allowed_origins:
            o = str(o).strip().rstrip("/").lower()
            if not _ORIGIN_RX.match(o):
                raise DataError(
                    400, "bad_origins", f"not an origin (scheme://host[:port]): {o[:80]!r}"
                )
            norm.append(o)
        cfg["allowed_origins"] = sorted(set(norm))
    _save(settings, ctx, project)
    audit.record(
        tenant_root(settings.data_dir, ctx),
        {
            "kind": "data_api_policies_set",
            "project_id": project_id,
            "actor": ctx.subject,
            "tables": sorted(clean),
        },
    )
    return get_config(ctx, project_id, base_url, settings)


def disable(ctx: TenantContext, project_id: str, settings: Settings = SETTINGS) -> dict:
    project = _project_with_backend(ctx, project_id, settings)
    revoked = revoke_project(ctx, project_id, settings)
    (project.backend or {}).pop("data_api", None)
    _save(settings, ctx, project)
    audit.record(
        tenant_root(settings.data_dir, ctx),
        {
            "kind": "data_api_disabled",
            "project_id": project_id,
            "actor": ctx.subject,
            "keys_revoked": revoked,
        },
    )
    return {"enabled": False, "keys_revoked": revoked}


def _wrap(fn):
    def inner(*a, **k):
        try:
            return fn(*a, **k)
        except DataError as e:
            return _err(e)

    return inner


@mgmt.get("/projects/{project_id}/data/config")
def route_config(project_id: str, request: Request, ctx: TenantContext = Depends(_ctx)):
    return _wrap(get_config)(ctx, project_id, _base_url(request, SETTINGS), SETTINGS)


@mgmt.post("/projects/{project_id}/data/enable")
def route_enable(project_id: str, request: Request, ctx: TenantContext = Depends(_ctx)):
    return _wrap(enable)(ctx, project_id, _base_url(request, SETTINGS), SETTINGS)


@mgmt.post("/projects/{project_id}/data/keys/rotate")
def route_rotate(project_id: str, body: dict, request: Request, ctx: TenantContext = Depends(_ctx)):
    return _wrap(rotate)(
        ctx, project_id, str(body.get("kind", "")), _base_url(request, SETTINGS), SETTINGS
    )


@mgmt.put("/projects/{project_id}/data/policies")
def route_policies(
    project_id: str, body: dict, request: Request, ctx: TenantContext = Depends(_ctx)
):
    return _wrap(set_policies)(
        ctx,
        project_id,
        body.get("policies") or {},
        body.get("allowed_origins"),
        _base_url(request, SETTINGS),
        SETTINGS,
    )


@mgmt.delete("/projects/{project_id}/data")
def route_disable(project_id: str, ctx: TenantContext = Depends(_ctx)):
    return _wrap(disable)(ctx, project_id, SETTINGS)
