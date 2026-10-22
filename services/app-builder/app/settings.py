"""Environment-driven configuration for the app-builder microservice.

Sibling of `code-intel/app/settings.py` — same shape, same conventions
(everything injectable via env vars, sane local-dev defaults, no secrets in
files). app-builder is a CLIENT of code-intel (never vendors its engine),
so this also carries the settings needed to reach code-intel's HTTP API.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Settings:
    # Where per-tenant project checkouts + the project registry live. In
    # docker-compose this is a named volume; every tenant gets an isolated
    # subtree underneath (see app/tenancy.py).
    data_dir: str = field(default_factory=lambda: os.environ.get("APP_BUILDER_DATA_DIR", "./data"))

    # Where the starter-kit catalog lives (templates/, repo-root sibling of
    # this service by default; overridable for tests / alternate layouts).
    templates_dir: str = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_TEMPLATES_DIR", "./templates")
    )

    # Auth mode — identical semantics to code-intel/app/auth.py's three
    # modes (keycloak / internal / none). See app/auth.py for the full
    # docstring; kept in lockstep with code-intel on purpose so the two
    # services read like the same engineering team wrote them.
    auth_mode: str = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_AUTH_MODE", "internal")
    )

    internal_token: str = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_INTERNAL_TOKEN", "")
    )

    # Keycloak (auth_mode=keycloak). Defaults match deploy/compose/docker-compose.yml
    # and code-intel/app/settings.py's own keycloak_issuer default — see that
    # file's docstring for why the issuer must be the BROWSER-facing URL
    # under KC_HOSTNAME_STRICT while the JWKS fetch host may differ.
    # No default (AGENTS.md rule 4: a token issuer is never hardcoded to
    # localhost): compose and Helm always set it, and with auth_mode=keycloak
    # an empty value makes every token request answer 503 instead of
    # silently trusting a dev issuer.
    keycloak_issuer: str = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_KEYCLOAK_ISSUER", "")
    )
    # Extra issuers accepted on the token's `iss` (comma-separated): the
    # dual-issuer window of a domain migration, exactly core's
    # KEYCLOAK_ADDITIONAL_ISSUERS (AGENTS.md rule 7). The service-specific
    # name wins; the shared name is what docker-compose passes through.
    keycloak_additional_issuers: str = field(
        default_factory=lambda: os.environ.get(
            "APP_BUILDER_KEYCLOAK_ADDITIONAL_ISSUERS",
            os.environ.get("KEYCLOAK_ADDITIONAL_ISSUERS", ""),
        )
    )

    def keycloak_issuers(self) -> list[str]:
        """Every issuer a token may carry: the primary one plus the additional list."""
        raw = [self.keycloak_issuer, *self.keycloak_additional_issuers.split(",")]
        return [i.strip() for i in raw if i and i.strip()]

    keycloak_jwks_url: str = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_KEYCLOAK_JWKS_URL", "")
    )
    keycloak_audience: str | None = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_KEYCLOAK_AUDIENCE")
    )
    # SEC-056 (same fix as code-intel, security audit 2026-09-18): allowlist
    # of `azp` (authorized party = the Keycloak client the token was MINTED
    # for), comma-separated. Audience is only verified when
    # APP_BUILDER_KEYCLOAK_AUDIENCE is set, and Keycloak access tokens carry
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
            "APP_BUILDER_KEYCLOAK_ALLOWED_AZP", "agentic-ai-core,agentic-ai-web"
        )
    )

    # code-intel client. Backend-to-backend URL, same pattern as every other
    # internal service link in this platform: localhost for local dev,
    # compose DNS name in docker (see docker-compose.app-builder.yml, which
    # overrides this to http://code-intel:8090). NEVER a public domain here
    # — this call never goes through a browser.
    code_intel_url: str = field(
        default_factory=lambda: os.environ.get(
            "APP_BUILDER_CODE_INTEL_URL", "http://localhost:8090"
        )
    )
    # Shared secret for code-intel's own `internal` auth mode (its
    # X-Internal-Token). Deliberately a SEPARATE env var from this service's
    # own internal_token above — app-builder is code-intel's client here,
    # not the other way around, and the two secrets protect different
    # trust boundaries (who may call app-builder vs. who app-builder claims
    # to be when it calls code-intel).
    code_intel_internal_token: str = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_CODE_INTEL_TOKEN", "")
    )
    code_intel_timeout_seconds: int = field(
        default_factory=lambda: int(os.environ.get("APP_BUILDER_CODE_INTEL_TIMEOUT", "60"))
    )
    # code-intel registers local repos by absolute path and re-checks that the
    # path exists in ITS OWN filesystem. app-builder and code-intel run in
    # separate containers with separate volumes, so a path under this service's
    # data_dir does not exist over there — every scaffold used to 500 at
    # code-intel's `local path does not exist` guard. Deployment mounts
    # app-builder's data volume into code-intel READ-ONLY at this prefix; when
    # empty (bare local dev with a shared filesystem) the path is sent
    # unchanged, preserving today's single-host behaviour.
    code_intel_repo_prefix: str = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_CODE_INTEL_REPO_PREFIX", "")
    )

    # CORS: comma-separated allowed origins for browser callers. Empty = no
    # browser access (server-to-server only) — same posture as code-intel.
    cors_origins: str = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_CORS_ORIGINS", "")
    )

    # Interactive API docs. Secure by DEFAULT-OFF for the same reason as
    # code-intel: an OpenAPI schema on a public subdomain is reconnaissance
    # material an unauthenticated visitor should not get for free.
    expose_docs: bool = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_EXPOSE_DOCS", "false").lower() == "true"
    )

    # generate.py: NL instruction -> file edits. 2026-08-06 routing fix —
    # this used to call the Anthropic Python SDK directly, hardcoded, with
    # no fallback ("Failed: generate() requires: pip install anthropic
    # (and ANTHROPIC_API_KEY)"). It now calls core's own
    # `POST /api/internal/app-builder/generate-files`, which resolves the
    # SAME platform-hosted-pool/BYOK routing every other LLM call in this
    # platform already uses (AssistService's pattern — see
    # AppBuilderCompletionController/AppBuilderCompletionService in
    # core/agents/appbuilder). Model choice, prompt-building, and the
    # provider credential itself all live on core's side now — there is no
    # app-builder-side model/max-tokens knob to carry anymore (core's own
    # `app.app-builder.max-tokens` / APP_BUILDER_MAX_TOKENS plays that role
    # there). Same "app-builder is a CLIENT, never vendors the engine"
    # posture as code_intel_url/code_intel_internal_token below.
    core_base_url: str = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_CORE_BASE_URL", "http://localhost:8080")
    )
    # Shared secret for core's X-Service-Token check
    # (AppBuilderCompletionController#authenticate, `permitAll()` at the
    # Spring Security layer — this controller is the sole gate). MUST equal
    # core's own `app.app-builder.service-token`, set via the SAME env var
    # name on core's side too (APP_BUILDER_CORE_SERVICE_TOKEN — see
    # core/app/src/main/resources/application.yml's `app-builder:` block),
    # same "one secret kept in sync by using the same name in both
    # environments" pattern code_intel_internal_token's docstring below
    # already documents for the code-intel pair.
    core_service_token: str = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_CORE_SERVICE_TOKEN", "")
    )
    core_timeout_seconds: int = field(
        default_factory=lambda: int(os.environ.get("APP_BUILDER_CORE_TIMEOUT", "120"))
    )

    # generate.modify_project's self-repair loop: how many corrective
    # regenerations may follow a failed build/test (each one sees the files it
    # wrote plus the new failure output). 1 reproduces the old single retry;
    # each attempt is one more hosted-model call, so keep it small.
    max_fix_attempts: int = field(
        default_factory=lambda: max(
            0, min(6, int(os.environ.get("APP_BUILDER_MAX_FIX_ATTEMPTS", "3")))
        )
    )

    # dataapi.py: public HTTPS data API in front of each project's own schema
    # (the provisioned DATABASE_URL is a private-VNet host a deployed app cannot
    # reach). `data_api_enabled` is the operator kill switch; the public base
    # URL is what the console shows users (the request's own base URL is
    # unreliable behind the ingress, so set APP_BUILDER_PUBLIC_URL in prod).
    data_api_enabled: bool = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_DATA_API_ENABLED", "true")
        .strip()
        .lower()
        not in ("0", "false", "no", "off")
    )
    public_base_url: str = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_PUBLIC_URL", "").strip().rstrip("/")
    )
    # The projects' shared database caps total connections (see baas.py /
    # the prod bootstrap), so the proxy bounds its own concurrent DB use well
    # below it and answers 503 rather than queueing without limit.
    data_api_max_conns: int = field(
        default_factory=lambda: max(
            1, min(12, int(os.environ.get("APP_BUILDER_DATA_API_MAX_CONNS", "8")))
        )
    )
    data_api_statement_timeout_ms: int = field(
        default_factory=lambda: max(
            500,
            min(30000, int(os.environ.get("APP_BUILDER_DATA_API_STATEMENT_TIMEOUT_MS", "5000"))),
        )
    )
    # Requests per minute. The per-key limit is the authoritative control;
    # the per-IP one is best-effort (client IP comes from proxy headers).
    data_api_anon_rpm_per_key: int = field(
        default_factory=lambda: int(os.environ.get("APP_BUILDER_DATA_API_ANON_RPM", "600"))
    )
    data_api_anon_rpm_per_ip: int = field(
        default_factory=lambda: int(os.environ.get("APP_BUILDER_DATA_API_ANON_RPM_PER_IP", "120"))
    )
    data_api_service_rpm_per_key: int = field(
        default_factory=lambda: int(os.environ.get("APP_BUILDER_DATA_API_SERVICE_RPM", "1200"))
    )

    # baas.py: optional per-project Postgres schema provisioning. An
    # operator-managed, ADMIN-capable connection string to the platform's
    # existing Postgres. Empty => provision_backend() no-ops with a clear
    # "not configured" result rather than crashing the service — a project
    # can scaffold/preview with no database at all. See baas.py's module
    # docstring for the (deliberate, documented) isolation limitations of
    # this first pass.
    admin_database_url: str = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_ADMIN_DATABASE_URL", "")
    )

    # ingress.py: Caddy admin API base URL used to register/deregister real
    # preview routes. "caddy" is the compose DNS name of the platform's
    # single reverse-proxy container (deploy/compose/docker-compose.yml) — port
    # 2019 is Caddy's admin API, deliberately NOT published to the host or
    # the tunnel (see deploy/caddy/Caddyfile's `admin` global directive),
    # so this only ever resolves container-to-container. Unreachable here
    # (e.g. running app-builder standalone with no Caddy at all) is a
    # handled, logged fallback — see ingress.py's module docstring — not a
    # hard dependency.
    caddy_admin_url: str = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_CADDY_ADMIN_URL", "http://caddy:2019")
    )

    # ingress.py "subdomain" mode: the wildcard domain previews are minted
    # under, e.g. project "foo" -> https://foo.preview.example.com. Requires
    # a manual operator step (wildcard DNS + Cloudflare tunnel hostname) not
    # covered by this repo's code — see ingress.py's module docstring.
    preview_domain: str = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_PREVIEW_DOMAIN", "preview.localhost")
    )

    # ingress.py: "path" (default, zero extra ops work — rides the existing
    # :8899 listener) or "subdomain" (needs the wildcard-DNS step above).
    ingress_mode: str = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_INGRESS_MODE", "path")
    )

    # ingress.py "path" mode: the externally-reachable base URL to build
    # returned preview URLs from (http://localhost:8899 for local dev; the
    # real tunnel base, e.g. https://builder.example.com, in a real deployment —
    # deliberately settings-driven rather than hardcoded so this doesn't
    # bake "localhost" into a value a real deployment depends on, per this
    # repo's standing no-localhost-in-browser-facing-URLs rule).
    preview_path_base_url: str = field(
        default_factory=lambda: os.environ.get(
            "APP_BUILDER_PREVIEW_PATH_BASE_URL", "http://localhost:8899"
        )
    )

    # ------------------------------------------------------------------ #
    # bring-your-own-repo (docs/APP-BUILDER-BYO-REPO-CONTRACT-2026-07-30.md)

    # github_credentials.py: Fernet key encrypting each tenant's GitHub
    # Personal Access Token at rest. Generate with
    #   python -c "from cryptography.fernet import Fernet; \
    #              print(Fernet.generate_key().decode())"
    # and set APP_BUILDER_ENCRYPTION_KEY in deploy/compose/.env.
    #
    # DELIBERATELY NOT core's APP_ENCRYPTION_KEY. Different service,
    # different trust boundary — exactly the reasoning
    # `code_intel_internal_token` above already gives for being separate
    # from `internal_token`. A tenant's PAT can read and write their whole
    # repository; it must not be recoverable by anything holding core's key.
    #
    # Empty is a supported state: the service boots and every non-GitHub
    # route keeps working, but POST /projects/github/connect returns a clear
    # 503 rather than storing a token in the clear.
    app_builder_encryption_key: str = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_ENCRYPTION_KEY", "")
    )

    # preview.py / ingress.py — the OLD Docker-preview-container path. OFF by
    # default as of the BYO-repo pivot: build/preview now runs on the tenant's
    # own GitHub Actions, not on our host, because those preview containers
    # were becoming the dominant infra cost on a single shared box. The code
    # is retained (not deleted) so bare local dev with no connected GitHub
    # repo can still fall back to today's behaviour by setting this true;
    # production tenants never reach it.
    local_preview_enabled: bool = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_LOCAL_PREVIEW_ENABLED", "false").lower()
        == "true"
    )

    # github_client.py: GitHub REST API base. Settings-driven so tests can
    # point it at a local stub and a GitHub Enterprise Server deployment can
    # point it at https://ghe.example.com/api/v3 without a code change.
    github_api_url: str = field(
        default_factory=lambda: os.environ.get(
            "APP_BUILDER_GITHUB_API_URL", "https://api.github.com"
        )
    )
    github_timeout_seconds: int = field(
        default_factory=lambda: int(os.environ.get("APP_BUILDER_GITHUB_TIMEOUT", "30"))
    )

    # Branch namespace this service pushes under, so a tenant can tell our
    # branches apart from their own at a glance and protect or ignore them
    # with one pattern. Final branch is "<prefix>/<project_id>".
    github_branch_prefix: str = field(
        default_factory=lambda: os.environ.get("APP_BUILDER_GITHUB_BRANCH_PREFIX", "app-builder")
    )

    # import.py: caps on scanning an existing repo. Trees over these limits
    # are refused (413) with a hint to use `subdir`.
    import_max_files: int = field(
        default_factory=lambda: int(os.environ.get("APP_BUILDER_IMPORT_MAX_FILES", "5000"))
    )
    import_max_bytes: int = field(
        default_factory=lambda: int(
            os.environ.get("APP_BUILDER_IMPORT_MAX_BYTES", str(100 * 1024 * 1024))
        )
    )


SETTINGS = Settings()
