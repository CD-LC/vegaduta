# app-builder

**No-code app builder** microservice for the agentic-ai-platform: a
non-technical consumer describes an app in plain language and gets a
running, iteratively-editable app with a live preview. Sibling of
`code-intel` (same directory level, repo root — NOT a subfolder of it),
built the same way: standalone, multi-tenant, deployed as its own
container.

```
consumer ──describe an app──▶ app-builder :8091
                                 │
                                 ├─ scaffold.py   template -> new project, git-init, register+index with code-intel
                                 ├─ generate.py    NL instruction -> {path,content} edits, applied via code-intel
                                 ├─ preview.py     start/stop a live dev-server process via code-intel, get a URL
                                 ├─ baas.py        optional per-project Postgres schema
                                 └─ data/tenants/<tenant>/projects/<project_id>/   the actual checkout, on THIS
                                                                                    service's own filesystem
                                 │
                                 ▼
                              code-intel :8090   (index/search/apply/run/workspaces — app-builder is a CLIENT,
                                                   it never vendors or duplicates code-intel's engine)
```

## Multi-tenancy (mirrors code-intel / the platform's model)

Directory-per-tenant storage: every project checkout and the project
registry live under `data/tenants/<tenant_id>/`, tenant and project ids
are validated against strict patterns (no path traversal — see
`app/tenancy.py`), and every request is scoped by the authenticated
tenant. A project's `project_id` doubles as its code-intel `repo_id`,
1:1, to keep the mental model simple — app-builder registers a project
with code-intel under `repo_id=project_id` and never renames it.

Auth modes (`APP_BUILDER_AUTH_MODE`) — identical semantics to
`code-intel/app/auth.py`:

| mode | who calls | mechanism |
|---|---|---|
| `internal` (default) | core, or any trusted server-to-server caller | `X-Tenant-Id` + `X-Internal-Token` shared secret (constant-time compare; fails closed if unset) |
| `keycloak` | anything holding a user JWT | Bearer token validated against the realm JWKS; `tenant_id` claim required |
| `none` | local dev only | `X-Tenant-Id` header |

app-builder is itself a CLIENT of code-intel: regardless of how a caller
authenticated to app-builder, every call app-builder makes TO code-intel
uses code-intel's own `internal` auth mode (`X-Tenant-Id` +
`X-Internal-Token`, forwarding the authenticated tenant) — see
`app/codeintel_client.py`.

## API

```
GET    /health                                       no auth; {status, auth_mode, github_ready, local_preview_enabled}
GET    /templates                                     [{template_id, name, description}]

GET    /projects/github/status                        {connected} | {connected, repo_full_name, default_branch,
                                                                    connected_by, connected_at, repo_url}
POST   /projects/github/connect                       {token, repo_full_name} -> same shape as /status
DELETE /projects/github/connect                       {disconnected}

POST   /projects                                      {project_id, template_id, prompt?}
GET    /projects
GET    /projects/{id}
DELETE /projects/{id}
POST   /projects/{id}/generate                        {instruction, context?, images?}
POST   /projects/{id}/generate/apply                  {instruction, files:[{path,content}]}  ← edge path
GET    /projects/{id}/files                           {files:[{path,content}]}  ← instant preview
GET    /projects/{id}/assets                          {assets:[{path,size}]}
POST   /projects/{id}/assets                          {name, content_base64}    → commits to assets/ on the branch
DELETE /projects/{id}/assets                          {path}
POST   /projects/{id}/backend/provision

POST   /projects/{id}/preview/start                    LEGACY — 409 unless APP_BUILDER_LOCAL_PREVIEW_ENABLED=true
POST   /projects/{id}/preview/stop                     LEGACY — same
GET    /projects/{id}/preview                          LEGACY — {active:false, enabled:false} when disabled
```

`token` on `POST /projects/github/connect` is **write-only**: it is
validated against GitHub's API, encrypted with `APP_BUILDER_ENCRYPTION_KEY`,
stored per tenant, and never returned by any route again — not by `/status`,
not on a project record, not in an error message.

Errors use the platform's `{timestamp, status, error}` JSON shape, same as
core and code-intel. Auth required (`Depends(tenant)`) on everything
except `/health`.

## Templates catalog

