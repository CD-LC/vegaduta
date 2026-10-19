"""Environment-driven configuration for the rl-trainer microservice.

Mirrors the platform's conventions (see code-intel/app/settings.py):
everything injectable via env vars, sane local-dev defaults, no secrets in
files. This service is the OFFLINE optimizer for the Java `core/rl` module -
it exports data, we return improved artifacts. It is LLM-driven only: there
are NO torch/GPU dependencies in the default image (see README's
"GPU-enablement path" for the optional weight-training worker).
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Settings:
    # Auth mode (identical semantics to code-intel/app/auth.py):
    #   internal - trusted calls from core over the private compose network:
    #              X-Tenant-Id header + shared secret X-Internal-Token
    #              (constant-time compared against RL_TRAINER_INTERNAL_TOKEN).
    #              This is the DEFAULT: core/rl is the only caller of the
    #              offline optimizer, there is no browser-facing page.
    #   keycloak - validate Bearer JWTs against the realm JWKS and require a
    #              tenant_id claim (kept for parity with the other sidecars;
    #              also transparently accepts an internal-token call).
    #   none     - local dev only; tenant from X-Tenant-Id or "default".
    auth_mode: str = field(
        default_factory=lambda: os.environ.get("RL_TRAINER_AUTH_MODE", "internal")
    )

    internal_token: str = field(
        default_factory=lambda: os.environ.get("RL_TRAINER_INTERNAL_TOKEN", "")
    )

    # Keycloak (auth_mode=keycloak). Same issuer-vs-jwks split as code-intel:
    # the issuer VALIDATED against the token's `iss` claim must be the
    # BROWSER-facing URL (KC_HOSTNAME_STRICT), while the JWKS is FETCHED over
    # the internal service name. Empty jwks_url => derive from the issuer.
    # No default (AGENTS.md rule 4: a token issuer is never hardcoded to
    # localhost): compose and Helm always set it, and with auth_mode=keycloak
    # an empty value makes every token request answer 503 instead of
    # silently trusting a dev issuer.
    keycloak_issuer: str = field(
        default_factory=lambda: os.environ.get("RL_TRAINER_KEYCLOAK_ISSUER", "")
    )
    # Extra issuers accepted on the token's `iss` (comma-separated): the
    # dual-issuer window of a domain migration, exactly core's
    # KEYCLOAK_ADDITIONAL_ISSUERS (AGENTS.md rule 7). The service-specific
    # name wins; the shared name is what docker-compose passes through.
    keycloak_additional_issuers: str = field(
        default_factory=lambda: os.environ.get(
            "RL_TRAINER_KEYCLOAK_ADDITIONAL_ISSUERS",
            os.environ.get("KEYCLOAK_ADDITIONAL_ISSUERS", ""),
        )
    )

    def keycloak_issuers(self) -> list[str]:
        """Every issuer a token may carry: the primary one plus the additional list."""
        raw = [self.keycloak_issuer, *self.keycloak_additional_issuers.split(",")]
        return [i.strip() for i in raw if i and i.strip()]

    keycloak_jwks_url: str = field(
        default_factory=lambda: os.environ.get("RL_TRAINER_KEYCLOAK_JWKS_URL", "")
    )
    keycloak_audience: str | None = field(
        default_factory=lambda: os.environ.get("RL_TRAINER_KEYCLOAK_AUDIENCE")
    )
    # SEC-056 (same fix as code-intel, security audit 2026-09-18): allowlist
    # of `azp` (authorized party = the Keycloak client the token was MINTED
    # for), comma-separated. Audience is only verified when
    # RL_TRAINER_KEYCLOAK_AUDIENCE is set, and Keycloak access tokens carry
    # aud="account" unless an audience mapper is added - so without this
    # check a validly-signed token from ANY client in the realm (academy-webapp
    # included) was accepted. Default = the first-party clients that
    # legitimately reach this service:
    #   agentic-ai-core - core's own client, for a relayed/service token
    #   agentic-ai-web  - the console's session token
    # agentic-ai-app, -ide, -mobile, -platform-admin and academy-webapp are
    # deliberately NOT included; add one here by env var if it ever needs
    # direct access. Empty string => EVERY bearer token is refused; there is
    # intentionally no "allow any" wildcard. The X-Internal-Token path never
    # reaches this check.
    keycloak_allowed_azp: str = field(
        default_factory=lambda: os.environ.get(
            "RL_TRAINER_KEYCLOAK_ALLOWED_AZP", "agentic-ai-core,agentic-ai-web"
        )
    )

    # LLM (Anthropic) config for the prompt optimizer and the constitutional
    # judge. The API key is read straight from ANTHROPIC_API_KEY by the SDK -
    # we deliberately do NOT copy it into this dataclass so it never lands in
    # a log or an error body.
    llm_model: str = field(
        default_factory=lambda: os.environ.get("RL_TRAINER_LLM_MODEL", "claude-sonnet-5")
    )
    # Whether an ANTHROPIC_API_KEY is present. LLM endpoints fail closed with
    # a clear 503 when it is missing, rather than a raw SDK stack trace.
    llm_api_key_present: bool = field(
        default_factory=lambda: bool(os.environ.get("ANTHROPIC_API_KEY", "").strip())
    )
    # Upper bound on output tokens for a single optimizer / judge call.
    llm_max_tokens: int = field(
        default_factory=lambda: int(os.environ.get("RL_TRAINER_LLM_MAX_TOKENS", "8000"))
    )

    # Working directory for any exported artifacts (DPO job manifests, etc.).
    # In docker-compose this is a named volume.
    data_dir: str = field(default_factory=lambda: os.environ.get("RL_TRAINER_DATA_DIR", "./data"))

    # CORS: comma-separated allowed origins. Empty = server-to-server only,
    # which is the expected posture (core/rl calls this; no browser page).
    cors_origins: str = field(default_factory=lambda: os.environ.get("RL_TRAINER_CORS_ORIGINS", ""))


SETTINGS = Settings()
