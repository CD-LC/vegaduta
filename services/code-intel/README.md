# code-intel

Standalone, multi-tenant **code intelligence microservice** for the
agentic-ai-platform: incremental codebase indexing (RAG), semantic search,
and the deterministic AST-derived code graph — exposed over HTTP, isolated
per tenant, deployed like `sidecar` as its own container. **Nothing in
`core/` is modified or required to run it.**

```
core (Spring) ──X-Tenant-Id + X-Internal-Token──▶ code-intel :8090
                                                    │
                                                    ├─ data/tenants/<tenant>/repos/<repo>/
                                                    │    ├─ checkout/   (its own git clone)
                                                    │    └─ state/index.db  (vectors + graph)
                                                    └─ vendored codebase_rag pipeline
```

## Multi-tenancy (mirrors the platform's model)

The platform isolates tenants with schema-per-tenant Postgres; this service
mirrors that with **directory-per-tenant storage** — every clone, index and
graph lives under `data/tenants/<tenant_id>/`, tenant ids are validated
against a strict pattern (no path traversal), and every request is scoped
by the authenticated tenant. Two tenants can register the same `repo_id`
and never see each other's data (covered by tests).

Auth modes (`CODE_INTEL_AUTH_MODE`):

| mode | who calls | mechanism |
|---|---|---|
| `internal` (default) | core, over the compose network | `X-Tenant-Id` + `X-Internal-Token` shared secret (constant-time compare; fails closed if unset) |
| `keycloak` | anything holding a user JWT | Bearer token validated against the realm JWKS; `tenant_id` claim required — same semantics as core's `TenantContextFilter`, including 403 for tenant-less tokens |
| `none` | local dev only | `X-Tenant-Id` header |

Keycloak-mode settings: `CODE_INTEL_KEYCLOAK_ISSUER` (validated `iss`),
`CODE_INTEL_KEYCLOAK_JWKS_URL` (where keys are fetched; empty = derived from
the issuer), `CODE_INTEL_KEYCLOAK_AUDIENCE` (optional; `aud` is only verified
when set) and `CODE_INTEL_KEYCLOAK_ALLOWED_AZP` — a comma-separated allowlist
of the Keycloak **client** a token was minted for (its `azp` claim). Default
`agentic-ai-web,agentic-ai-ide,agentic-ai-core`. A token with a missing or
unlisted `azp` is a 401, so a token issued to another client in the same realm
(e.g. `academy-webapp`) is refused even though its signature and issuer are
valid (security audit 2026-09-18). There is no wildcard; an empty value refuses
every bearer token. `X-Internal-Token` calls are not subject to it.

## API

```
GET  /health
POST /repos                          {repo_id, source_type: local|git, path|git_url, git_ref?, auth_env?, embedder?, embed_model?}
GET  /repos
DEL  /repos/{repo_id}
POST /repos/{repo_id}/index          {full?: bool, background?: bool}   409 if already running
GET  /repos/{repo_id}/status
POST /repos/{repo_id}/search         {query, top_k?, path_prefix?}
POST /repos/{repo_id}/graph/build
GET  /repos/{repo_id}/graph/callers/{symbol}
GET  /repos/{repo_id}/graph/callees/{symbol}
GET  /repos/{repo_id}/graph/impact/{symbol}?depth=3
GET  /repos/{repo_id}/graph/stats

# code execution / rework loop (403 unless CODE_INTEL_ALLOW_EXEC=true)
POST /repos/{repo_id}/workspaces                     -> {workspace_id}
POST /repos/{repo_id}/workspaces/{ws}/apply          {files:[{path,content}|{path,delete}]}
POST /repos/{repo_id}/workspaces/{ws}/run            {command, timeout?, attempts?}
GET  /repos/{repo_id}/workspaces/{ws}/diff
DEL  /repos/{repo_id}/workspaces/{ws}

# long-running processes (live-preview dev servers) - same 403 gate as
# above, PLUS requires CODE_INTEL_RUN_MODE=docker|e2b (local refuses)
POST /repos/{repo_id}/workspaces/{ws}/processes                 {command, run_mode?, image?, port?, env_overrides?} -> {process_id, run_mode, host|preview_url, port|sandbox_id}
GET  /repos/{repo_id}/workspaces/{ws}/processes/{process_id}
DEL  /repos/{repo_id}/workspaces/{ws}/processes/{process_id}
```

