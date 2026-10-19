"""Authentication -> TenantContext, mirroring core's semantics.

Three modes (settings.auth_mode):

- keycloak: validate the Bearer JWT against the realm JWKS and read the
  tenant_id claim. Exactly like core's TenantContextFilter (including the
  2026-07-15 hardening): an authenticated token WITHOUT a tenant_id claim
  is rejected with 403 unless it carries ROLE_platform-admin. Also accepts
  an internal-token call transparently (see the dual-mode comment in
  authenticate() below) - deploy/docker-compose.yml runs this service in
  keycloak mode by default (for the web module's /code-intel page), and
  core's own CodeIntelClient needs to keep working against that same
  default without every deployment having to flip a second env var.
- internal: for calls from core over the private network. Core has already
  authenticated the user and holds TenantContext; it forwards the tenant in
  X-Tenant-Id plus the shared secret in X-Internal-Token. Constant-time
  compare; no secret configured => mode is unusable (fail closed).
- none: local development only. X-Tenant-Id or "default".

This module is framework-free (dicts of headers in, TenantContext or
AuthError out) so it is unit-testable without FastAPI installed.
"""

from __future__ import annotations

import hmac
import time
from typing import Any

from .settings import Settings
from .tenancy import TenantContext, validate_tenant_id


class AuthError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


_JWKS_CACHE: dict[str, tuple[float, Any]] = {}  # value: jwt.PyJWKClient
_JWKS_TTL_SECONDS = 300.0


def _lower_headers(headers: dict[str, str]) -> dict[str, str]:
    return {k.lower(): v for k, v in headers.items()}


def authenticate(headers: dict[str, str], settings: Settings) -> TenantContext:
    h = _lower_headers(headers)
    mode = settings.auth_mode.lower()
    if mode == "internal":
        return _internal_auth(h, settings)
    if mode == "keycloak":
        # Dual-mode fallback: deploy/docker-compose.yml's `code-intel`
        # service defaults CODE_INTEL_AUTH_MODE to keycloak so the web
        # module's /code-intel page can authenticate with its existing
        # browser session, but core's own server-to-server CodeIntelClient
        # has no browser session to forward - it only ever sends
        # X-Internal-Token/X-Tenant-Id (see application.yml's
        # code-intel.internal-token property). Without this branch, every
        # call from core would 401 regardless of a correctly configured
        # CODE_INTEL_INTERNAL_TOKEN, silently defeating the "OFF by default,
        # flip CODE_INTEL_ENABLED=true to turn on" promise in that file's
        # own comment. Safe to check unconditionally: a caller without the
        # real secret still fails the constant-time compare in
        # _internal_auth exactly like today, so this only ever *grants*
        # access to holders of the shared secret, never *removes* the
        # keycloak path for anyone else - true browser callers never send
        # X-Internal-Token, so they always fall through unchanged below.
        if settings.internal_token and h.get("x-internal-token"):
            return _internal_auth(h, settings)
        return _keycloak_auth(h, settings)
    if mode == "none":
        tenant = h.get("x-tenant-id", "default")
        return TenantContext(tenant_id=validate_tenant_id(tenant))
    raise AuthError(500, f"unknown auth mode '{settings.auth_mode}'")


def _internal_auth(h: dict[str, str], settings: Settings) -> TenantContext:
    if not settings.internal_token:
        # Fail closed: internal mode without a configured secret would
        # otherwise mean "any caller may impersonate any tenant".
        raise AuthError(500, "internal auth mode requires CODE_INTEL_INTERNAL_TOKEN to be set")
    supplied = h.get("x-internal-token", "")
    if not hmac.compare_digest(supplied, settings.internal_token):
        raise AuthError(401, "invalid or missing X-Internal-Token")
    tenant = h.get("x-tenant-id", "")
    if not tenant:
        raise AuthError(403, "X-Tenant-Id header required")
    # via_internal_token=True marks this as a trusted core->service call: core
    # has already authenticated the user and (for writes) run its own
    # admin-gated approval before ever forwarding this. See TenantContext's
    # docstring and CodeIntelService._is_admin.
    return TenantContext(tenant_id=validate_tenant_id(tenant), via_internal_token=True)


def _allowed_azp(settings: Settings) -> frozenset[str]:
    return frozenset(
        p.strip() for p in (settings.keycloak_allowed_azp or "").split(",") if p.strip()
    )


def _expected_audiences(settings: Settings) -> list[str]:
    """CODE_INTEL_KEYCLOAK_AUDIENCE as a list (comma-separated; any match).

    SEC-056: when set, the token's `aud` MUST contain one of these (a token
    with no aud, or only aud="account", is refused) - configure it together
    with a Keycloak audience mapper on the first-party clients. When unset
    (or blank - an empty env var must not become an `aud == ""` check that
    PyJWT would handle inconsistently), aud is not verified and the azp
    allowlist below is the client gate. azp is enforced in BOTH cases."""
    return [a.strip() for a in (settings.keycloak_audience or "").split(",") if a.strip()]


