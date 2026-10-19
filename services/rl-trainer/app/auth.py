"""Authentication -> TenantContext, mirroring code-intel/app/auth.py.

Three modes (settings.auth_mode):

- internal (DEFAULT): for calls from core over the private network. core has
  already authenticated the user and holds TenantContext; it forwards the
  tenant in X-Tenant-Id plus the shared secret in X-Internal-Token.
  Constant-time compare; no secret configured => mode is unusable (fail
  closed). This is the default because the offline optimizer is only ever
  driven by core/rl - there is no browser-facing page.
- keycloak: validate the Bearer JWT against the realm JWKS and read the
  tenant_id claim (same 403-on-missing-claim rule as core's
  TenantContextFilter). Also transparently accepts an internal-token call, so
  core's server-to-server client keeps working against a keycloak-mode
  deployment without a second env var - identical dual-mode fallback to
  code-intel.
- none: local development only. X-Tenant-Id or "default".

Framework-free (dicts of headers in, TenantContext or AuthError out) so it is
unit-testable without FastAPI installed.
"""

from __future__ import annotations

import hmac
import time
from typing import TYPE_CHECKING, Any

from .settings import Settings
from .tenancy import TenantContext, validate_tenant_id

if TYPE_CHECKING:
    # Type-only: PyJWT is imported lazily inside _keycloak_auth so the default
    # internal/none modes never need it installed.
    from jwt import PyJWKClient
    from jwt.types import Options


class AuthError(Exception):
    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.message = message


_JWKS_CACHE: dict[str, tuple[float, PyJWKClient]] = {}
_JWKS_TTL_SECONDS = 300.0


def _lower_headers(headers: dict[str, str]) -> dict[str, str]:
    return {k.lower(): v for k, v in headers.items()}


def authenticate(headers: dict[str, str], settings: Settings) -> TenantContext:
    h = _lower_headers(headers)
    mode = settings.auth_mode.lower()
    if mode == "internal":
        return _internal_auth(h, settings)
    if mode == "keycloak":
        # Dual-mode fallback (see code-intel/app/auth.py): a caller that
        # presents the shared secret routes to internal-token auth even in
        # keycloak mode. Safe to check unconditionally - a caller without the
        # real secret still fails the constant-time compare, so this only
        # ever *grants* access to holders of the shared secret.
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
        raise AuthError(500, "internal auth mode requires RL_TRAINER_INTERNAL_TOKEN to be set")
    supplied = h.get("x-internal-token", "")
    if not hmac.compare_digest(supplied, settings.internal_token):
        raise AuthError(401, "invalid or missing X-Internal-Token")
    tenant = h.get("x-tenant-id", "")
    if not tenant:
        raise AuthError(403, "X-Tenant-Id header required")
    return TenantContext(tenant_id=validate_tenant_id(tenant), via_internal_token=True)


def _allowed_azp(settings: Settings) -> frozenset[str]:
    return frozenset(
        p.strip() for p in (settings.keycloak_allowed_azp or "").split(",") if p.strip()
    )


def _require_issuers(settings) -> list[str]:
    """The issuers tokens may carry; 503 (not 401) when none is configured, so a
    missing RL_TRAINER_KEYCLOAK_ISSUER is an operator error, not a bad token."""
    allowed = settings.keycloak_issuers()
    if not allowed:
        raise AuthError(503, "RL_TRAINER_KEYCLOAK_ISSUER is not configured")
    return allowed


def _check_issuer(claims: dict, allowed: list[str]) -> None:
    """`iss` must be one of the configured issuers (primary + additional).
    Checked here rather than through PyJWT's single `issuer` so a domain
    migration can accept the old and the new issuer at once (rule 7)."""
    if claims.get("iss") not in allowed:
        raise AuthError(401, "token validation failed: Invalid issuer")


def _check_authorized_party(claims: dict[str, Any], settings: Settings) -> None:
    """Reject a token minted for a client that has no business calling us.

    SEC-056, mirroring code-intel/app/auth.py: signature + issuer prove "this
    realm issued it", not "it was issued FOR a caller of this service".
    Audience is only verified when RL_TRAINER_KEYCLOAK_AUDIENCE is set (and
    then `aud` is REQUIRED - PyJWT refuses a token without one), which it is
    not by default, since Keycloak's default access token carries
    aud="account". `azp` is always present on a Keycloak access token, so a
    missing azp is itself a reason to refuse (fail closed), not a reason to
    skip the check. Exact, case-sensitive match - client ids are identifiers.

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
            "RL_TRAINER_KEYCLOAK_ALLOWED_AZP)",
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

    try:
        signing_key = jwk_client.get_signing_key_from_jwt(token)
        options: Options = {"verify_aud": settings.keycloak_audience is not None}
        claims = pyjwt.decode(
            token,
            signing_key.key,
            algorithms=["RS256", "ES256"],
            audience=settings.keycloak_audience,
            issuer=None,  # checked against the issuer LIST below
            options=options,
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