Errors use the platform's `{timestamp, status, error}` JSON shape.

## Code execution with automatic rework

The industry pattern for agentic code fixes (SWE-agent / Reflexion) is a
loop against an **isolated workspace**:

```
create_workspace                     # git worktree of the repo; real checkout untouched
  └─ run_code "mvn test"             # -> {success:false, final.stderr, rework_hint}
       └─ apply_files [corrected]    # the agent writes fixed full-file contents
            └─ run_code "mvn test"   # rerun; repeat until success
                 └─ workspace_diff   # unified patch -> becomes a PR
```

`run_code` returns **structured failure** (exit code, stdout/stderr tails,
timing) plus a `rework_hint` that literally instructs the model to fix and
rerun — so any competent agent drives the loop, and the platform's existing
Reflexion / tool-verifier machinery reinforces it. `attempts>1` reruns
**unchanged** code to absorb flaky tests (reported as `flaky:true`), which
is deliberately separate from rework (changed code).

Safety: execution is **off by default** (`CODE_INTEL_ALLOW_EXEC`), every run
is in an isolated workspace under the tenant's subtree, the primary checkout
and index are never mutated, env is scrubbed to an allowlist (no service
secrets reach tenant code), output and timeout are capped, and workspace
paths are escape-checked. For untrusted multi-tenant production, run this
container with execution off and instead front `run_code` with a sandbox
(docker-per-run, or the platform's existing E2B credentials).

## Execution sandbox: RUN_MODE (docker / e2b)

`CODE_INTEL_ALLOW_EXEC` gates *whether* execution happens at all; `CODE_INTEL_RUN_MODE`
(`app/executor.py` + `app/settings.py`) picks *where* it happens once allowed:

| `CODE_INTEL_RUN_MODE` | where commands run | notes |
|---|---|---|
| `local` (default) | bare subprocess in this container/host | fine for the engineer-owned rework loop on a self-hosted/single-org deployment; **`start_process` (below) refuses to run at all under `local`** |
| `docker` | one throwaway container per run, via the `docker` Python SDK (not the CLI - identical behavior on Windows dev hosts and Linux containers) | `--network none --memory=1g --cpus=1 --pids-limit=256`, workspace bind-mounted `rw`, removed after every run; needs a reachable Docker daemon - see `docker-compose.code-intel.yml` for the host-socket-vs-dind-sidecar tradeoff |
| `e2b` | a real [E2B](https://e2b.dev) cloud sandbox | needs `E2B_API_KEY`; no local Docker daemon required at all |

`run_command`'s and `start_process`'s result/response shape is identical across all three
modes - callers never need to know which one ran. Per-call `run_mode`/`image` params
override the service-wide default for that one call.

This is what makes execution safe to open up beyond the engineer-owned rework loop to a
**consumer-facing "app builder"**: `local` mode is a bare subprocess on the service's own
host, appropriate only where the operator already accepts running tenant code there
(today's default posture); `docker`/`e2b` are what make running arbitrary/generated code
from untrusted or consumer-facing callers safe.

### Long-running processes (live-preview dev servers)

`run_command` is one-shot (test/build commands that exit). A consumer-facing app builder
also needs to **start and keep running** something like `npm run dev` and get back a
preview URL - that's what `start_process`/`stop_process`/`process_status` are for
(`docker`/`e2b` only; `local` raises):

```
create_workspace
  └─ apply_files [generated app source]
       └─ process_start "npm run dev", port: 3000
            -> docker:  {process_id, host, port}       (ephemeral host port, docker-assigned)
            -> e2b:     {process_id, preview_url}       (E2B's built-in per-port HTTPS preview)
       └─ process_status {process_id}  -> {running, exit_code}
       └─ process_stop {process_id}
```

Same guardrail path as `run_code`: gated by `policy.evaluate_command` (a long-running
process is treated as at least as privileged as a one-shot command), approval-escalated
under `test_gated`/`read_only` policy modes exactly like `workspace_run`, and audited
(`process_start_*`/`process_started`/`process_stopped` event kinds).

Process tracking is **in-memory per service instance** (mirrors `_BUSY_WORKSPACES`) - a
service restart orphans the tracking record, though not the container/sandbox itself.
Docker containers are labeled `codeintel.ws_id=<ws_id>` / `codeintel.process_id=<id>`
specifically so an operator can find and clean up anything orphaned this way by label
(container cleanup by label is still manual - the reaper below covers on-disk
workspaces only, not runaway containers/sandboxes).

### Workspace TTL reaper

Stale **on-disk workspaces** don't accumulate until an operator hand-cleans them:
`app/reaper.py` runs as an asyncio background task started from `main.py`'s lifespan -
one sweep at startup, then every 15 minutes. Each sweep walks the per-tenant workspace
layout and deletes, via the same `executor.delete_workspace()` path callers use, any
workspace older than `CODE_INTEL_WORKSPACE_TTL_HOURS` (default `24`) that isn't
currently mid-execution (`executor.is_busy`). Every deletion is logged and recorded as
a `workspace_reaped` audit event, and a sweep deletes at most 50 workspaces so a reaper
bug can't wipe everything in one pass (the remainder is caught next sweep).

**Verification status:** the docker/e2b branches are unit-tested against mocked
`docker`/`e2b` SDKs only (`tests/test_run_modes.py`) - this repo's dev environment has
neither a Docker daemon nor E2B credentials available. Treat both as needing a real
end-to-end pass on a machine with Docker installed (and, separately, a real E2B API key)
before relying on them in production.

## Plugging into the platform's agents (no core changes)

`app/mcp_bridge.py` is a stdlib-only **MCP stdio server** — the exact
transport the platform's `DynamicMcpConnectionManager` already speaks. Register
it per tenant in the MCP registry like any catalog server:

```yaml
command: python3
args:    ["-m", "app.mcp_bridge"]      # cwd: the code-intel directory
env:
  CODE_INTEL_URL: http://code-intel:8090
  CODE_INTEL_INTERNAL_TOKEN: <shared secret>
  CODE_INTEL_TENANT: <tenant schema id>     # core injects TenantContext.tenantId
  CODE_INTEL_REPO: <default repo_id>        # optional
```

It exposes `search_code`, `code_callers`, `code_callees`, `code_impact`,
`create_workspace`, `apply_files`, `run_code`, `workspace_diff`. The agent
sees the rework loop spelled out in the tool descriptions and drives it
itself — no orchestration code in core. (`CODE_INTEL_DIRECT=1` makes the
bridge call the service in-process, used by the tests.)

**Read-only by default over the registry path.** When registered in the
platform's MCP registry (the HTTP backend, reachable by arbitrary agents),
the bridge exposes **only** the four read tools (`search_code`,
`code_callers`, `code_callees`, `code_impact`) unless
`CODE_INTEL_MCP_READONLY=false`. This is deliberate and load-bearing: the
bridge reaches the service over the *same* shared `X-Internal-Token` core
uses, and the service now treats any internal-token caller as
platform-pre-approved (see "Two approval systems" below), so the only safe
default is to keep the write/execute loop off this path entirely — code
writes belong on core's approval-gated `apply_code_changes` /
`run_in_code_workspace` tools. `CODE_INTEL_DIRECT=1` (single-box dev/tests)
defaults to full tools. There's a ready-to-use catalog entry for this in
core's MCP gallery (`McpCatalogService`, key `code-intel`).

### Two ways core talks to this service (both wired)

1. **Native Java tools** (`core/codeintel`): every agent gets the five
   read-only code-intel tools when `code-intel.enabled=true`, and the five
   approval-gated write/execute tools when its agent is code-workspace-enabled
   with a repo grant. These are also usable as **workflow steps** via the
   workflow designer's generic **Tool / MCP Call** node — pick a
   code-intel-enabled agent and the tool by name; no dedicated node type is
   needed (the workflow engine reuses the same `AgentToolProvider` catalog).
2. **The web console** (`web/app/code-intel`) now talks to the service
   **through** core's `/api/code-intel` proxy (`CodeIntelController`) by
   default, so a human-initiated `apply`/`run` from the Execute tab is filed
   on the platform's one central Approvals screen — the same request type an
   agent's tool call creates. Set `NEXT_PUBLIC_CODE_INTEL_URL` to point the
   page straight at the service instead (direct mode, bypasses that routing).

### Two approval systems, reconciled

The platform (core/approval) and this service (`policy.py`) each gate
writes/execution independently — defense in depth. To stop a
platform-approved action from *dead-ending* on this service's own second,
independent approval, an **internal-token caller is treated as trusted /
pre-approved** here (it is core, which already ran its admin-gated approval
before forwarding). Hard denies, blast-radius caps, protected paths and the
injection canary still fire for that caller — only the redundant second
*human* ASK is short-circuited. A real browser user (no internal token)
still escalates exactly as before, and a non-admin still cannot self-resolve
an apply/run approval. See `code-intel-approval-reconciliation/`.

### Example (internal mode)

```bash
H='-H "X-Tenant-Id: acme" -H "X-Internal-Token: $CODE_INTEL_INTERNAL_TOKEN" -H "Content-Type: application/json"'

curl -s -X POST localhost:8090/repos $H -d '{
  "repo_id": "billing-api",
  "source_type": "git",
  "git_url": "https://github.com/acme/billing-api.git",
  "auth_env": "GITHUB_TOKEN_TENANT_ACME"
}'
curl -s -X POST localhost:8090/repos/billing-api/index $H -d '{}'
curl -s -X POST localhost:8090/repos/billing-api/search $H \
  -d '{"query": "where do we validate webhook signatures", "top_k": 5}'
curl -s localhost:8090/repos/billing-api/graph/impact/ChargeService?depth=3 $H
```

## Git authentication

The registry stores `auth_env` — the **name** of an environment variable
holding the token; the operator sets that variable on the container.
Tokens are injected per git invocation via an ephemeral credential helper:
never written to the clone's config, the registry, or logs. Public repos
and pre-mounted local paths need no credentials at all.

## Design decisions

- **Index state lives outside the checkout** (`state/`, not
  `<checkout>/.coderag/`), so `git reset --hard` during sync never wipes
  the index, and local-source repos don't get files dropped into them.
- **Per-repo in-process locks**; concurrent index of the same repo → 409.
  Hence one uvicorn worker per container — scale by sharding repos across
  containers.
- **Repo `.coderag.yml` is honored for chunking knobs but
  `context_scripts` are stripped** unless
  `CODE_INTEL_ALLOW_CONTEXT_SCRIPTS=true`: in a shared service they are
  arbitrary command execution and belong in a sandbox first.
- **Vendored `codebase_rag`** (copied from `tools/codebase-rag`) keeps the
  service self-contained; the CLI tool remains usable independently.

## Run

```bash
# local dev
cd code-intel
pip install -r requirements.txt
CODE_INTEL_AUTH_MODE=none uvicorn app.main:app --port 8090

# tests (no FastAPI required - service + executor + MCP bridge directly)
python3 tests/test_service.py
python3 tests/test_exec_and_mcp.py
python3 tests/test_run_modes.py   # RUN_MODE dispatch: local regression + docker/e2b via mocked SDKs

# docker, alongside the existing stack (deploy/ untouched)
echo "CODE_INTEL_INTERNAL_TOKEN=$(openssl rand -hex 24)" >> deploy/.env
docker compose -f deploy/docker-compose.yml \
               -f code-intel/docker-compose.code-intel.yml up -d --build code-intel
```


