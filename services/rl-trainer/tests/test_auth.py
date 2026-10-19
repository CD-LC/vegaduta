"""app/auth.py - headers in, TenantContext or AuthError out.

The keycloak cases sign real RS256 JWTs with a locally generated RSA key and
serve the matching public key from a fake JWKS client dropped into the
module's JWKS cache, so PyJWT's real signature/issuer/audience/expiry checks
run end to end with no network.
"""

from __future__ import annotations

import json
import time
from collections.abc import Callable
from typing import Any

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from jwt.algorithms import RSAAlgorithm

from app import auth
from app.auth import AuthError, authenticate
from app.settings import Settings
from app.tenancy import TenantValidationError

ISSUER = "https://auth.example.test/realms/agentic-ai"
JWKS_URL = "http://keycloak:8080/realms/agentic-ai/protocol/openid-connect/certs"
SECRET = "correct-horse-battery-staple"

MakeSettings = Callable[..., Settings]


# --------------------------------------------------------------------- #
# internal mode


def test_internal_mode_without_configured_secret_fails_closed(
    make_settings: MakeSettings,
) -> None:
    s = make_settings(auth_mode="internal", internal_token="")
    with pytest.raises(AuthError) as ei:
        # Even a caller that sends an (empty) token and a tenant is refused.
        authenticate({"X-Internal-Token": "", "X-Tenant-Id": "acme"}, s)
    assert ei.value.status == 500
    assert "RL_TRAINER_INTERNAL_TOKEN" in ei.value.message


@pytest.mark.parametrize(
    "headers",
    [
        {"X-Tenant-Id": "acme"},
        {"X-Internal-Token": "", "X-Tenant-Id": "acme"},
        {"X-Internal-Token": "wrong", "X-Tenant-Id": "acme"},
        {"X-Internal-Token": SECRET + "x", "X-Tenant-Id": "acme"},
        {"X-Internal-Token": SECRET[:-1], "X-Tenant-Id": "acme"},
    ],
)
def test_internal_mode_rejects_missing_or_wrong_token(
    make_settings: MakeSettings, headers: dict[str, str]
) -> None:
    s = make_settings(auth_mode="internal", internal_token=SECRET)
    with pytest.raises(AuthError) as ei:
        authenticate(headers, s)
    assert ei.value.status == 401
    # The configured secret must never be echoed back in an error.
    assert SECRET not in ei.value.message


def test_internal_mode_right_token_without_tenant_is_403(make_settings: MakeSettings) -> None:
    s = make_settings(auth_mode="internal", internal_token=SECRET)
    with pytest.raises(AuthError) as ei:
        authenticate({"X-Internal-Token": SECRET}, s)
    assert ei.value.status == 403


def test_internal_mode_right_token_ok_and_headers_case_insensitive(
    make_settings: MakeSettings,
) -> None:
    s = make_settings(auth_mode="INTERNAL", internal_token=SECRET)
    ctx = authenticate({"x-internal-token": SECRET, "X-TENANT-ID": "acme_01"}, s)
    assert ctx.tenant_id == "acme_01"
    assert ctx.via_internal_token is True
    assert ctx.subject is None


