"""Framework-free tests for app/auth.py's keycloak mode - the SEC-056 azp
(authorized party) allowlist, mirroring code-intel/tests/test_auth.py.

Real RS256 tokens signed with a throwaway key; the ONLY thing stubbed is the
JWKS fetch (no Keycloak, no network), so signature, issuer, audience and
expiry validation all still run for real.

Run: python3 -m tests.test_auth   (from the app-builder directory)
"""

from __future__ import annotations

import os
import sys
import time
from dataclasses import replace

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

import jwt as pyjwt  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import rsa  # noqa: E402

import app.auth as auth_module  # noqa: E402
from app.auth import AuthError, authenticate  # noqa: E402
from app.settings import Settings  # noqa: E402

PASS = 0
FAIL = 0

ENV_VAR = "APP_BUILDER_KEYCLOAK_ALLOWED_AZP"
ISSUER = "http://localhost:8180/realms/agentic-ai"
KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)


def check(name: str, cond: bool, extra: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


class _StubSigningKey:
    key = KEY.public_key()


class _StubJWKClient:
    def __init__(self, url: str) -> None:
        self.url = url

    def get_signing_key_from_jwt(self, token: str) -> _StubSigningKey:
        return _StubSigningKey()


def settings(**overrides) -> Settings:
    return replace(Settings(), **overrides)


def mint(**claims) -> str:
    body = {
        "iss": ISSUER,
        "sub": "user-1",
        "tenant_id": "acme",
        "aud": "account",
        "exp": int(time.time()) + 300,
    }
    body.update(claims)
    body = {k: v for k, v in body.items() if v is not None}
    return pyjwt.encode(body, KEY, algorithm="RS256")


def bearer(**claims) -> dict[str, str]:
    return {"Authorization": "Bearer " + mint(**claims)}


def rejected(name: str, headers: dict[str, str], s: Settings, needle: str, status: int = 401):
    try:
        authenticate(headers, s)
        check(name, False, "(was accepted)")
    except AuthError as e:
        check(name, e.status == status and needle in e.message, f"(got {e.status}: {e.message})")


def main() -> int:
    orig_client = pyjwt.PyJWKClient
    orig_env = os.environ.pop(ENV_VAR, None)
    pyjwt.PyJWKClient = _StubJWKClient
    auth_module._JWKS_CACHE.clear()
    try:
        _run()
    finally:
        pyjwt.PyJWKClient = orig_client
        auth_module._JWKS_CACHE.clear()
        if orig_env is not None:
            os.environ[ENV_VAR] = orig_env
        else:
            os.environ.pop(ENV_VAR, None)
    print(f"\n{PASS} passed, {FAIL} failed")
    return 1 if FAIL else 0


def _run() -> None:
    s = settings(auth_mode="keycloak", internal_token="s3cr3t", keycloak_issuer=ISSUER)

    check(
        "default allowlist is agentic-ai-core + agentic-ai-web only",
        auth_module._allowed_azp(Settings()) == {"agentic-ai-core", "agentic-ai-web"},
    )

    for client in ("agentic-ai-core", "agentic-ai-web"):
        ctx = authenticate(bearer(azp=client), s)
        check(
            f"azp {client}: accepted by default",
            ctx.tenant_id == "acme" and ctx.subject == "user-1" and not ctx.via_internal_token,
        )

    # The finding itself: validly-signed, in-date, right-issuer tokens with a
    # tenant_id claim, minted for a DIFFERENT realm client.
    for client in (
        "academy-webapp",
        "agentic-ai-app",
        "agentic-ai-ide",
        "agentic-ai-mobile",
        "agentic-ai-platform-admin",
    ):
        rejected(f"azp {client}: rejected 401 by default", bearer(azp=client), s, client)

    rejected("azp missing: rejected 401 (fail closed)", bearer(azp=None), s, "no azp")
    rejected("azp empty string: rejected 401", bearer(azp=""), s, "no azp")
    rejected("azp whitespace: rejected 401", bearer(azp="  "), s, "no azp")
    rejected("azp non-string: rejected 401", bearer(azp=["agentic-ai-web"]), s, "no azp")
    rejected("azp match is case-sensitive", bearer(azp="Agentic-AI-Web"), s, "not allowed")
    rejected(
        "azp match is exact, not a prefix", bearer(azp="agentic-ai-web-evil"), s, "not allowed"
    )
    rejected(
        "aud=agentic-ai-web does not rescue a foreign azp",
        bearer(azp="academy-webapp", aud="agentic-ai-web"),
        s,
        "academy-webapp",
    )

    # Operator override via the real env var (whitespace/empty entries tolerated).
    os.environ[ENV_VAR] = " agentic-ai-mobile , ,custom "
    try:
        s_custom = settings(auth_mode="keycloak", keycloak_issuer=ISSUER)
    finally:
        os.environ.pop(ENV_VAR, None)
    check(
        "env override parsed",
        auth_module._allowed_azp(s_custom) == {"agentic-ai-mobile", "custom"},
    )
    ctx = authenticate(bearer(azp="agentic-ai-mobile"), s_custom)
    check("custom allowlist: listed client accepted", ctx.tenant_id == "acme")
    rejected(
        "custom allowlist replaces the default (web now rejected)",
        bearer(azp="agentic-ai-web"),
        s_custom,
        "not allowed",
    )

    for allowlist, label in (("", "empty allowlist"), ("*", "'*' is not a wildcard")):
        s_x = settings(auth_mode="keycloak", keycloak_issuer=ISSUER, keycloak_allowed_azp=allowlist)
        rejected(
            f"{label}: bearer token rejected", bearer(azp="agentic-ai-web"), s_x, "not allowed"
        )

    # The internal-token path is untouched by the allowlist - even an EMPTY
    # one, and even with a foreign-client bearer token riding along.
    s_empty_internal = settings(
        auth_mode="keycloak",
        internal_token="s3cr3t",
        keycloak_issuer=ISSUER,
        keycloak_allowed_azp="",
    )
    ctx = authenticate({"X-Internal-Token": "s3cr3t", "X-Tenant-Id": "acme"}, s_empty_internal)
    check(
        "internal-token path: unaffected by an empty azp allowlist",
        ctx.tenant_id == "acme" and ctx.via_internal_token,
    )
    headers = bearer(azp="academy-webapp")
    headers.update({"X-Internal-Token": "s3cr3t", "X-Tenant-Id": "acme"})
    ctx = authenticate(headers, s)
    check(
        "internal-token path: azp never consulted",
        ctx.tenant_id == "acme" and ctx.via_internal_token,
    )

    # Pre-existing checks still run: bad issuer/expiry is a 401 from decode, an
    # allowed azp without tenant_id is still a 403, and the optional audience
    # check still applies on top of azp - with aud REQUIRED once configured.
    rejected(
        "wrong issuer still rejected",
        bearer(azp="agentic-ai-web", iss="http://evil/realms/x"),
        s,
        "token validation failed",
    )
    rejected(
        "expired token still rejected",
        bearer(azp="agentic-ai-web", exp=int(time.time()) - 60),
        s,
        "token validation failed",
    )
    rejected(
        "allowed azp without tenant_id: still 403",
        bearer(azp="agentic-ai-web", tenant_id=None),
        s,
        "tenant_id",
        status=403,
    )
    s_aud = settings(auth_mode="keycloak", keycloak_issuer=ISSUER, keycloak_audience="svc")
    rejected(
        "audience configured: wrong aud rejected",
        bearer(azp="agentic-ai-web", aud="account"),
        s_aud,
        "token validation failed",
    )
    rejected(
        "audience configured: missing aud rejected (aud required)",
        bearer(azp="agentic-ai-web", aud=None),
        s_aud,
        "token validation failed",
    )
    ctx = authenticate(bearer(azp="agentic-ai-web", aud="svc"), s_aud)
    check("audience + allowed azp: accepted", ctx.tenant_id == "acme")
    rejected(
        "audience matches but azp foreign: still rejected",
        bearer(azp="academy-webapp", aud="svc"),
        s_aud,
        "not allowed",
    )


if __name__ == "__main__":
    raise SystemExit(main())