Five real, minimal, genuinely-runnable starters under `templates/`, each
with a `.appbuilder.json` manifest (`{name, install, run, build?, test?,
port, image}` — plus optional `preview_dir` and `workflow_extra`, below)
that scaffold.py/generate.py/preview.py all read from:

| template_id | stack | run |
|---|---|---|
| `node-react` | Vite + React (TypeScript) | `npm install && npm run dev -- --host 0.0.0.0 --port 3000` |
| `capacitor-app` | Vite + Capacitor — one codebase → web + Android APK + iOS | `npm install && npm run dev -- --host 0.0.0.0 --port 5173` |
| `astro-site` | Astro static site (blogs, marketing, docs) | `npm install && npm run dev -- --host 0.0.0.0 --port 4321` |
| `python-fastapi` | FastAPI, one `/` route | `pip install -r requirements.txt && uvicorn main:app --host 0.0.0.0 --port 8000` |
| `static-html` | plain HTML/CSS, no build step | `python -m http.server 8000` |

Optional manifest keys:

- `preview_dir` — build output that publishes to the repo's own GitHub
  Pages site via a `pages` job in the starter workflow (branch-gated to
  `app-builder/<id>`). At scaffold time the service POSTs
  `/repos/{}/pages` with `build_type: workflow`; a PAT without Pages:write
  soft-fails and the PR body explains the one manual step.
- `workflow_extra` — verbatim job-level YAML appended under `jobs:` in the
  starter workflow (`{branch}` is substituted). `capacitor-app` uses it
  for an Android `assembleDebug` job (ubuntu runner — free even on private
  repos) and an iOS simulator build (macos-latest, gated to public repos
  only — private repos bill macOS minutes at 10x).

Add a template by dropping a new directory with its own `.appbuilder.json`
under `templates/` — `scaffold.list_templates()` scans the directory, no
code change needed.

## The generate loop

`POST /projects/{id}/generate {instruction, context?, images?}` (see
`app/generate.py`). `context` is structured design notes composed
client-side (wireframe spec, extracted palette, SVG markup, asset names);
`images` are base64 design attachments (sketch photos, Figma exports)
attached to the completion as vision media — core retries text-only if the
resolved model rejects images.

```
retrieve a few relevant files          code-intel's own grounded search — never the whole repo in the prompt
  └─ ask core for {path,content}       POST /api/internal/app-builder/generate-files — same platform-pool/BYOK
                                        routing every other LLM call in this platform uses (2026-08-06 routing fix)
       └─ apply in a scratch workspace  code-intel POST .../workspaces + .../apply
            └─ run build/test          code-intel POST .../workspaces/{ws}/run, if the template has one
                 ├─ success -> promote the same files as ONE commit on the project's branch in
                 │             the TENANT's own GitHub repo, then re-index via code-intel
                 └─ failure -> ONE corrective retry (feed the structured failure back to the
                               model), then give up and report failure — v0, not a full
                               N-iteration rework loop
```

Unlike code-intel's own rework loop (which stops at a human-reviewable
diff — "the agent never merges"), app-builder owns each project
end-to-end: a successful `generate()` call promotes straight into the
real checkout with no human approval gate. That is the correct/expected
behavior here (there's no separate human owner of the checkout to hand a
diff back to), but every call is still audit-logged (instruction text +
diff, via `app/audit.py`) so there's a durable record of what the AI
changed and why.

## Bring-your-own-repo (2026-07-30) — where the code and the build live

See `docs/APP-BUILDER-BYO-REPO-CONTRACT-2026-07-30.md`. A tenant admin
connects a **fine-grained GitHub PAT** scoped to one repository (Contents:
read/write, Pull requests: read/write — nothing broader). From then on:

```
POST /projects  ->  create branch app-builder/<project_id> in THEIR repo
                    ONE commit: template + .github/workflows/app-builder.yml
                    register with code-intel as source_type="git"
                      (git_url = their repo, git_ref = the branch,
                       auth_token = their PAT, decrypted per-request)
                    optional generate()  ->  a second commit on that branch
                    open a PR against their default branch
```

**Build and preview run on the tenant's own GitHub Actions minutes, not on
this platform.** We start no container for it. The UI links out to the PR;
if the tenant has a deploy-preview provider wired up (Vercel, Netlify,
Cloudflare Pages), that provider's preview for the PR is the app running.
Nothing is written to this service's filesystem except its own metadata
(project registry, audit log, credential store) — a scaffold produces a
commit, not a directory.