def test_internal_mode_uses_constant_time_compare(
    make_settings: MakeSettings, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls: list[tuple[str, str]] = []
    real = auth.hmac.compare_digest

    def spy(a: str, b: str) -> bool:
        calls.append((a, b))
        return real(a, b)

    monkeypatch.setattr(auth.hmac, "compare_digest", spy)
    s = make_settings(auth_mode="internal", internal_token=SECRET)
    authenticate({"X-Internal-Token": SECRET, "X-Tenant-Id": "acme"}, s)
    with pytest.raises(AuthError):
        authenticate({"X-Internal-Token": "nope", "X-Tenant-Id": "acme"}, s)
    assert calls == [(SECRET, SECRET), ("nope", SECRET)]


@pytest.mark.parametrize("tenant", ["../etc", "Acme", "a" * 64, "-lead", "a b", "a/b"])
def test_internal_mode_rejects_malformed_tenant_ids(
    make_settings: MakeSettings, tenant: str
) -> None:
    s = make_settings(auth_mode="internal", internal_token=SECRET)
    with pytest.raises(TenantValidationError):
        authenticate({"X-Internal-Token": SECRET, "X-Tenant-Id": tenant}, s)


# --------------------------------------------------------------------- #
# none / unknown modes


def test_none_mode_defaults_tenant_and_honours_header(make_settings: MakeSettings) -> None:
    s = make_settings(auth_mode="none")
    assert authenticate({}, s).tenant_id == "default"
    ctx = authenticate({"X-Tenant-Id": "dev"}, s)
    assert ctx.tenant_id == "dev"
    assert ctx.via_internal_token is False


def test_none_mode_still_validates_tenant(make_settings: MakeSettings) -> None:
    with pytest.raises(TenantValidationError):
        authenticate({"X-Tenant-Id": "../x"}, make_settings(auth_mode="none"))


def test_unknown_mode_is_500(make_settings: MakeSettings) -> None:
    with pytest.raises(AuthError) as ei:
        authenticate({}, make_settings(auth_mode="ldap"))
    assert ei.value.status == 500


# --------------------------------------------------------------------- #
# keycloak mode


class _FakeJwkClient:
    """Stands in for PyJWKClient: returns a real PyJWK built from the test
    public key, exactly what the real client would parse out of the realm's
    JWKS document."""

    def __init__(self, public_jwk: dict[str, Any]) -> None:
        self._jwk = jwt.PyJWK(public_jwk)
        self.calls = 0

    def get_signing_key_from_jwt(self, token: str) -> jwt.PyJWK:
        self.calls += 1
        return self._jwk


def _new_key() -> rsa.RSAPrivateKey:
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


@pytest.fixture(scope="module")
def signing_key() -> rsa.RSAPrivateKey:
    return _new_key()


@pytest.fixture
def jwks(signing_key: rsa.RSAPrivateKey) -> _FakeJwkClient:
    public_jwk = json.loads(RSAAlgorithm.to_jwk(signing_key.public_key()))
    public_jwk.update({"kid": "test-kid", "alg": "RS256", "use": "sig"})
    client = _FakeJwkClient(public_jwk)
    auth._JWKS_CACHE[JWKS_URL] = (time.time(), client)
    return client


@pytest.fixture
def kc(make_settings: MakeSettings) -> Settings:
    return make_settings(auth_mode="keycloak", keycloak_issuer=ISSUER, keycloak_jwks_url=JWKS_URL)


def _token(key: rsa.RSAPrivateKey, **claims: Any) -> str:
    now = int(time.time())
    body: dict[str, Any] = {
        "iss": ISSUER,
        "sub": "user-1",
        "iat": now,
        "exp": now + 300,
        "tenant_id": "acme",
        "realm_access": {"roles": ["user"]},
        # SEC-056: a real Keycloak access token always carries azp; default to
        # an allowlisted first-party client so the non-azp tests stay focused.
        "azp": "agentic-ai-web",
    }
    body.update(claims)
    body = {k: v for k, v in body.items() if v is not None}
    return jwt.encode(body, key, algorithm="RS256", headers={"kid": "test-kid"})


def _bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def test_keycloak_valid_token(
    kc: Settings, jwks: _FakeJwkClient, signing_key: rsa.RSAPrivateKey
) -> None:
    ctx = authenticate(_bearer(_token(signing_key)), kc)
    assert ctx.tenant_id == "acme"
    assert ctx.subject == "user-1"
    assert ctx.roles == frozenset({"user"})
    assert ctx.via_internal_token is False
    assert jwks.calls == 1


def test_keycloak_missing_or_non_bearer_header_is_401(kc: Settings, jwks: _FakeJwkClient) -> None:
    for headers in ({}, {"Authorization": "Basic dXNlcjpwYXNz"}, {"Authorization": "Bearer"}):
        with pytest.raises(AuthError) as ei:
            authenticate(headers, kc)
        assert ei.value.status == 401
    assert jwks.calls == 0


@pytest.mark.parametrize(
    "claims",
    [
        {"iss": "https://evil.example.test/realms/agentic-ai"},
        {"iss": None},
        {"exp": int(time.time()) - 3600},
    ],
    ids=["wrong-issuer", "no-issuer", "expired"],
)
def test_keycloak_rejects_bad_claims(
    kc: Settings,
    jwks: _FakeJwkClient,
    signing_key: rsa.RSAPrivateKey,
    claims: dict[str, Any],
) -> None:
    with pytest.raises(AuthError) as ei:
        authenticate(_bearer(_token(signing_key, **claims)), kc)
    assert ei.value.status == 401


def test_keycloak_rejects_token_signed_by_another_key(kc: Settings, jwks: _FakeJwkClient) -> None:
    with pytest.raises(AuthError) as ei:
        authenticate(_bearer(_token(_new_key())), kc)
    assert ei.value.status == 401


def test_keycloak_rejects_alg_none_and_hs256_confusion(
    kc: Settings, jwks: _FakeJwkClient, signing_key: rsa.RSAPrivateKey
) -> None:
    now = int(time.time())
    claims = {"iss": ISSUER, "exp": now + 300, "tenant_id": "acme"}
    unsigned = jwt.encode(claims, key=None, algorithm="none")
    pem = signing_key.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    # Classic RS->HS confusion: HMAC-sign with the PUBLIC key bytes. PyJWT
    # refuses PEM keys for HMAC, so hand-roll the HS256 signature.
    import base64
    import hashlib
    import hmac

    def b64(b: bytes) -> str:
        return base64.urlsafe_b64encode(b).rstrip(b"=").decode()

    head = b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    payload = b64(json.dumps(claims).encode())
    sig = b64(hmac.new(pem, f"{head}.{payload}".encode(), hashlib.sha256).digest())
    confused = f"{head}.{payload}.{sig}"

    for token in (unsigned, confused):
        with pytest.raises(AuthError) as ei:
            authenticate(_bearer(token), kc)
        assert ei.value.status == 401


def test_keycloak_audience_enforced_only_when_configured(
    make_settings: MakeSettings, jwks: _FakeJwkClient, signing_key: rsa.RSAPrivateKey
) -> None:
    with_aud = make_settings(
        auth_mode="keycloak",
        keycloak_issuer=ISSUER,
        keycloak_jwks_url=JWKS_URL,
        keycloak_audience="rl-trainer",
    )
    ok = authenticate(_bearer(_token(signing_key, aud="rl-trainer")), with_aud)
    assert ok.tenant_id == "acme"
    for aud in ("account", None):
        with pytest.raises(AuthError) as ei:
            authenticate(_bearer(_token(signing_key, aud=aud)), with_aud)
        assert ei.value.status == 401

    without_aud = make_settings(
        auth_mode="keycloak", keycloak_issuer=ISSUER, keycloak_jwks_url=JWKS_URL
    )
    assert authenticate(_bearer(_token(signing_key, aud="anything")), without_aud)


def test_keycloak_token_without_tenant_is_403(
    kc: Settings, jwks: _FakeJwkClient, signing_key: rsa.RSAPrivateKey
) -> None:
    for tenant in (None, "", "   "):
        with pytest.raises(AuthError) as ei:
            authenticate(_bearer(_token(signing_key, tenant_id=tenant)), kc)
        assert ei.value.status == 403
        assert "no tenant_id" in ei.value.message


@pytest.mark.parametrize("role", ["platform-admin", "ROLE_platform-admin"])
def test_keycloak_platform_admin_without_tenant_is_403(
    kc: Settings, jwks: _FakeJwkClient, signing_key: rsa.RSAPrivateKey, role: str
) -> None:
    token = _token(signing_key, tenant_id=None, realm_access={"roles": [role]})
    with pytest.raises(AuthError) as ei:
        authenticate(_bearer(token), kc)
    assert ei.value.status == 403
    assert "platform-admin" in ei.value.message


def test_keycloak_malformed_tenant_claim_rejected(
    kc: Settings, jwks: _FakeJwkClient, signing_key: rsa.RSAPrivateKey
) -> None:
    with pytest.raises(TenantValidationError):
        authenticate(_bearer(_token(signing_key, tenant_id="../other")), kc)


def test_keycloak_mode_accepts_internal_token_dual_mode(
    make_settings: MakeSettings, jwks: _FakeJwkClient
) -> None:
    s = make_settings(
        auth_mode="keycloak",
        keycloak_issuer=ISSUER,
        keycloak_jwks_url=JWKS_URL,
        internal_token=SECRET,
    )
    ctx = authenticate({"X-Internal-Token": SECRET, "X-Tenant-Id": "acme"}, s)
    assert ctx.via_internal_token is True
    # A wrong shared secret does NOT fall through to the JWT path.
    with pytest.raises(AuthError) as ei:
        authenticate({"X-Internal-Token": "guess", "X-Tenant-Id": "acme"}, s)
    assert ei.value.status == 401
    assert jwks.calls == 0


def test_keycloak_mode_ignores_internal_header_when_no_secret_configured(
    kc: Settings, jwks: _FakeJwkClient
) -> None:
    # No RL_TRAINER_INTERNAL_TOKEN: an X-Internal-Token header must not open
    # any door; the request is judged as a (missing) bearer token.
    with pytest.raises(AuthError) as ei:
        authenticate({"X-Internal-Token": "", "X-Tenant-Id": "acme"}, kc)
    assert ei.value.status == 401
    assert "bearer" in ei.value.message


def test_keycloak_jwks_url_derived_from_issuer_and_cached(
    make_settings: MakeSettings,
    monkeypatch: pytest.MonkeyPatch,
    signing_key: rsa.RSAPrivateKey,
) -> None:
    public_jwk = json.loads(RSAAlgorithm.to_jwk(signing_key.public_key()))
    constructed: list[str] = []

    def fake_ctor(url: str) -> _FakeJwkClient:
        constructed.append(url)
        return _FakeJwkClient(public_jwk)

    monkeypatch.setattr(jwt, "PyJWKClient", fake_ctor)
    s = make_settings(auth_mode="keycloak", keycloak_issuer=ISSUER + "/", keycloak_jwks_url="")
    token = _token(signing_key, iss=ISSUER + "/")
    authenticate(_bearer(token), s)
    authenticate(_bearer(token), s)
    assert constructed == [ISSUER + "/protocol/openid-connect/certs"]


# --------------------------------------------------------------------- #
# SEC-056: azp (authorized party) allowlist, mirroring code-intel


def test_default_azp_allowlist_is_core_and_web_only(make_settings: MakeSettings) -> None:
    assert auth._allowed_azp(make_settings()) == {"agentic-ai-core", "agentic-ai-web"}


@pytest.mark.parametrize("client", ["agentic-ai-core", "agentic-ai-web"])
def test_keycloak_allowed_azp_accepted(
    kc: Settings, jwks: _FakeJwkClient, signing_key: rsa.RSAPrivateKey, client: str
) -> None:
    ctx = authenticate(_bearer(_token(signing_key, azp=client)), kc)
    assert ctx.tenant_id == "acme"
    assert ctx.via_internal_token is False


@pytest.mark.parametrize(
    "client",
    [
        "academy-webapp",
        "agentic-ai-app",
        "agentic-ai-ide",
        "agentic-ai-mobile",
        "agentic-ai-platform-admin",
        "Agentic-AI-Web",  # case-sensitive
        "agentic-ai-web-evil",  # exact, not a prefix
    ],
)
def test_keycloak_foreign_azp_rejected(
    kc: Settings, jwks: _FakeJwkClient, signing_key: rsa.RSAPrivateKey, client: str
) -> None:
    # The finding itself: validly-signed, in-date, right-issuer tokens with a
    # tenant_id claim, minted for a client that has no business calling us.
    with pytest.raises(AuthError) as ei:
        authenticate(_bearer(_token(signing_key, azp=client)), kc)
    assert ei.value.status == 401
    assert "not allowed" in ei.value.message


@pytest.mark.parametrize("azp", [None, "", "   ", ["agentic-ai-web"]], ids=repr)
def test_keycloak_missing_or_malformed_azp_rejected(
    kc: Settings, jwks: _FakeJwkClient, signing_key: rsa.RSAPrivateKey, azp: Any
) -> None:
    with pytest.raises(AuthError) as ei:
        authenticate(_bearer(_token(signing_key, azp=azp)), kc)
    assert ei.value.status == 401
    assert "no azp" in ei.value.message


def test_keycloak_aud_does_not_rescue_foreign_azp(
    kc: Settings, jwks: _FakeJwkClient, signing_key: rsa.RSAPrivateKey
) -> None:
    with pytest.raises(AuthError) as ei:
        authenticate(_bearer(_token(signing_key, azp="academy-webapp", aud="agentic-ai-web")), kc)
    assert ei.value.status == 401


def test_keycloak_azp_env_override_replaces_default(
    monkeypatch: pytest.MonkeyPatch, jwks: _FakeJwkClient, signing_key: rsa.RSAPrivateKey
) -> None:
    monkeypatch.setenv("RL_TRAINER_KEYCLOAK_ALLOWED_AZP", " agentic-ai-mobile , ,custom ")
    monkeypatch.setenv("RL_TRAINER_AUTH_MODE", "keycloak")
    monkeypatch.setenv("RL_TRAINER_KEYCLOAK_ISSUER", ISSUER)
    monkeypatch.setenv("RL_TRAINER_KEYCLOAK_JWKS_URL", JWKS_URL)
    s = Settings()
    assert auth._allowed_azp(s) == {"agentic-ai-mobile", "custom"}
    assert authenticate(_bearer(_token(signing_key, azp="agentic-ai-mobile")), s)
    with pytest.raises(AuthError) as ei:
        authenticate(_bearer(_token(signing_key, azp="agentic-ai-web")), s)
    assert ei.value.status == 401


@pytest.mark.parametrize("allowlist", ["", "*"])
def test_keycloak_empty_or_star_allowlist_refuses_everything(
    make_settings: MakeSettings,
    jwks: _FakeJwkClient,
    signing_key: rsa.RSAPrivateKey,
    allowlist: str,
) -> None:
    s = make_settings(
        auth_mode="keycloak",
        keycloak_issuer=ISSUER,
        keycloak_jwks_url=JWKS_URL,
        keycloak_allowed_azp=allowlist,
    )
    with pytest.raises(AuthError) as ei:
        authenticate(_bearer(_token(signing_key, azp="agentic-ai-web")), s)
    assert ei.value.status == 401


def test_internal_token_path_never_consults_azp(
    make_settings: MakeSettings, jwks: _FakeJwkClient, signing_key: rsa.RSAPrivateKey
) -> None:
    s = make_settings(
        auth_mode="keycloak",
        keycloak_issuer=ISSUER,
        keycloak_jwks_url=JWKS_URL,
        internal_token=SECRET,
        keycloak_allowed_azp="",
    )
    headers = _bearer(_token(signing_key, azp="academy-webapp"))
    headers.update({"X-Internal-Token": SECRET, "X-Tenant-Id": "acme"})
    ctx = authenticate(headers, s)
    assert ctx.via_internal_token is True
    assert jwks.calls == 0


def test_keycloak_allowed_azp_without_tenant_still_403(
    kc: Settings, jwks: _FakeJwkClient, signing_key: rsa.RSAPrivateKey
) -> None:
    with pytest.raises(AuthError) as ei:
        authenticate(_bearer(_token(signing_key, azp="agentic-ai-core", tenant_id=None)), kc)
    assert ei.value.status == 403
