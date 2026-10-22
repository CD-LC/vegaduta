# Installation

VegaDūta has four install tiers: **Personal** (one person, one machine; the easiest, see [Personal mode](personal-mode.md) and the Setup app in [installer/README.md](../installer/README.md), which needs no terminal and installs Docker and a free local model for you), Lite, Full and Kubernetes. All of them run the **same container images**
(`ghcr.io/cd-lc/<component>`), so you can start small and move up without
changing code.

| | Lite | Full | Kubernetes |
|---|---|---|---|
| Use for | trying it, local development, a single user | self-hosting on one server | production, high availability |
| Runs | postgres, valkey, keycloak (dev mode), core, web; `rag` profile adds the sidecar | Lite plus PgBouncer, SeaweedFS (S3), Keycloak database, sidecar; opt-in profiles for everything else | umbrella chart `vegaduta`: core API (scalable), migrate job, jobs, optional worker, every optional service |
| Memory | ~6 GB for Docker | 12 GB core set, 24 GB+ with all profiles | per values |
| Command | `./scripts/vegaduta up --lite` | `./scripts/vegaduta up --full [--profile ...]` | `helm install ...` |
| Details | [deploy/README.md](../deploy/README.md) | [deploy/README.md](../deploy/README.md) | [deploy/helm/README.md](../deploy/helm/README.md) |

## Images and verification

Release images are multi-arch (`linux/amd64`, `linux/arm64`) and published as
`ghcr.io/cd-lc/<name>:<version>`:

`core`, `core-full` (core with Chromium for browser tools), `core-worker`,
`core-jobs`, `web`, `sidecar`, `code-intel`, `app-builder`, `tool-sandbox`,
`mcp-stdio-runner`, `mcp-stdio-runner-browser`, `workspace-runner`,
`browser-runner`, `voice-agent`, `rl-trainer`,
`postgres` (PostgreSQL 16 with pgvector and Apache AGE; also tagged `16` for
Compose, and `16-pgvector-age` for the CloudNativePG-based variant the Helm
`postgres` chart uses), and `omnibus` — Postgres, a Redis-protocol store,
core and the console in **one** container for the smallest possible install:

```sh
docker run -d --name vegaduta -v vegaduta-data:/data \
  -p 3000:3000 -p 8080:8080 ghcr.io/cd-lc/omnibus:<version>
```

