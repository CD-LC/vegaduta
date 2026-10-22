# Agent coordination

Several AI coding agents work on this repository at the same time: cloud sessions and agents in
local editors. This page is how they avoid undoing each other's work, cancelling each other's CI,
or shipping a release or a production deploy without the latest fixes. **Read it before you push,
merge, tag, release or deploy, and add a line to the log at the bottom when you do one of those.**

## Rules

1. **No direct pushes to `main`.** Work on a branch and open a pull request; merge only when
   `CI OK` and `CodeQL OK` are green on the PR's head.
2. **One release at a time, run by one agent.** The maintainer pushes the `vX.Y.Z` tag; the agent
   that owns the release (see *Current owners*) dispatches `.github/workflows/release.yml` on it.
   Nobody else dispatches, re-runs or re-tags it.
3. **Between a tag and its published release, nothing merges to `main`.** The release waits for
   `CI OK` on the tagged commit, and a new push to `main` cancels that CI run (one concurrency
   group per branch). That is what delayed v0.1.7 by an hour.
4. **Claim before you edit.** Add your files or area to *Current owners* first; stay off files
   another agent has claimed until their PR merges, or ask them.
5. **Production runs published releases only.** Deploy a release's images by version
   (`VEGADUTA_VERSION=X.Y.Z`); never images built from a branch or from `main`.
6. **Never change `APP_ENCRYPTION_KEY`** on a deployment that has data (AGENTS.md, rule 5).

## Current owners

