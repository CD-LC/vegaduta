"""Auth tests: mode selection + the keycloak/internal dual-mode fallback.

Run: python3 tests/test_auth.py
"""

from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.auth import AuthError, authenticate
from app.settings import Settings

PASS = 0
FAIL = 0


def check(name, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def settings(**overrides):
    base = dict(auth_mode="keycloak", internal_token="")  # noqa: C408
    base.update(overrides)
    return Settings(**base)


# --- internal mode (explicit), unchanged behavior ------------------------

s = settings(auth_mode="internal", internal_token="s3cr3t")
ctx = authenticate({"X-Internal-Token": "s3cr3t", "X-Tenant-Id": "acme"}, s)
check("internal mode: valid token+tenant authenticates", ctx.tenant_id == "acme")

try:
    authenticate({"X-Internal-Token": "wrong", "X-Tenant-Id": "acme"}, s)
    check("internal mode: wrong token rejected", False)
except AuthError as e:
    check("internal mode: wrong token rejected", e.status == 401)

# --- keycloak mode (default), dual-mode fallback --------------------------

s = settings(auth_mode="keycloak", internal_token="s3cr3t")

# A core-style server-to-server call (X-Internal-Token set, no bearer token
# at all) must succeed even though auth_mode is "keycloak" - this is the
# bug fix: previously this would 401 with "missing bearer token".
ctx = authenticate({"X-Internal-Token": "s3cr3t", "X-Tenant-Id": "acme"}, s)
check("keycloak mode + valid X-Internal-Token: authenticates via internal path",
      ctx.tenant_id == "acme")

# Wrong internal token in keycloak mode must still fail closed (falls into
# _internal_auth's constant-time compare, not silently ignored).
try:
    authenticate({"X-Internal-Token": "wrong", "X-Tenant-Id": "acme"}, s)
    check("keycloak mode + wrong X-Internal-Token: rejected", False)
except AuthError as e:
    check("keycloak mode + wrong X-Internal-Token: rejected", e.status == 401)

# A true browser call (no X-Internal-Token header at all) must still go
# through the keycloak path unchanged - i.e. fail with "missing bearer
# token", NOT be silently granted access.
try:
    authenticate({"Authorization": ""}, s)
    check("keycloak mode + no X-Internal-Token: falls through to keycloak path", False)
except AuthError as e:
    check("keycloak mode + no X-Internal-Token: falls through to keycloak path",
          e.status == 401 and "bearer" in e.message.lower())

# If the service has no internal_token configured at all (default, unset),
# an X-Internal-Token header must NOT bypass keycloak validation - the
# dual-mode check requires settings.internal_token to be truthy too.
s_no_secret = settings(auth_mode="keycloak", internal_token="")
try:
    authenticate({"X-Internal-Token": "anything", "X-Tenant-Id": "acme"}, s_no_secret)
    check("keycloak mode + no internal_token configured: X-Internal-Token ignored", False)
except AuthError as e:
    check("keycloak mode + no internal_token configured: X-Internal-Token ignored",
          e.status == 401 and "bearer" in e.message.lower())

# --- none mode, unchanged --------------------------------------------------

s = settings(auth_mode="none")
ctx = authenticate({}, s)
check("none mode: defaults to 'default' tenant", ctx.tenant_id == "default")
ctx = authenticate({"X-Tenant-Id": "acme"}, s)
check("none mode: honors X-Tenant-Id", ctx.tenant_id == "acme")

# --- keycloak mode: azp (authorized party) allowlist ------------------------
# Security audit 2026-09-18. Real RS256 tokens signed with a throwaway key;
# the ONLY thing stubbed is the JWKS fetch (no Keycloak, no network), so
# signature, issuer and expiry validation all still run for real.

try:
    import time

    import jwt as pyjwt
    from cryptography.hazmat.primitives.asymmetric import rsa
    HAVE_JWT = True
except ImportError as e:                                     # pragma: no cover
    HAVE_JWT = False
    print(f"  skip  azp tests - PyJWT[crypto] not installed ({e})")

if HAVE_JWT:
    import app.auth as auth_module

    ISSUER = "http://localhost:8180/realms/agentic-ai"
    KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)

    class _StubSigningKey:
        key = KEY.public_key()

    class _StubJWKClient:
        def __init__(self, url):
            self.url = url

        def get_signing_key_from_jwt(self, token):
            return _StubSigningKey()

    pyjwt.PyJWKClient = _StubJWKClient
    auth_module._JWKS_CACHE.clear()

    def mint(**claims):
        body = {"iss": ISSUER, "sub": "user-1", "tenant_id": "acme",
                "aud": "account", "exp": int(time.time()) + 300}
        body.update(claims)
        body = {k: v for k, v in body.items() if v is not None}
        return pyjwt.encode(body, KEY, algorithm="RS256")

    def bearer(**claims):
        return {"Authorization": "Bearer " + mint(**claims)}

    def rejected(name, headers, s, needle):
        try:
            authenticate(headers, s)
            check(name, False, "(was accepted)")
        except AuthError as e:
            check(name, e.status == 401 and needle in e.message,
                  f"(got {e.status}: {e.message})")

    s = settings(auth_mode="keycloak", internal_token="s3cr3t",
                 keycloak_issuer=ISSUER)

    check("azp default allowlist is the three first-party clients",
          auth_module._allowed_azp(s) == {"agentic-ai-web", "agentic-ai-ide",
                                          "agentic-ai-core"})

    for client in ("agentic-ai-web", "agentic-ai-ide", "agentic-ai-core"):
        ctx = authenticate(bearer(azp=client), s)
        check(f"azp {client}: accepted by default",
              ctx.tenant_id == "acme" and ctx.subject == "user-1"
              and not ctx.via_internal_token)

    # The finding itself: a validly-signed, in-date, right-issuer token with a
    # tenant_id claim, minted for a DIFFERENT realm client.
    rejected("azp academy-webapp: rejected 401",
             bearer(azp="academy-webapp"), s, "academy-webapp")
    rejected("azp agentic-ai-platform-admin: rejected 401",
             bearer(azp="agentic-ai-platform-admin"), s, "not allowed")
    rejected("azp missing: rejected 401 (fail closed)",
             bearer(azp=None), s, "no azp")
    rejected("azp empty string: rejected 401", bearer(azp=""), s, "no azp")
    rejected("azp non-string: rejected 401",
             bearer(azp=["agentic-ai-web"]), s, "no azp")
    rejected("azp match is exact, not case-insensitive",
             bearer(azp="Agentic-AI-Web"), s, "not allowed")
    rejected("azp match is exact, not a prefix/substring",
             bearer(azp="agentic-ai-web-evil"), s, "not allowed")
    # aud naming an allowed client must not substitute for azp.
    rejected("aud=agentic-ai-web does not rescue a foreign azp",
             bearer(azp="academy-webapp", aud="agentic-ai-web"), s,
             "academy-webapp")

    # Operator override via CODE_INTEL_KEYCLOAK_ALLOWED_AZP (whitespace and
    # empty entries tolerated).
    s_custom = settings(auth_mode="keycloak", keycloak_issuer=ISSUER,
                        keycloak_allowed_azp=" agentic-ai-mobile , ,custom ")
    ctx = authenticate(bearer(azp="agentic-ai-mobile"), s_custom)
    check("custom allowlist: listed client accepted", ctx.tenant_id == "acme")
    rejected("custom allowlist: replaces the default (web now rejected)",
             bearer(azp="agentic-ai-web"), s_custom, "not allowed")

    s_empty = settings(auth_mode="keycloak", keycloak_issuer=ISSUER,
                       keycloak_allowed_azp="")
    rejected("empty allowlist: every bearer token rejected, no wildcard",
             bearer(azp="agentic-ai-web"), s_empty, "not allowed")

    # The internal-token path must be untouched by the allowlist - even an
    # EMPTY one, and even with a foreign-client bearer token riding along.
    s_empty_internal = settings(auth_mode="keycloak", internal_token="s3cr3t",
                                keycloak_issuer=ISSUER, keycloak_allowed_azp="")
    ctx = authenticate({"X-Internal-Token": "s3cr3t", "X-Tenant-Id": "acme"},
                       s_empty_internal)
    check("internal-token path: unaffected by an empty azp allowlist",
          ctx.tenant_id == "acme" and ctx.via_internal_token)
    headers = bearer(azp="academy-webapp")
    headers.update({"X-Internal-Token": "s3cr3t", "X-Tenant-Id": "acme"})
    ctx = authenticate(headers, s)
    check("internal-token path: azp never consulted",
          ctx.tenant_id == "acme" and ctx.via_internal_token)

    # Pre-existing checks still run, in the same order as before: a bad
    # signature/issuer is a 401 from decode, and an allowed azp with no
    # tenant_id still gets the 403.
    rejected("wrong issuer still rejected before azp",
             bearer(azp="agentic-ai-web", iss="http://evil/realms/x"), s,
             "token validation failed")
    try:
        authenticate(bearer(azp="agentic-ai-web", tenant_id=None), s)
        check("allowed azp without tenant_id: still 403", False)
    except AuthError as e:
        check("allowed azp without tenant_id: still 403", e.status == 403)

    # --- SEC-056: configurable audience check (CODE_INTEL_KEYCLOAK_AUDIENCE) --
    check("audience unset -> not verified (default aud=account accepted)",
          authenticate(bearer(azp="agentic-ai-web"), s).tenant_id == "acme")
    s_blank_aud = settings(auth_mode="keycloak", keycloak_issuer=ISSUER,
                           keycloak_audience="  ")
    check("blank audience env treated as unset",
          authenticate(bearer(azp="agentic-ai-web"), s_blank_aud).tenant_id == "acme")
    s_aud = settings(auth_mode="keycloak", keycloak_issuer=ISSUER,
                     keycloak_audience="code-intel, code-intel-alt")
    check("audience set: matching aud accepted",
          authenticate(bearer(azp="agentic-ai-web", aud="code-intel"),
                       s_aud).tenant_id == "acme")
    check("audience set: any listed audience matches (list aud claim)",
          authenticate(bearer(azp="agentic-ai-web",
                              aud=["account", "code-intel-alt"]),
                       s_aud).tenant_id == "acme")
    rejected("audience set: aud=account rejected",
             bearer(azp="agentic-ai-web"), s_aud, "token validation failed")
    rejected("audience set: missing aud rejected",
             bearer(azp="agentic-ai-web", aud=None), s_aud,
             "token validation failed")
    rejected("audience set: azp allowlist STILL enforced",
             bearer(azp="academy-webapp", aud="code-intel"), s_aud,
             "academy-webapp")
    rejected("audience set: missing azp still rejected",
             bearer(azp=None, aud="code-intel"), s_aud, "no azp")

print(f"\n{PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
