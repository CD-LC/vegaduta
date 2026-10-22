"""Preview URL routing, wired to Caddy's admin API.

Replaces the earlier dev-only stub (returned the raw compose-internal
``http://host:port`` — reachable inside the compose network only, never a
real public URL) with actual runtime route registration against
``deploy/caddy/Caddyfile``'s already-running ``:8899`` server, using
Caddy's admin API (default ``http://caddy:2019`` inside the compose
network — see ``deploy/caddy/Caddyfile``'s ``admin`` global directive and
``deploy/compose/docker-compose.yml``'s ``caddy`` service, which deliberately does
NOT publish 2019 to the host or the tunnel — admin API access is
container-to-container only).

Two modes (``APP_BUILDER_INGRESS_MODE`` / ``settings.ingress_mode``):

``path`` (default)
    Registers ``handle_path /preview/{project_id}/*`` on the existing
    ``:8899`` listener — the SAME listener every static route in the
    Caddyfile (web/admin/academy/api/auth/codeintel) already uses — that
    strips the ``/preview/{project_id}`` prefix and reverse-proxies to the
    upstream. Needs zero new DNS: it rides the Host-header-agnostic path
    match on a listener that's already reachable through the tunnel today.
    Returns ``{settings.preview_path_base_url}/preview/{project_id}/``.
    This is the practical default until an operator does the wildcard-DNS
    step below.

``subdomain``
    Registers a Host-header route matching
    ``{project_id}.{settings.preview_domain}`` -> reverse_proxy upstream,
    the same shape as every static route already in the Caddyfile.
    Returns ``https://{project_id}.{settings.preview_domain}`` (the
    Cloudflare tunnel terminates TLS in front of Caddy, same as every
    other route here — see the ``https_proto`` snippet this mirrors).
    REQUIRES a manual, outside-this-repo operator step: point
    ``*.{settings.preview_domain}`` at the Cloudflare tunnel (both in DNS
    and in the tunnel's public-hostname config) so requests for
    ``<project>.preview.staging.example.com`` ever reach this Caddy instance in
    the first place. Nothing in this module can do that step — until it's
    done, ``subdomain`` mode registers a route that is real and correct on
    the Caddy side but unreachable from the outside.

Ordering (why routes are inserted, not appended): the Caddyfile's
``:8899`` server ends in a bare ``handle {}`` catch-all with no matcher —
it matches every request. Caddy's ``routes`` array is evaluated top to
bottom and, for plain (non-``route``-wrapped) directives like the ones in
this Caddyfile, a matching handler chain runs and writes the response,
which in practice ends processing for that request. So any route appended
to the END of the array would always lose to the catch-all, which sorts
before it. We avoid needing to compute "insert right before the catch-all"
by instead always inserting new preview routes at index 0 (the very
front): our matchers (a specific project subdomain, or a
``/preview/{project_id}/*`` path) never overlap the existing
Host-only-matched static routes, so putting them first only ever adds a
new thing Caddy checks before falling through to the existing routes
unchanged — see ``_upsert_route`` below.

Idempotent re-registration: every route we push carries a deterministic
``"@id": "preview-{project_id}"``. Caddy indexes any config object tagged
with ``@id`` so it can be addressed directly at ``/id/<value>`` regardless
of where it lives in the tree. Re-registering the same project (e.g. the
preview process restarted on a new host:port) goes through ``PATCH
/id/preview-{project_id}`` — a full in-place replace that needs no array
bookkeeping — and only falls back to the index-0 insert path the first
time (or if the id was previously deregistered).

Failure posture: every Caddy admin API call is wrapped so a connection
failure, timeout, or non-2xx response never crashes preview start/stop —
it falls back to returning the raw ``http://{upstream_host}:{upstream_port}``
URL (today's stub behavior), which still works for local development
without Caddy running at all. The fallback is always logged (Python
`logging`, so it shows up in the service's own logs) AND recorded through
`app.audit` so it is visible to an operator, not silent. `audit.record`
needs a directory to append to and is normally tenant-scoped, but
`register_preview_route`/`deregister_preview_route` are called from
`preview.py` without a `TenantContext` (see that module's call sites) —
changing that call signature is out of scope here, so ingress-level
fallback events are recorded under a dedicated
``{settings.data_dir}/_ingress`` directory instead of a real tenant's
subtree. The leading underscore is deliberate: tenant ids are validated
against ``^[a-z0-9]...`` (see `app/tenancy.py`), so `_ingress` can never
collide with a real tenant directory.
"""

from __future__ import annotations

import logging
import os

import httpx

from . import audit
from .settings import SETTINGS, Settings

log = logging.getLogger(__name__)

# The Caddyfile's site block address (deploy/caddy/Caddyfile: ":8899 { ... }").
# Used to find the right server object under apps.http.servers.* in Caddy's
# live JSON config without hardcoding Caddy's auto-generated server name
# (typically "srv0", but that's an implementation detail we shouldn't rely
# on staying stable across Caddyfile edits).
_LISTEN_ADDR = ":8899"

_ADMIN_TIMEOUT_SECONDS = 5.0


class CaddyAdminError(RuntimeError):
    """Raised for any problem talking to Caddy's admin API — connection
    refused/timeout, or a non-2xx/404 response where one wasn't expected.
    Always caught at the register/deregister boundary and turned into the
    documented host:port fallback; never propagates to callers."""


def _route_id(project_id: str) -> str:
    return f"preview-{project_id}"


def _audit_dir(settings: Settings) -> str:
    path = os.path.join(settings.data_dir, "_ingress")
    os.makedirs(path, exist_ok=True)
    return path