Why a PAT and not a GitHub App: a GitHub App needs an interactive one-time
registration on github.com plus a new platform-level private key to operate
and rotate. Per-tenant PATs add **zero** new platform secrets beyond
`APP_BUILDER_ENCRYPTION_KEY` and fully replace the preview infra, which was
the point. A GitHub App is a valid Phase 2 if PAT UX proves to be real
friction.

Why the Git Data API and not `git clone && git push`: nothing touches our
disk at all (a clone writes the tenant's source tree and leaves it behind if
the process dies), N files land as ONE atomic commit (the plain Contents API
would be one commit per file), and the PAT only ever exists as a header on
one in-flight request — never in argv, a subprocess env, `.git/config`, or a
log. See `app/github_client.py`'s module docstring.

## Preview (LEGACY — off by default)

`app/preview.py`/`app/ingress.py` are retained but gated behind
`APP_BUILDER_LOCAL_PREVIEW_ENABLED` (default **false**), because running
preview containers here was the dominant infra cost this pivot exists to
remove. With the flag off, nothing in the create/generate flow can start a
container and the `/preview/*` routes answer 409. With it **on** and no
GitHub repo connected, `create_project()` falls back to the pre-pivot
local-checkout behaviour, so bare local dev still works with zero external
dependencies: `POST /projects/{id}/preview/start` then reads the project's
manifest, registers a code-intel workspace from the real checkout, starts
the install+run command as a long-running process, and returns a URL.

## BaaS (optional per-project Postgres)

`POST /projects/{id}/backend/provision` creates a Postgres schema
(`app_<project_id>`) in the platform's existing database and returns a
schema-scoped `DATABASE_URL` (via `options=-csearch_path=<schema>`).
Fully optional — a project scaffolds and previews with no database at
all; if `APP_BUILDER_ADMIN_DATABASE_URL` isn't configured,
`provision_backend()` returns a clear `{"provisioned": false, "reason":
...}` instead of crashing the service. See `app/baas.py`'s module
docstring for the isolation caveat (below).

## Run

```bash
# local dev
cd app-builder
pip install -r requirements.txt
APP_BUILDER_AUTH_MODE=none APP_BUILDER_CODE_INTEL_URL=http://localhost:8090 \
  uvicorn app.main:app --port 8091 --reload

# tests (no FastAPI/network/core/Postgres required — every
# codeintel_client + core generate-files + psycopg call is mocked)
python3 -m tests.test_projects
python3 -m tests.test_baas
python3 -m tests.test_scaffold          # the LEGACY local path
python3 -m tests.test_generate
python3 -m tests.test_ingress
python3 -m tests.test_github_credentials  # encrypted PAT store
python3 -m tests.test_github_routes       # the connect/status/disconnect API
python3 -m tests.test_byo_repo            # ACCEPTANCE GATE: create->generate->push->PR

# docker, alongside the existing stack + code-intel (deploy/ untouched)
echo "APP_BUILDER_INTERNAL_TOKEN=$(openssl rand -hex 24)" >> deploy/.env
docker compose -f deploy/docker-compose.yml \
               -f code-intel/docker-compose.code-intel.yml \
               -f app-builder/docker-compose.app-builder.yml \
               up -d --build app-builder
```

## Required environment variables

