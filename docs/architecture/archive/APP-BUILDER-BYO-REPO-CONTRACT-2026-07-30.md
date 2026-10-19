# App Builder pivot: bring-your-own-repo (2026-07-30)

Replaces the Docker-scaffold-and-preview model with: Claude writes code on a
branch in the **tenant's own GitHub repo**, opens a PR, and preview/build runs
on **the tenant's own GitHub Actions** — not ours. This is a deliberate
*reduction* in what we run, done because our Docker preview containers were
becoming the dominant infra cost as usage grows, on a single shared 8vCPU/
11.68GB host running ~20 other services.

Read this fully. It supersedes app-builder's local-scaffold behavior described
in `scaffold.py`'s old docstring; do not re-introduce a Docker preview path.

## Why PAT, not a GitHub App (explicit scope decision)

A full GitHub App (OAuth, webhooks, installation tokens) is the more polished
long-term answer, but it requires an interactive one-time registration on
github.com by the platform owner (create the app, generate a private key, set
a webhook secret) — that step cannot be done autonomously, and it adds a new
platform-level secret (the App's private key) to operate and rotate.

**Phase 1 ships with a per-tenant fine-grained Personal Access Token
instead.** Lower polish (the tenant generates their own token rather than one
OAuth click), but: zero new platform-level secrets, zero manual dashboard
step blocking ship, and it fully replaces the Docker preview infra — which is
the actual goal. A GitHub App is a valid Phase 2 if the PAT UX proves to be
real friction; do not build it now.

## What already exists and MUST be reused, not rebuilt

`code-intel/app/gitops.py`'s `ensure_checkout()` already clones/fetches git
repos with proper security hygiene: partial clone (`--filter=blob:none`), and
an **ephemeral credential helper** that injects the token per-invocation —
never written to `.git/config`, never in argv, never logged. This is
production-grade and existed before this pivot; use it as-is.

**The one real gap**: `RepoEntry.auth_env` (`code-intel/app/registry.py:31`)
is an **environment-variable NAME**, read via `os.environ.get(entry.auth_env)`
— built for a small, fixed number of service-level tokens set at container
start. A multi-tenant BYO-repo model needs a different token *per tenant*,
and you cannot set one env var per tenant in a running container. This is a
small, additive extension (see §2), not a redesign of gitops.py.

## Architecture

```
tenant admin ──(pastes a fine-grained PAT, repo-scoped)──▶ app-builder
                                                              │ encrypts + stores
"build an app" ──▶ app-builder:
   1. create/select branch in tenant's repo (GitHub API, using their PAT)
   2. generate() — UNCHANGED: same Claude call, same file-list output
   3. commit the generated files to that branch (short-lived clone+push,
      deleted immediately after — no persistent local checkout needed)
   4. open a PR (GitHub API)
   5. register the repo with code-intel as source_type="git", git_url=
      the tenant's repo, auth_token=<their PAT> (NEW field, §2) — code-intel
      clones straight from GitHub. No shared volume with app-builder at all.
   6. commit a starter .github/workflows/ file into new projects at scaffold
      time, so build/test runs on the TENANT's own Actions minutes.
Preview: no container of ours. The UI links to the PR and to the tenant's own
Actions run / their own Vercel-Netlify deploy-preview if they have one wired.
```

## 1. Per-tenant credential storage (app-builder, Python)

New module `app-builder/app/github_credentials.py`:
- Encrypt at rest with a **new**, service-own symmetric key
  (`APP_BUILDER_ENCRYPTION_KEY`, Fernet/`cryptography` — generate one, put it
  in `deploy/.env`, document it in `deploy/compose/docker-compose.yml` the same way
  every other per-service secret in this repo is documented). Do **not**
  reuse Core's `APP_ENCRYPTION_KEY` — different service, different trust
  boundary, same reasoning `settings.py` already gives for
  `code_intel_internal_token` being separate from `internal_token`.
- Store per-tenant: `{ tenant_id, repo_full_name, encrypted_token,
  connected_by, connected_at }` in the existing per-tenant JSON store
  pattern (follow `projects.py`'s file-per-tenant shape).
- `GET /projects/github/status` (connected? which repo?), `POST
  /projects/github/connect { token, repo_full_name }` (validate the token
  against GitHub's API before storing — reject early if it can't read the
  repo), `DELETE /projects/github/connect` (disconnect).
- **Never** log the raw token. Never return it in any response after storage
  (write-only, same posture as every credential field in this codebase).

## 2. code-intel: accept a direct token, not just an env-var name

Extend `RepoEntry`/the `/repos` POST body with an optional `auth_token: str
| None` field, used **in preference to** `auth_env` when both could apply
(document the precedence). In `gitops.py::_run`, if a direct token is passed
through, use it via the **exact same ephemeral-credential-helper mechanism**
already there — do not weaken the "never touches disk/argv/log" property.
This is additive: existing `auth_env` callers (if any) keep working
unchanged.

**Required test**: register a repo with a fake token via `auth_token`, run
the clone, and assert the token does not appear in the resulting `.git/config`,
in captured subprocess argv, or in any log line produced.

## 3. app-builder: rewire scaffold + generate, remove the Docker preview path

`scaffold.py::create_project` currently: copy template → local `git init` →
register with code-intel as `source_type="local"` → optional `generate()`.
Replace with: copy template → **push to a branch in the tenant's connected
repo** (via a short-lived clone using their token, or the GitHub Contents
API for small templates — your call, but no *persistent* local checkout) →
register with code-intel as `source_type="git"` (git_url = the tenant's repo,
git_ref = the new branch, auth_token = their decrypted PAT, fetched
per-call, never held longer than the request) → optional `generate()`
(unchanged LLM call; its file-write step now commits+pushes to the branch
instead of writing to a local workspace) → open a PR.

`preview.py` / `ingress.py` (the Docker-preview-container path): **do not
delete**, but gate it behind a feature flag defaulting OFF
(`APP_BUILDER_LOCAL_PREVIEW_ENABLED=false`) and stop calling it from the
main create/generate flow. Bare local dev without a connected GitHub repo can
still fall back to today's local behavior if the flag is on — that keeps
today's `mvn`-style zero-external-dependency local dev working, without it
being what production tenants hit.

**Required test**: a full create→generate→push→PR cycle against a real (or
faithfully mocked) GitHub API, asserting no Docker container is started and
no write happens outside the pushed branch.

## 4. web/: the tenant-facing flow

New screen, `web/app/build/github/page.tsx` or similar (find the existing
`/build` page and extend it, don't fork a parallel one) — "Connect GitHub":
a PAT input with a link to GitHub's fine-grained-token creation page scoped
to exactly the permissions needed (Contents: read/write, Pull requests:
read/write — nothing broader), calling app-builder's new
connect/status/disconnect endpoints. Once connected, the existing build flow
shows "Branch pushed" / "PR opened" with a link out to GitHub, instead of an
embedded preview iframe. Use the 2026-07-29 design system tokens
(`.state-pill` for connected/disconnected, `--cat-automation` fits this
surface's category).

**Honest copy**: state plainly that build/preview now runs on the tenant's
own GitHub (and their own Actions minutes if they add a workflow), not on
this platform. No fabricated claims about build times or reliability.

## Hard rules

- **No new platform-wide secret beyond `APP_BUILDER_ENCRYPTION_KEY`.** A PAT
  belongs to one tenant; never let it leak into a shared env var, a log, or
  cross-tenant storage.
- **Reuse `gitops.py`'s existing clone path.** Do not write a second git
  client. The extension in §2 is additive.
- **Do not delete `preview.py`/Docker preview code.** Flag it off; a later
  cleanup pass can remove it once the pivot is proven in production.
- **Do not deploy.** This changes a production-facing flow; coordinator
  integrates, verifies, and deploys after every agent's tests are green.
- File scopes below are disjoint — stay in yours; report a cross-scope need
  rather than taking it.

## File scopes

1. **app-builder backend** — `app-builder/app/github_credentials.py` (new),
   `app-builder/app/settings.py` (add `APP_BUILDER_ENCRYPTION_KEY`,
   `APP_BUILDER_LOCAL_PREVIEW_ENABLED`), `app-builder/app/scaffold.py`
   (rewire), `app-builder/app/main.py` (new routes), `app-builder/app/codeintel_client.py`
   (pass `auth_token` through), `app-builder/tests/**`.
2. **code-intel** — `code-intel/app/registry.py`, `code-intel/app/gitops.py`,
   `code-intel/app/main.py` (the `auth_token` field), `code-intel/tests/**`
   (the token-never-leaks test is the acceptance gate for this scope).
3. **web** — the Connect-GitHub screen + build-flow status UI, under
   `web/app/build/**` and any new `web/app/components/build/**`. Do not
   touch `app-builder`/`code-intel`.