def _record_fallback(settings: Settings, project_id: str, reason: str) -> None:
    log.warning(
        "ingress: Caddy admin API unavailable, falling back to raw "
        "host:port preview URL for project_id=%s (%s)", project_id, reason)
    try:
        audit.record(_audit_dir(settings), {
            "kind": "preview_ingress_fallback",
            "project_id": project_id,
            "reason": reason,
        })
    except OSError:
        # Never let audit-logging itself break the fallback path.
        log.exception("ingress: failed to write fallback audit event")


def _admin_request(settings: Settings, method: str, path: str,
                    json_body: dict | None = None) -> httpx.Response:
    url = settings.caddy_admin_url.rstrip("/") + path
    try:
        resp = httpx.request(method, url, json=json_body,
                              timeout=_ADMIN_TIMEOUT_SECONDS)
    except httpx.TimeoutException as e:
        raise CaddyAdminError(f"Caddy admin API timed out: {method} {path}") from e
    except httpx.TransportError as e:
        raise CaddyAdminError(f"Caddy admin API unreachable at {url}: {e}") from e
    return resp


def _find_server_name(settings: Settings) -> str:
    """Look up the name Caddy auto-assigned (from the Caddyfile) to the
    server listening on `_LISTEN_ADDR`, by reading the live config rather
    than assuming "srv0". Raises CaddyAdminError if unreachable, malformed,
    or no matching server is found."""
    resp = _admin_request(settings, "GET", "/config/apps/http/servers")
    if resp.status_code != 200:
        raise CaddyAdminError(
            f"unexpected status reading servers: {resp.status_code}")
    try:
        servers = resp.json() or {}
    except ValueError as e:
        raise CaddyAdminError("non-JSON response reading servers") from e

    for name, server in servers.items():
        if _LISTEN_ADDR in (server.get("listen") or []):
            return name
    raise CaddyAdminError(
        f"no Caddy server found listening on {_LISTEN_ADDR}")


def _path_route(project_id: str, upstream_host: str, upstream_port: int,
                route_id: str) -> dict:
    prefix = f"/preview/{project_id}"
    return {
        "@id": route_id,
        "match": [{"path": [f"{prefix}/*"]}],
        "handle": [
            {"handler": "rewrite", "strip_path_prefix": prefix},
            {
                "handler": "reverse_proxy",
                "upstreams": [{"dial": f"{upstream_host}:{upstream_port}"}],
                "headers": {"request": {"set": {
                    "X-Forwarded-Proto": ["https"]}}},
            },
        ],
    }


def _subdomain_route(project_id: str, upstream_host: str, upstream_port: int,
                     route_id: str, preview_domain: str) -> dict:
    host = f"{project_id}.{preview_domain}"
    return {
        "@id": route_id,
        "match": [{"host": [host]}],
        "handle": [
            {
                "handler": "reverse_proxy",
                "upstreams": [{"dial": f"{upstream_host}:{upstream_port}"}],
                "headers": {"request": {"set": {
                    "X-Forwarded-Proto": ["https"]}}},
            },
        ],
    }


def _upsert_route(settings: Settings, route_id: str, route: dict) -> None:
    """Push `route` live into Caddy, addressed by its `@id`. Tries an
    in-place replace first (the common case: re-registering an already-known
    project, e.g. the preview process restarted on a new host:port) and
    only inserts a brand-new array entry if nothing with this id exists yet.
    New entries always go in at index 0 of the target server's routes array
    — see the module docstring's "Ordering" section for why front-insertion
    is always safe here and avoids needing to locate the catch-all."""
    patch_resp = _admin_request(settings, "PATCH", f"/id/{route_id}", route)
    if patch_resp.status_code in (200, 201):
        return
    if patch_resp.status_code != 404:
        raise CaddyAdminError(
            f"unexpected status updating route {route_id}: "
            f"{patch_resp.status_code} {patch_resp.text[:300]}")

    # Not registered yet — insert at the front of the live server's routes.
    server_name = _find_server_name(settings)
    insert_resp = _admin_request(
        settings, "PUT",
        f"/config/apps/http/servers/{server_name}/routes/0", route)
    if insert_resp.status_code not in (200, 201):
        raise CaddyAdminError(
            f"unexpected status inserting route {route_id}: "
            f"{insert_resp.status_code} {insert_resp.text[:300]}")


def register_preview_route(project_id: str, upstream_host: str,
                           upstream_port: int,
                           settings: Settings = SETTINGS) -> str:
    route_id = _route_id(project_id)
    mode = settings.ingress_mode

    try:
        if mode == "subdomain":
            route = _subdomain_route(
                project_id, upstream_host, upstream_port, route_id,
                settings.preview_domain)
            _upsert_route(settings, route_id, route)
            return f"https://{project_id}.{settings.preview_domain}"

        # default / "path"
        route = _path_route(
            project_id, upstream_host, upstream_port, route_id)
        _upsert_route(settings, route_id, route)
        base = settings.preview_path_base_url.rstrip("/")
        return f"{base}/preview/{project_id}/"
    except CaddyAdminError as e:
        _record_fallback(settings, project_id, str(e))
        return f"http://{upstream_host}:{upstream_port}"


def deregister_preview_route(project_id: str,
                             settings: Settings = SETTINGS) -> None:
    route_id = _route_id(project_id)
    try:
        resp = _admin_request(settings, "DELETE", f"/id/{route_id}")
        if resp.status_code not in (200, 204, 404):
            raise CaddyAdminError(
                f"unexpected status deleting route {route_id}: "
                f"{resp.status_code} {resp.text[:300]}")
    except CaddyAdminError as e:
        # No downstream URL to fall back to here (the caller already has
        # nothing to do with a return value) — just make sure the stale
        # route not being cleaned up is visible to an operator rather than
        # silently leaking a dangling Caddy route.
        _record_fallback(settings, project_id, f"deregister failed: {e}")