| var | required? | purpose |
|---|---|---|
| `APP_BUILDER_DATA_DIR` | no (default `./data`) | per-tenant project checkouts + registry |
| `APP_BUILDER_TEMPLATES_DIR` | no (default `./templates`) | starter-kit catalog |
| `APP_BUILDER_AUTH_MODE` | no (default `internal`) | `internal` \| `keycloak` \| `none` |
| `APP_BUILDER_INTERNAL_TOKEN` | yes, if `auth_mode=internal` | shared secret for callers OF app-builder |
| `APP_BUILDER_CODE_INTEL_URL` | no (default `http://localhost:8090`) | where code-intel is reachable |
| `APP_BUILDER_CODE_INTEL_TOKEN` | yes | the secret app-builder presents TO code-intel's own internal auth mode — must equal code-intel's `CODE_INTEL_INTERNAL_TOKEN` |
| `APP_BUILDER_CORE_BASE_URL` | no (default `http://localhost:8080`) | where core is reachable — `/generate` calls `POST {this}/api/internal/app-builder/generate-files`. Scaffolding/preview/backend-provision all work without it |
| `APP_BUILDER_CORE_SERVICE_TOKEN` | yes, for `/generate` only | the secret app-builder presents TO core's `X-Service-Token` check — must equal core's own `app.app-builder.service-token` (set via the SAME env var name on core's side) |
| `APP_BUILDER_CORE_TIMEOUT` | no (default `120` seconds) | httpx timeout for the generate-files call |
| `APP_BUILDER_ADMIN_DATABASE_URL` | no | BaaS is fully optional without it |
| `APP_BUILDER_CORS_ORIGINS` | no | browser access, only if a future console UI calls this service directly |
| `APP_BUILDER_ENCRYPTION_KEY` | yes, for GitHub connect | Fernet key encrypting each tenant's PAT at rest. Generate: `python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"`. **Never** reuse core's `APP_ENCRYPTION_KEY`. Unset ⇒ service boots fine but `/projects/github/connect` returns 503 and `/health` reports `github_ready: false` |
| `APP_BUILDER_LOCAL_PREVIEW_ENABLED` | no (default `false`) | `true` re-enables the legacy Docker-preview path + the local-checkout scaffold fallback. Leave false in any real deployment |
| `APP_BUILDER_GITHUB_API_URL` | no (default `https://api.github.com`) | override for GitHub Enterprise Server (`https://ghe.example/api/v3`) or tests |
| `APP_BUILDER_GITHUB_BRANCH_PREFIX` | no (default `app-builder`) | branches are pushed as `<prefix>/<project_id>` |
| `APP_BUILDER_GITHUB_TIMEOUT` | no (default `30`) | per-request GitHub API timeout, seconds |

Rotating `APP_BUILDER_ENCRYPTION_KEY` makes every stored PAT undecryptable;
tenants get a clear "disconnect and reconnect GitHub" message rather than a
500, but treat it like core's key and do not churn it.

## Known limitations in this pass

- **Ingress is a dev-only stub.** `app/ingress.py` returns a direct
  `http://host:port` URL, reachable only inside the compose network — NOT
  a public URL in a real deployment. A follow-up task wires this to
  Caddy's admin API for real `*.preview.<domain>` subdomain routing. Do
  not present preview URLs as production-ready until that lands.
- **BaaS has no per-project credential isolation yet.** Every project's
  generated backend code shares the SAME admin Postgres role — only the
  default `search_path` differs per project, there is no dedicated role,
  no row-level security, no privilege reduction. See `app/baas.py`'s
  module docstring. Named follow-up, not an oversight.
- **`generate.py` does one corrective retry, not a full N-iteration
  loop.** On a build/test failure it feeds the structured failure back to
  the model exactly once, then reports failure. This is v0 — a real
  rework loop (like code-intel's own SDLC agent, see
  `code-intel/app/agent.py`) is future work.
- **Depends on code-intel process endpoints landing in a parallel
  workstream.** `preview.py` and `codeintel_client.py`'s
  `create_process`/`get_process`/`delete_process` assume this exact
  contract (mirroring the existing one-shot `/run` route):
  ```
  POST   /repos/{repo_id}/workspaces/{ws_id}/processes
         body {command, run_mode?, image?, port?, env_overrides?}
         -> {process_id, run_mode, host?, port?, preview_url?, container_id?}
  GET    /repos/{repo_id}/workspaces/{ws_id}/processes/{process_id}
         -> {process_id, running, exit_code} + host/port or preview_url
  DELETE /repos/{repo_id}/workspaces/{ws_id}/processes/{process_id}
  ```
  These endpoints did not exist in `code-intel/app/main.py` as of this
  writing (only the one-shot `/run` route did) — this was built to the
  contract specified up front, without waiting for that workstream to
  land, per instruction. **If the landed shape differs**, the fix is
  contained entirely to three methods in `app/codeintel_client.py`
  (`create_process`/`get_process`/`delete_process`) plus their three
  call sites in `app/preview.py` (`start_preview`/`stop_preview`/
  `preview_status`) — nothing else in this service touches process
  response fields directly, so this should be a 5-minute fix, not a
  rewrite, once the real shape is known.
- **`code-intel` itself is read-only reference for this pass** — nothing
  under `code-intel/` was modified to build app-builder, exactly as
  scoped.
