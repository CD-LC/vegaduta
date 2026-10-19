"""Environment-driven configuration for the code-intel microservice.

Mirrors the platform's conventions: everything injectable via env vars,
sane local-dev defaults, no secrets in files.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Settings:
    # Where per-tenant indexes and clones live. In docker-compose this is a
    # named volume; every tenant gets an isolated subtree underneath.
    data_dir: str = field(default_factory=lambda: os.environ.get("CODE_INTEL_DATA_DIR", "./data"))

    # Auth mode:
    #   keycloak - validate Bearer JWTs against the realm's JWKS and require
    #              a tenant_id claim (same semantics as core's
    #              TenantContextFilter, including the 403-on-missing-claim
    #              behavior).
    #   internal - trusted calls from core: X-Tenant-Id header + shared
    #              secret X-Internal-Token (constant-time compared against
    #              CODE_INTEL_INTERNAL_TOKEN). Use inside the compose network
    #              where core has already authenticated the user.
    #   none     - local dev only; tenant from X-Tenant-Id or "default".
    auth_mode: str = field(
        default_factory=lambda: os.environ.get("CODE_INTEL_AUTH_MODE", "internal")
    )

    internal_token: str = field(
        default_factory=lambda: os.environ.get("CODE_INTEL_INTERNAL_TOKEN", "")
    )

    # Keycloak (auth_mode=keycloak). Defaults match deploy/docker-compose.yml.
    #
    # keycloak_issuer is the value VALIDATED against the token's `iss` claim.
    # Under Keycloak's KC_HOSTNAME_STRICT (how this platform runs it), every
    # token - no matter where it's minted - carries the BROWSER-facing issuer
    # (http://localhost:8180/realms/...), so this MUST be that external URL, or
    # every browser token fails with "Invalid issuer". (This is exactly what
    # core validates against too - see application-security.yml's
    # KEYCLOAK_ISSUER_URI. A prior deploy set this to the internal
    # http://keycloak:8080/... and broke the web console's direct calls.)
    # No default (AGENTS.md rule 4: a token issuer is never hardcoded to
    # localhost): compose and Helm always set it, and with auth_mode=keycloak
    # an empty value makes every token request answer 503 instead of
    # silently trusting a dev issuer.
    keycloak_issuer: str = field(
        default_factory=lambda: os.environ.get("CODE_INTEL_KEYCLOAK_ISSUER", "")
    )
    # Extra issuers accepted on the token's `iss` (comma-separated): the
    # dual-issuer window of a domain migration, exactly core's
    # KEYCLOAK_ADDITIONAL_ISSUERS (AGENTS.md rule 7). The service-specific
    # name wins; the shared name is what docker-compose passes through.
    keycloak_additional_issuers: str = field(
        default_factory=lambda: os.environ.get(
            "CODE_INTEL_KEYCLOAK_ADDITIONAL_ISSUERS",
            os.environ.get("KEYCLOAK_ADDITIONAL_ISSUERS", ""),
        )
    )

    def keycloak_issuers(self) -> list[str]:
        """Every issuer a token may carry: the primary one plus the additional list."""
        raw = [self.keycloak_issuer, *self.keycloak_additional_issuers.split(",")]
        return [i.strip() for i in raw if i and i.strip()]

    # keycloak_jwks_url is where the signing keys are FETCHED from - a
    # DIFFERENT host from the issuer inside the compose network: `localhost`
    # here is this container, not Keycloak, so keys must come over the internal
    # service name (http://keycloak:8080/realms/.../protocol/openid-connect/
    # certs). Empty => derive it from keycloak_issuer (correct for single-host
    # dev where issuer and fetch host are the same). Mirrors Spring's separate
    # issuer-uri vs jwk-set-uri.
    keycloak_jwks_url: str = field(
        default_factory=lambda: os.environ.get("CODE_INTEL_KEYCLOAK_JWKS_URL", "")
    )
    keycloak_audience: str | None = field(
        default_factory=lambda: os.environ.get("CODE_INTEL_KEYCLOAK_AUDIENCE")
    )
    # Security audit 2026-09-18: allowlist of `azp` (authorized party = the
    # Keycloak client the token was MINTED for), comma-separated. Audience is
    # only verified when CODE_INTEL_KEYCLOAK_AUDIENCE is set - and it is unset
    # everywhere, because Keycloak access tokens carry aud="account" unless an
    # audience mapper is added - so without this check a validly-signed token
    # from ANY client in the realm was accepted here, including academy-webapp
    # (a separate product with its own user base). Default = the
    # first-party clients whose users legitimately reach this service:
    #   agentic-ai-web  - the console's /code-intel page (its session token,
    #                     direct or relayed through core's /api/code-intel)
    #   agentic-ai-ide  - the VS Code / JetBrains / Eclipse plugins
    #   agentic-ai-core - core's own client, for a relayed/service token
    # Deliberately NOT included: academy-webapp, agentic-ai-platform-admin
    # (tenant-less tokens, refused below anyway), agentic-ai-mobile and
    # agentic-ai-app (no code-intel surface today - add them here, by env var,
    # if one ships). Set to an empty string and EVERY bearer token is refused;
    # there is intentionally no "allow any" wildcard. The X-Internal-Token
    # path never reaches this check.
    keycloak_allowed_azp: str = field(
        default_factory=lambda: os.environ.get(
            "CODE_INTEL_KEYCLOAK_ALLOWED_AZP", "agentic-ai-web,agentic-ai-ide,agentic-ai-core"
        )
    )

    # Embedding defaults for newly registered repos (per-repo override via
    # the register payload). "hash" = no-ML smoke mode.
    default_embedder: str = field(
        default_factory=lambda: os.environ.get("CODE_INTEL_EMBEDDER", "fastembed")
    )
    default_embed_model: str = field(
        default_factory=lambda: os.environ.get("CODE_INTEL_EMBED_MODEL", "BAAI/bge-small-en-v1.5")
    )
    # Operator allow-list (comma-separated model names) for per-repo
    # embedder/embed_model overrides. Empty (default) = NO overrides: a
    # tenant-supplied model that is not the configured default is ignored (the
    # default is used), so a tenant cannot pin many large models in memory.
    # When set, only the listed models (plus the default) may be requested.
    allowed_embed_models: frozenset = field(
        default_factory=lambda: frozenset(
            m.strip()
            for m in os.environ.get("CODE_INTEL_ALLOWED_EMBED_MODELS", "").split(",")
            if m.strip()
        )
    )

    # Context scripts are arbitrary commands from repo config; in a shared
    # service they must be explicitly enabled (and ideally sandboxed).
    allow_context_scripts: bool = field(
        default_factory=lambda: os.environ.get("CODE_INTEL_ALLOW_CONTEXT_SCRIPTS", "false").lower()
        == "true"
    )

    git_timeout_seconds: int = field(
        default_factory=lambda: int(os.environ.get("CODE_INTEL_GIT_TIMEOUT", "300"))
    )

    # Heavy-job admission (index / clone / graph build run inline on a
    # threadpool). Process-wide and per-tenant concurrent caps; over the cap
    # a synchronous call gets HTTP 429 + Retry-After, a background=true index
    # waits (bounded) for a slot. Per-process: with N replicas the effective
    # cluster cap is N x these values. Defaults: 4 global, 2 per tenant (the
    # per-tenant cap must stay below the global one so one tenant can never
    # take every slot). Repo size / file-count / tenant disk
    # quota limits are read in gitops.py (CODE_INTEL_MAX_REPO_MB,
    # CODE_INTEL_MAX_REPO_FILES, CODE_INTEL_TENANT_QUOTA_MB; 0 disables; the tenant quota
    # is OFF unless that variable is set).
    max_heavy_jobs: int = field(default_factory=lambda: max(1, int(
        os.environ.get("CODE_INTEL_MAX_HEAVY_JOBS", "4"))))
    max_tenant_heavy_jobs: int = field(default_factory=lambda: max(1, int(
        os.environ.get("CODE_INTEL_MAX_TENANT_HEAVY_JOBS", "2"))))
    heavy_retry_after_seconds: int = field(default_factory=lambda: int(
        os.environ.get("CODE_INTEL_HEAVY_RETRY_AFTER", "30")))
    # Background indexes allowed to wait for a slot at once (each parks a
    # threadpool thread), and how long each waits before giving up.
    max_queued_heavy_jobs: int = field(default_factory=lambda: int(
        os.environ.get("CODE_INTEL_MAX_QUEUED_HEAVY_JOBS", "8")))
    heavy_queue_wait_seconds: int = field(default_factory=lambda: int(
        os.environ.get("CODE_INTEL_HEAVY_QUEUE_WAIT", "900")))

    # CORS: comma-separated allowed origins for browser callers (the web
    # module's /code-intel page calls this service directly with its
    # Keycloak token). Empty = no browser access (server-to-server only).
    cors_origins: str = field(default_factory=lambda: os.environ.get("CODE_INTEL_CORS_ORIGINS", ""))

    # Interactive API docs (Swagger UI /docs, ReDoc /redoc, /openapi.json).
    # Secure by DEFAULT-OFF: this service is reachable on a public subdomain
    # (codeintel.staging.example.com) and its OpenAPI schema enumerates every route,
    # payload shape, and auth header - reconnaissance material an unauthenticated
    # visitor should not get. When false (default), FastAPI is built with
    # docs_url/redoc_url/openapi_url all None, so all three return 404. Set
    # CODE_INTEL_EXPOSE_DOCS=true in a LOCAL dev env only if you want the
    # interactive docs back on 127.0.0.1:8090.
    expose_docs: bool = field(
        default_factory=lambda: os.environ.get("CODE_INTEL_EXPOSE_DOCS", "false").lower() == "true"
    )

    # Code execution (run_code / workspaces). Fail closed: execution is a
    # deliberate operator decision, not a default.
    allow_exec: bool = field(
        default_factory=lambda: os.environ.get("CODE_INTEL_ALLOW_EXEC", "false").lower() == "true"
    )
    exec_timeout_seconds: int = field(
        default_factory=lambda: int(os.environ.get("CODE_INTEL_EXEC_TIMEOUT", "300"))
    )

    # Execution workspaces (see executor.py) are scratch copies of a
    # tenant's repo used for the apply/run/diff rework loop. Nothing
    # forces a caller to ever delete one, so a background reaper (see
    # reaper.py, started from main.py) deletes any workspace older than
    # this many hours that isn't currently mid-execution. Governance/
    # disk-exhaustion guardrail, not a hard cap on rework duration.
    workspace_ttl_hours: int = field(
        default_factory=lambda: int(os.environ.get("CODE_INTEL_WORKSPACE_TTL_HOURS", "24"))
    )

    # Execution sandbox for run_command/start_process (see executor.py):
    #   local  - bare subprocess in this container/host (default; the
    #            existing engineer rework loop already accepts this for the
    #            single-org/self-hosted case, and it's the only mode
    #            start_process refuses outright).
    #   docker - one throwaway container per run, via the `docker` Python
    #            SDK (not the CLI - keeps behavior identical whether this
    #            service runs on Windows or Linux).
    #   e2b    - a real E2B (https://e2b.dev) cloud sandbox.
    # Required before this service can safely run arbitrary/generated code
    # for untrusted or consumer-facing callers (e.g. the app-builder
    # feature) rather than just the engineer-owned rework loop.
    run_mode: str = field(default_factory=lambda: os.environ.get("CODE_INTEL_RUN_MODE", "local"))

    # run_mode=docker: default image when a caller doesn't specify one via
    # run_command's/start_process's own `image` param.
    docker_image: str = field(
        default_factory=lambda: os.environ.get("CODE_INTEL_DOCKER_IMAGE", "node:22-bookworm")
    )

    # run_mode=docker: point docker-py at this daemon instead of the local
    # socket/env default - e.g. tcp://code-intel-docker:2375 for a
    # docker-in-docker sidecar (see docker-compose.code-intel.yml), so this
    # container never needs the host's docker.sock mounted into it. None
    # (default) = docker-py's own local socket/env resolution.
    docker_host: str | None = field(default_factory=lambda: os.environ.get("DOCKER_HOST") or None)

    # run_mode=docker container hardening (SVC-002). Each sandbox container
    # used to run as the image's default user (root) with Docker's default
    # capability set and a writable root filesystem. The defaults below are
    # the secure posture; each is individually overridable for an operator
    # whose images genuinely need something looser.
    #
    # docker_user: "uid:gid" the command runs as (numeric, so it works on
    # images with no /etc/passwd entry). The ONLY writable paths in the
    # container are the bind-mounted /workspace and the /tmp tmpfs, so
    # /workspace must be writable by this uid for builds that write there
    # (npm install, pytest caches, compiled output). Workspaces are created
    # by THIS service's own uid, hence the default:
    #   - "auto" (default) -> the container runs as this service process's
    #     own uid:gid - non-root (the image runs as `codeintel`) AND the owner
    #     of every workspace, so builds keep working with no chown. "auto"
    #     never yields root: it falls back to 65534:65534 (nobody) if this
    #     process is root or the platform has no getuid(), e.g. Windows.
    #   - an explicit "uid:gid" (e.g. 65534:65534) -> the operator must make
    #     the workspaces dir writable by that uid out-of-band.
    # Empty string = do not pass `user` (image default, usually root) - an
    # explicit, visible downgrade, never the default.
    docker_user: str = field(
        default_factory=lambda: os.environ.get("CODE_INTEL_DOCKER_USER", "auto")
    )
    # Mount the container's root filesystem read-only. Writes go to the
    # /workspace bind mount or the /tmp tmpfs below; HOME is pointed at
    # /tmp so npm/pip/etc. caches have somewhere to land.
    docker_read_only: bool = field(
        default_factory=lambda: os.environ.get("CODE_INTEL_DOCKER_READ_ONLY", "true").lower()
        == "true"
    )
    # Size of the /tmp tmpfs (RAM-backed; counts against the container's
    # memory limit). Empty = no /tmp tmpfs (only sensible with read_only
    # off).
    docker_tmpfs_size: str = field(
        default_factory=lambda: os.environ.get("CODE_INTEL_DOCKER_TMPFS_SIZE", "512m")
    )
    # /tmp keeps exec by default: node-gyp, pip source builds, cargo build
    # scripts and many test runners extract and execute helpers from /tmp,
    # and noexec there would break legitimate builds for little gain (the
    # workspace mount the command runs from is executable regardless).
    docker_tmpfs_exec: bool = field(
        default_factory=lambda: os.environ.get("CODE_INTEL_DOCKER_TMPFS_EXEC", "true").lower()
        == "true"
    )
    # Linux capabilities: drop everything (comma-separated list; "" = keep
    # Docker's default set). Nothing a build/test needs requires a cap once
    # it is not root and not binding ports < 1024.
    docker_cap_drop: str = field(
        default_factory=lambda: os.environ.get("CODE_INTEL_DOCKER_CAP_DROP", "ALL")
    )
    # no-new-privileges: setuid binaries in the image (su, sudo, ping, ...)
    # can't regain the privileges dropped above.
    docker_no_new_privileges: bool = field(
        default_factory=lambda: os.environ.get(
            "CODE_INTEL_DOCKER_NO_NEW_PRIVILEGES", "true"
        ).lower()
        == "true"
    )
    # Blast-radius caps (previously hard-coded in executor.py; same values).
    docker_mem_limit: str = field(
        default_factory=lambda: os.environ.get("CODE_INTEL_DOCKER_MEM_LIMIT", "1g")
    )
    docker_nano_cpus: int = field(
        default_factory=lambda: int(os.environ.get("CODE_INTEL_DOCKER_NANO_CPUS", "1000000000"))
    )  # 1 vCPU
    docker_pids_limit: int = field(
        default_factory=lambda: int(os.environ.get("CODE_INTEL_DOCKER_PIDS_LIMIT", "256"))
    )

    # run_mode=e2b: sandbox credentials. Required for e2b mode; executor.py
    # fails closed with a clear WorkspaceError (not a raw SDK exception) if
    # this is unset when e2b mode is selected.
    e2b_api_key: str | None = field(default_factory=lambda: os.environ.get("E2B_API_KEY") or None)
    e2b_template: str = field(
        default_factory=lambda: os.environ.get("CODE_INTEL_E2B_TEMPLATE", "base")
    )

    # SDLC agent loop (POST /repos/{repo_id}/agent/resolve): the default
    # planner is an Anthropic LLM. Only the PRESENCE of the key is recorded
    # here - the value is read straight from the environment by the anthropic
    # SDK, exactly as rl-trainer/app/settings.py does it, so it can never land
    # in a log line, an error body, or /health.
    #
    # OPTIONAL and currently unset everywhere: no deployment passes
    # ANTHROPIC_API_KEY to this service (not deploy/docker-compose.yml, not
    # infra/terraform/environments/azure-phase1/main.tf, not .env.example), and
    # `anthropic` is a commented-out optional line in requirements.txt, so it
    # is not in the image either. Both gaps are reported as an unavailable
    # capability on /health rather than blowing up mid-request - see
    # agent.agent_capabilities.
    agent_api_key_present: bool = field(
        default_factory=lambda: bool(os.environ.get("ANTHROPIC_API_KEY", "").strip())
    )


SETTINGS = Settings()