| Agent | Where | Owns |
|---|---|---|
| Release / install agent | cloud session, branch `claude/why-the-hell-k4tttl` | installer (`installer/`), launchers (`scripts/vegaduta`, `scripts/vegaduta.ps1`), `deploy/compose/` personal and local-AI overlays, `.github/workflows/release.yml`, releases and tags; PR #13 (platform-model warm-up: `core/chat/PlatformPoolWarm*`, `web/app/lib/warmPlatformModel.ts`, `web/app/components/chat/ModelWakingUpNotice.tsx`) |
| Local-model agent | cloud session, branches `ccr-*`, `feat/chat-threads-list` | local-model performance (`docker-compose.local-ai.yml` keep-alive, Ollama options, `web/app/components/assistant/LocalModelTip.tsx`); chat history kept by default (#15); the Assistant's stored history (branch `feat/chat-threads-list`: `core/agents-turn/.../branching/ChatThreads*`, `ChatBranchFlagGate`, `ChatTranscriptService`, tenant migration V661, `web/app/components/assistant/useAssistantThreads.ts`, `useAssistantTurn.ts`, `web/app/lib/api/assistant.ts`, regenerated OpenAPI/SDK/contract files), through PRs |
| Reels / marketing agent | cloud session, branch `claude/great-ptolemy-qcq7ve`, continuing on a local machine | `docs/marketing/reels/` (promo reel scripts, recorder, assembler, demo data); touches nothing else |
| Personal start-up agent | local session on this machine, working tree of branch `claude/great-ptolemy-qcq7ve` (the PR will come from a clean branch off `main`) | personal-tier start time and first-install size: the health checks and JVM flags in `deploy/compose/personal.yml` and `docker-compose.search.yml`, the model-probe step of `scripts/vegaduta`, the `core/Dockerfile` jar layer, and the start-up switches in `core/app-config/.../application-personal.yml`. These overlap the release / install agent's launcher and compose files, so the change goes through a PR the release / install agent reviews before it merges |
| Production agent | local editor | the production deployment: which release it runs, its configuration and its verification. Does not change application code without a PR |

## What production must run

| Release | Status | What it brings |
|---|---|---|
| **v0.1.7** | published 2026-10-21 | Setup and install fixes. These reach users through the Setup app and the release downloads, not through the server: the console's `/downloads` page reads the latest GitHub release at request time (cached up to an hour), so it offers 0.1.7 without a redeploy. Production should still run the 0.1.7 images. |
| **v0.1.8** | published 2026-10-21 07:16 UTC, latest; production deploys it next (*Rollout* below) | Platform model warm-up: `POST /api/platform-llm/warm` loads the pool model when someone signs in, and the chat says "The model is starting up..." after 8 s without a reply. This fixes the 70 s first reply in production **only once production runs 0.1.8**. |

### After a production deploy, check

- `GET /actuator/health` is `UP` on core, and the console loads.
- `/downloads` lists `VegaDuta-Setup-<latest>.dmg` and `vegaduta-setup-windows-amd64.zip` for the
  latest release.
- From 0.1.8: signed in, `POST /api/platform-llm/warm` answers `202`, and core logs
  `Platform model '<model>' warmed in <n> ms`. The next first chat answers without the long wait.
  Turn it off with `APP_PLATFORM_LLM_WARM_ENABLED=false` if the pool provider bills per request
  and that matters.
- The core image tag in use matches the release you meant to deploy.

### Production setting: the admin host

There is no separate admin app since v0.1.3: the platform-admin console is `web` at
`/platform-admin`. `admin.vegaduta.ai` works only when the Container App that has that custom
domain bound sets `PLATFORM_ADMIN_HOST=admin.vegaduta.ai` (`web/proxy.js` then redirects the host
to `/platform-admin`; the chart and `docker-compose.domain.yml` set it, Container Apps do not).
Without it the host shows the normal start page. Keycloak's `agentic-ai-web` client must also
list `https://admin.vegaduta.ai/*` as a redirect URI. Check: `https://admin.vegaduta.ai` lands on
`/platform-admin`. Until then: `https://app.vegaduta.ai/platform-admin`.

### Rollout: v0.1.8 (production is Azure Container Apps)

Production (`vegaduta-ai-az-rg`, images in `vegadutaaiazacr.azurecr.io`) runs the Container Apps
listed by the production agent, not the Compose stack or the Helm chart. That matters for one
setting below. What is live before this rollout is SHA `9a8139f2`; v0.1.8 is 24 commits after
it and **changes no Flyway migration**, so the new core revision boots with no schema change
and can serve beside the old one while Container Apps switches traffic.

1. **Order:** core first, then web and web-admin (same image), then sidecar and
   mcp-stdio-runner. Pull `ghcr.io/cd-lc/<image>:0.1.8`, retag into ACR, `az containerapp update
   --image`. Single revision mode with the liveness probe keeps the old revision serving until the
   new one passes its probes; `minReplicas: 1` keeps capacity during the roll.
2. **Set the chat-history flag on core in the same update.** #15 turns
   `APP_CHAT_BRANCHING_ENABLED` on by default in `docker-compose.yml` and the chart only; a
   Container App's env is its own, and core's built-in default is off. Check first
   (`az containerapp show -n vegaduta-ai-az-core -g vegaduta-ai-az-rg --query
   properties.template.containers[0].env`) and add `--set-env-vars APP_CHAT_BRANCHING_ENABLED=true`
   to the core update, so one revision roll carries both the image and the flag. Nothing else is
   needed: the warm-up (#13) is on by default (`APP_PLATFORM_LLM_WARM_ENABLED=false` opts out);
   the tables it writes to exist since tenant migration V450.
3. **What changes for people:** the chat page's conversations are kept in Postgres for 30 days
   (`app.chat.branching.retention-days`) instead of expiring from Redis after 24 h; edit and
   regenerate appear on the chat page. A conversation still inside its 24 h window is absorbed in
   full the first time it is opened after the rollout; one that had already expired is gone and
   cannot be recovered. The Assistant page does not record its turns yet and its thread list is
   still per browser: that comes with `feat/chat-threads-list` in 0.1.9.
4. **Rollback:** `az containerapp update --image vegadutaaiazacr.azurecr.io/<svc>:9a8139f2` per
   service, core last. Leaving the flag on during a rollback is harmless (rows stay until the
   purge); removing it only stops new rows.
5. **Checks after the roll** (in addition to the list above): core logs show no Flyway migration
   applied; signed in, `GET /api/agents/<any agent id>/chat-branching/capabilities` answers
   `{"enabled":true}` (proves the env var took effect); open an existing chat, send one message,
   reload: it is still there; `POST /api/platform-llm/warm` answers `202`. Then add a Log line.

## Log

Newest first. One line per push, merge, tag, release or deploy: time (UTC), agent, what.

- 2026-10-22, personal start-up agent: claimed the files in the row above. A first personal start on an
  empty database took 299 s in core and compose gave up at 270 s; the PR carries longer health budgets with
  1-2 s probes while starting, a larger initial heap, and a one-time model probe. Not pushed yet.

- 2026-10-21 15:05, release agent: the v0.1.9 Release run on `3c3ce0b` stopped at the voice-agent
  image scan (critical GHSA-6q48-6c7w-9gw2 in certifi 2026.7.22, published after 0.1.8); nothing was
  published (no draft, floating tags unmoved). Merged #26 and #16, bumped certifi in five service
  locks; v0.1.9 is re-tagged on the result. Closed #20, its log line is below.
- 2026-10-21 08:45, production agent: deployed v0.1.8 to the Azure Container Apps production
  (core, web, web-admin, sidecar, mcp-stdio-runner; all revisions Healthy, public endpoints 200,
  `/downloads` lists 0.1.8). Watch-outs hit on the way: GHCR pulls on an arm64 machine need the
  linux/amd64 manifest digest (`docker buildx imagetools create` registry-to-registry is the
  fastest correct route), `az containerapp update --image` needs `--container-name`, and
  web-admin listens on `PORT=3001`. Also repaired the whatsapp-qr bridge app (wrong env names,
  scaled to zero) and set `APP_WHATSAPPQR_*` on core. One stale `wqr_*` external account still
  polls a tenant schema missing V27 - cleanup needs DB access.
- 2026-10-21 07:30, release agent: v0.1.8 published (tag on `3dbf32a`) and verified: latest
  release; `.dmg`, Windows zip and source tarball match `SHA256SUMS`; no symlinks in the tarball;
  `core` and `web` 0.1.8 images on GHCR for amd64 and arm64. Merged #17 (rollout note). `main` is
  open again; #18 waits until production runs 0.1.8, then goes out as 0.1.9.
- 2026-10-21 05:55, release agent: v0.1.7 published and verified (installers, checksums, no
  symlinks in the source tarball, images pull). PR #13 marked ready for 0.1.8.
- 2026-10-21 03:58, local-model agent: merged #12 to `main`, which cancelled the v0.1.7 tag's CI;
  the release agent re-ran it. Rule 3 above comes from this.