All data and the generated secrets live under `/data`. The omnibus is a
personal-tier packaging, not a second product; the tradeoffs are in
[Personal mode](personal-mode.md#one-container-the-omnibus-image).

Each image is signed with cosign (keyless) and carries an SPDX SBOM and SLSA
build provenance as attestations:

```sh
cosign verify ghcr.io/cd-lc/core:<version> \
  --certificate-identity-regexp '^https://github.com/CD-LC/vegaduta/\.github/workflows/release\.yml@refs/tags/v' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
gh attestation verify oci://ghcr.io/cd-lc/core:<version> --repo CD-LC/vegaduta
```

Compose images have **no `latest` default**: every image tag comes from
`VEGADUTA_VERSION` in `deploy/compose/.env`, which a release tarball ships
already set to its own version, so compose files, realm and images always
belong to one release. In a git checkout with no version set, `up` and
`personal` look up the latest published release and write it into `.env`; use
`--build` to build the images instead (they are then tagged `local`). Pin the
chart version (Helm) the same way.

### Verifying the installer download

`install.sh` wraps everything in a `main` function (a truncated `curl | sh`
runs nothing), installs a pinned release (the one it shipped with, else the
latest release, printed before download), and verifies the tarball against the
release's `SHA256SUMS` before unpacking. When `cosign` is installed it also
verifies the keyless Sigstore signature of `SHA256SUMS`
(`SHA256SUMS.sigstore.json`, identity
`https://github.com/CD-LC/vegaduta/.github/workflows/release.yml@refs/tags/v*`);
`VEGADUTA_REQUIRE_SIGNATURE=1` makes that check mandatory. To verify by hand:

```sh
cosign verify-blob SHA256SUMS --bundle SHA256SUMS.sigstore.json   --certificate-identity-regexp '^https://github\.com/CD-LC/vegaduta/\.github/workflows/release\.yml@refs/tags/v'   --certificate-oidc-issuer https://token.actions.githubusercontent.com
sha256sum -c SHA256SUMS --ignore-missing
```

## Lite

See [getting-started.md](getting-started.md) for the walkthrough.

```sh
./scripts/vegaduta up --lite                 # signed release images (the latest release unless .env pins one)
./scripts/vegaduta up --lite --build         # build from this checkout
./scripts/vegaduta up --lite --profile rag   # add the ML sidecar for document ingestion
./scripts/vegaduta up --lite --local-ai      # add a free local model (Ollama), see local-models.md
```

Other Lite add-ons: `--profile tools` (a runner for STDIO MCP connectors),
`--profile workspace` (the Assistant's build terminal and projects folder),
`--search` (private web search with SearXNG) and `--browser` (the Assistant's
browser).

What Lite leaves out on purpose: Kafka events, Temporal, Langfuse tracing,
LiveKit voice, S3 (files go to a Docker volume), the admin console, and more
than one core replica. Moving to Full keeps the Postgres data if you carry it
over with `pg_dumpall`; Keycloak users and local files need an export, see
"Lite to full" in [deploy/README.md](../deploy/README.md).

## Full (Docker Compose)

```sh
./scripts/vegaduta up --full
./scripts/vegaduta up --full --profile temporal --profile observability
```

Profiles add optional services:

| Profile | Adds |
|---|---|
| `events` | Redpanda (Kafka API) for workflow events |
| `temporal` | Temporal, its database and UI, `core-worker`; switches workflows to the Temporal engine |
| `jobs` | `core-jobs`: scheduled and background work moved out of the API process |
| `observability` | Prometheus, Grafana, Loki, Promtail, Alertmanager |
| `tracing` | Langfuse (OpenTelemetry traces of agent runs) |
| `voice` | LiveKit and the voice agent |
| `rl` | RL trainer for tool/model selection |
| `code-intel` | code intelligence service with its private Docker executor (privileged dind, reachable only from code-intel on an internal network) |
| `builder` | app builder (implies `code-intel`) |
| `sandbox` | tool sandbox and MCP stdio runner for untrusted code |
| `tools` | MCP stdio runner only (STDIO MCP connectors) |
| `browser` | MCP stdio runner with a browser image (Chromium) for browser tools |

Full keeps chat history in Postgres by default (`APP_CHAT_BRANCHING_ENABLED=true`).

The CLI sets the matching core settings for you (for example
`WORKFLOW_EXECUTION_ENGINE=temporal` with `temporal`). With raw
`docker compose`, set them yourself; the table in
[deploy/README.md](../deploy/README.md) lists them.

### On a server with a domain

```sh
./scripts/vegaduta up --full --domain example.com
```

This writes `BASE_DOMAIN` and the `PUBLIC_*` URLs into `deploy/compose/.env`
and adds the Caddy overlay (`docker-compose.domain.yml`), which obtains
Let's Encrypt certificates for `app.`, `admin.`, `api.` and `auth.` on your
domain. Point those DNS names at the server and open ports 80 and 443.

On the domain overlay Keycloak always runs in production mode (`start`),
behind Caddy, and `/admin` plus the master realm are not published (reach the
admin console with `ssh -L 8180:127.0.0.1:8180 <server>`). `doctor` refuses
`KEYCLOAK_START_COMMAND=start-dev` together with `BASE_DOMAIN`.

Two rules prevent most sign-in problems:

- **No `localhost` in a browser-facing URL on a server.** Other users'
  browsers would call their own machines. `doctor` checks this.
- **The token issuer must match.** Keycloak stamps tokens with the public auth
  URL; core accepts that issuer plus anything listed in
  `KEYCLOAK_ADDITIONAL_ISSUERS`. When you change the domain, add the old
  issuer there during the switch.

### Object storage

Full ships SeaweedFS as an S3 gateway. To use AWS S3, Cloudflare R2, Ceph,
Garage or any S3 API, set `STORAGE_ENDPOINT`, `STORAGE_ACCESS_KEY`,
`STORAGE_SECRET_KEY`, `STORAGE_REGION` and `STORAGE_PATH_STYLE_ACCESS`.
For AWS S3 without stored keys set `STORAGE_CREDENTIALS=default`; for Azure Blob Storage
(no S3 API) set `APP_STORAGE_TYPE=azure-blob`. Both are described in
[configuration.md](configuration.md) and in the
[cloud portability ADR](architecture/ADR-cloud-portability.md), which also states what
has and has not been run against a real cloud account.

### Database login

core never connects as the PostgreSQL superuser. The one-shot `db-roles`
service (runs on every `up`, idempotent; `deploy/postgres/ensure-app-role.sh`)
creates `POSTGRES_APP_USER` (default `vegaduta_app`, password
`POSTGRES_APP_PASSWORD`, generated by `secrets`) as the **owner** of the
application database without superuser rights, installs `vector` and `age`
(which a non-superuser cannot create), grants the Apache AGE catalog, and on
an existing install hands the tables, sequences, views, functions and types
the superuser created to that role. `POSTGRES_USER` stays the bootstrap
superuser for `db-roles`, backups and manual `psql`. PostgreSQL preloads `age`
(`shared_preload_libraries`), so the app role never needs the superuser-only
`LOAD 'age'`. Setting `POSTGRES_APP_USER` equal to `POSTGRES_USER` opts out
(not recommended; `doctor` warns).

When the database volume was made by an earlier install with a different
password, `db-roles` fails with "password authentication failed". `up` and
`personal` notice this, set the database to this install's password (the data
is kept) and start again.

### Where the secrets are kept

`secrets` (run by every `up`) writes every secret the compose files require
into `deploy/compose/.env`, and before starting asks Docker Compose
(`docker compose config`) whether a required value is still missing. It also
keeps a copy of the secrets outside the install folder, one per user:
`~/.config/vegaduta/secrets.env` (`%LOCALAPPDATA%\VegaDuta\secrets.env` on
Windows and WSL). A new install folder reuses them, so it can still read the
data Docker kept. Back this file up with `.env`: it holds
`APP_ENCRYPTION_KEY`.

### Backup, restore, upgrade

```sh
./scripts/vegaduta backup                    # deploy/compose/backups/<timestamp>/ (owner-only)
./scripts/vegaduta restore deploy/compose/backups/<timestamp>
./scripts/vegaduta upgrade --version 1.2.0   # backup first, verify + fetch the release files, pull, recreate, wait
```

`backup` writes a `pg_dump` of the application database (and, on Full, the
Keycloak database), a tar of the uploaded-files volume, and a copy of `.env`
(it holds `APP_ENCRYPTION_KEY`: without it the database dump is useless).
`upgrade` refuses to run when the backup fails (`--no-backup` skips it), and on
a tarball install downloads the target release's compose files after checking
them against `SHA256SUMS` (plus the Sigstore bundle when `cosign` is
installed). Database migrations are forward-only: to roll back, run
`upgrade --version <previous> --no-backup` and then `restore` the pre-upgrade
backup; `upgrade` prints both commands when the new version does not become
healthy. On Windows, `scripts\vegaduta.ps1 upgrade -Version X.Y.Z` does the same (the tarball is unpacked with the `tar` of Windows 10 1803 or later, and the rest of the upgrade runs with the new release's script); in a git checkout it warns and you `git checkout vX.Y.Z` yourself. `.env`, its `.bak.*` copies and backups are always written with mode
600 (an explicit owner-only ACL on Windows).

## Kubernetes (Helm)

Charts are published as OCI artifacts at `oci://ghcr.io/cd-lc/charts` by the release workflow,
with the same version as the release. To install from a checkout instead, first build the chart dependencies as described in [deploy/helm/README.md](../deploy/helm/README.md). The
umbrella chart `vegaduta` installs the platform; each component is also
available as its own chart.

```sh
helm install vegaduta oci://ghcr.io/cd-lc/charts/vegaduta \
  --version <version> \
  --namespace vegaduta --create-namespace \
  --set global.domain=example.com
```

- **Dependencies**: Postgres (bundled as a CloudNativePG cluster, which needs
  the CloudNativePG operator), Valkey, Keycloak and SeaweedFS are bundled by
  default. To bring your own, set the `global.<dependency>` endpoint and
  disable the bundled chart (`postgres.enabled=false`, ...); the chart refuses
  to render when the two disagree.
- **Shapes**: the core API runs as a Deployment you can scale; a migrate Job
  runs schema migrations (`pre-install,pre-upgrade` with an external database;
  `post-install,pre-upgrade` with the bundled one, which the release itself
  creates - the API runs the control-plane migration on first boot, so
  `helm install --wait` does not deadlock); `core-jobs` runs as a single
  replica; `core-worker` runs only when Temporal is configured.
- **GitOps (Argo CD, Flux, `helm template`)**: these render without cluster
  access, so the chart cannot `lookup` generated secrets and would mint a new
  `APP_ENCRYPTION_KEY` on every sync. Such renders **fail** unless every secret
  comes from an `existingSecret` (`global.core.appSecret.existingSecret`,
  `global.internalSecret.existingSecret`, the bundled dependencies'
  `existingSecret`s) - create them with ExternalSecrets, SealedSecrets or SOPS.
  `global.allowGeneratedSecrets=true` exists for CI renders and throwaway
  clusters only. Argo CD sync waves are set: generated Secrets `-2`, the
  CloudNativePG cluster `-1`, the migrate Job (a Sync hook) `0`, the core
  family `1`.
- **NetworkPolicies are on by default**: a namespace default-deny plus
  per-component allows (needs an enforcing CNI such as Calico or Cilium). The
  core family may reach DNS, public 443, the release's own services, and the
  port of every configured integration (external PostgreSQL/Valkey/S3, OTLP,
  Kafka, Temporal, LiveKit, OIDC internal URL). Open more with
  `global.networkPolicy.coreEgress` (`smtp`, `otlp`, `ports` such as 11434 for
  Ollama, `cidrs`) or `global.networkPolicy.extraEgress` rules, which can be
  limited to some components with `components: [...]`. With Gateway API set
  `global.networkPolicy.gatewayNamespace`; with ServiceMonitors set
  `monitoringNamespace` (the chart fails with a clear message otherwise).
- **Keycloak**: only `/realms/<realm>` and `/resources` are routed on
  `auth.<domain>`; the admin console and the master realm are not public (serve
  them on a private host with `keycloak.admin.consoleUrl`).
- **TLS**: set `global.tls.clusterIssuer` (cert-manager) or
  `global.tls.secretName`; otherwise the release notes warn that the ingress
  controller will serve its default certificate.
- **Untrusted code**: `tool-sandbox` and `mcp-stdio-runner` require a
  VM-isolating RuntimeClass (Kata Containers or gVisor) unless you explicitly
  set `requireRuntimeClass=false` for evaluation.
- **Chat history**: `core.branchingEnabled` (default `true`) stores each chat
  message in Postgres for 30 days, so conversations show on any device. Set it
  to `false` to keep them only in Valkey for 24 hours.
- **Local cluster**: `deploy/kind/` has a kind configuration and values for
  trying the chart on a laptop.

`deploy/helm/validate.sh` (Docker only) lints and renders every chart and runs
kubeconform. See [deploy/helm/README.md](../deploy/helm/README.md) for values,
scenarios and install order.

## Building images yourself

```sh
make images                               # Full-tier images via the compose build overlay
docker build -f core/Dockerfile --build-arg MODULE=app-worker -t core-worker:dev core
```

`core/Dockerfile` builds all core shapes: `MODULE=app` (API, default),
`app-jobs` or `app-worker`.