def _require_issuers(settings) -> list[str]:
    """The issuers tokens may carry; 503 (not 401) when none is configured, so a
    missing CODE_INTEL_KEYCLOAK_ISSUER is an operator error, not a bad token."""
    allowed = settings.keycloak_issuers()
    if not allowed:
        raise AuthError(503, "CODE_INTEL_KEYCLOAK_ISSUER is not configured")
    return allowed


def _check_issuer(claims: dict, allowed: list[str]) -> None:
    """`iss` must be one of the configured issuers (primary + additional).
    Checked here rather than through PyJWT's single `issuer` so a domain
    migration can accept the old and the new issuer at once (rule 7)."""
    if claims.get("iss") not in allowed:
        raise AuthError(401, "token validation failed: Invalid issuer")


def _check_authorized_party(claims: dict, settings: Settings) -> None:
    """Reject a token minted for a client that has no business calling us.

    Security audit 2026-09-18: signature + issuer prove "this realm issued
    it", not "it was issued FOR a caller of this service". Audience is only
    verified when CODE_INTEL_KEYCLOAK_AUDIENCE is set (it is unset in every
    deployment, since Keycloak's default access token carries aud="account"),
    so a token from any realm client - academy-webapp included - used to be
    accepted. `azp` is the client the token was minted for and is always
    present on a Keycloak access token, so a missing azp is itself a reason to
    refuse (fail closed), not a reason to skip the check. Exact,
    case-sensitive match - client ids are identifiers, not hostnames.

    Only ever called on the bearer path: an X-Internal-Token call returns from
    authenticate() via _internal_auth and never gets here.
    """
    azp = claims.get("azp")
    if not isinstance(azp, str) or not azp.strip():
        raise AuthError(401, "token carries no azp (authorized party) claim")
    if azp not in _allowed_azp(settings):
        raise AuthError(
            401,
            f"token was issued to client '{azp}', which is "
            "not allowed to call this service (see "
            "CODE_INTEL_KEYCLOAK_ALLOWED_AZP)",
        )


def _keycloak_auth(h: dict[str, str], settings: Settings) -> TenantContext:
    authz = h.get("authorization", "")
    if not authz.lower().startswith("bearer "):
        raise AuthError(401, "missing bearer token")
    token = authz[7:].strip()

    try:
        import jwt as pyjwt  # PyJWT
        from jwt import PyJWKClient
    except ImportError as e:
        raise AuthError(
            500, "auth_mode=keycloak requires PyJWT[crypto] (pip install 'PyJWT[crypto]')"
        ) from e

    # JWKS is fetched from a possibly DIFFERENT host than the issuer we
    # validate against (internal service name vs browser-facing issuer under
    # KC_HOSTNAME_STRICT) - see Settings.keycloak_jwks_url. Empty => derive
    # from the issuer (single-host dev).
    allowed_issuers = _require_issuers(settings)
    jwks_url = (
        settings.keycloak_jwks_url.strip()
        or settings.keycloak_issuer.rstrip("/") + "/protocol/openid-connect/certs"
    )
    cached = _JWKS_CACHE.get(jwks_url)
    if cached and (time.time() - cached[0]) < _JWKS_TTL_SECONDS:
        jwk_client = cached[1]
    else:
        jwk_client = PyJWKClient(jwks_url)
        _JWKS_CACHE[jwks_url] = (time.time(), jwk_client)

    audiences = _expected_audiences(settings)
    try:
        signing_key = jwk_client.get_signing_key_from_jwt(token)
        claims = pyjwt.decode(
            token,
            signing_key.key,
            algorithms=["RS256", "ES256"],
            audience=audiences or None,
            issuer=None,  # checked against the issuer LIST below
            options={"verify_aud": bool(audiences)},
        )
    except Exception as e:
        raise AuthError(401, f"token validation failed: {e}") from e

    _check_issuer(claims, allowed_issuers)
    _check_authorized_party(claims, settings)

    roles = frozenset((claims.get("realm_access") or {}).get("roles", []) or [])
    tenant = (claims.get("tenant_id") or "").strip()
    if not tenant:
        # Same rule (and reason) as core's TenantContextFilter: tenant-less
        # tokens are only legitimate for platform admins.
        if "platform-admin" in roles or "ROLE_platform-admin" in roles:
            raise AuthError(
                403, "platform-admin tokens are tenant-less; this service's APIs are tenant-scoped"
            )
        raise AuthError(403, "authenticated token carries no tenant_id claim")
    return TenantContext(
        tenant_id=validate_tenant_id(tenant), subject=claims.get("sub"), roles=roles
    )
