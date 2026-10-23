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
| Release-readiness lead (v1.0.0) | local editor (Claude Code on the maintainer's Mac), branches `rr/*` | the v0.1.9 → v1.0.0 readiness audit and its fixes, one small PR per area, each listing its files: image dependency CVEs (`core/pom.xml` dependency management, `web/package.json` and `web/Dockerfile`, the Node runner and browser-runner Dockerfiles and `package.json` files, and only the Node toolchain stages of `core/Dockerfile`); `web/proxy.js`, `web/app/**` fixes and `tests/e2e/tests/**`; channel and webhook hardening in `core/omnichannel*`, `core/security`, `core/tenancy` (erasure), `core/audit`, `core/agents-tools` (MCP URL checks), `core/agents-interop`; `.github/workflows/{ci,zap,static-gates,pypi-release}.yml`, `.github/dependabot.yml`, `scripts/ci/**`, `Makefile`; `sdk/**`, `CITATION.cff`, `contracts/`; documentation under `docs/`, root policy files and `docs/maintainers/v1.0.0-definition-of-done.md`. Files claimed by the release / install agent and the personal start-up agent (`installer/`, `scripts/vegaduta*`, `install.sh`, `release.yml`, `deploy/compose` personal and local-AI overlays, `core/Dockerfile` apart from the Node toolchain stages, `tests/install/`) are only touched in PRs that say so in their title and wait for that owner's review before merging. Never tags, dispatches or re-runs a release; merges nothing. Runs stacks only under its own Compose project names (`rr-*`) |
| Lean personal edition agent | local session, branches `lean/*` merged into `feat/personal-lean-edition` (a pull request off `main` follows; nothing is pushed yet) | the edition switch and what a personal install leaves out: `core/security/.../edition/` (`@ConditionalOnEditionFeature`, `EditionFeature`, `EditionInfo`) and the one-line class annotations it adds across `core/tenancy`, `core/payments`, `core/billing`, `core/platform`, `core/templates`, `core/admin` (SCIM), `core/omnichannel` (Slack, Discord, Teams, X), `core/mcpserver`, `core/agents-interop` (A2A, directory), `core/sarvam`, `core/elevenlabs`, `core/voice`, `core/workflow` (share voice), `core/imagegen` (video and demo media); `PublicStatusController` (the `edition` object); `web/app/lib/edition.ts` and the route table, `web/proxy.js`, the channel, card and platform-admin allowlist changes; `VEGADUTA_EDITION` in `deploy/compose/personal.yml`, `.env.example` and `scripts/vegaduta`; `scripts/ci/boot-check.sh` / `startup-profile.sh`; `CHANGELOG.md`, `docs/configuration.md`, `docs/personal-mode.md`. Overlaps the personal start-up agent's `application-personal.yml` and the release / install agent's launcher and compose files: the change goes through a PR they review before it merges. `ci.yml` (a personal boot-check job) and the release workflows stay with the release agent |
| IDE plugins / console pagination agent (Devin) | local editor, branch `feat/web-list-pagination` | pagination-only edits in `web/app/`: `admin/instructions/PromptRegistryPanel.tsx`, `PromptHistory.tsx`, `promptRegistry.test.tsx`, `ai-models/page.tsx`, `components/AdminAccessMatrix.tsx`, `SentinelsPanel.tsx`, `clips/ClipStudio.tsx`, `knowledge/SemanticSearchPanel.tsx`, `knowledge/FaceEnrollmentControls.tsx` - overlaps the release-readiness lead's `web/app/**` claim, so the PR says so in its title and waits for their review |
| App Builder change-turn agent (Devin) | local editor, branch `feat/build-change-progress` | the /build change turn: `POST /generate/stream` (SSE stages) + summary + parallel file reads in `services/app-builder`, the `summary` field in `core/agents-studio/.../appbuilder` (generate-files contract), and `web/app/build/**` (`lib.ts` `generateStream`, `ChatPane.tsx` progress/revert, `ChatResultEntry.tsx` summary + per-file +/-, `edgeFiles.ts`, `studio/{patch,engine,useProjectSession}.ts`, `suggestions.ts` `diffFileStats`) - overlaps the release-readiness lead's `web/app/**` claim, so the PR says so in its title and waits for their review. Java compiles on CI only (no JDK 21 on this machine) |
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

- 2026-10-23 09:44, release-readiness lead: merged the v1.0.0 readiness pull requests at the maintainer's
  request, each squash-merged with `CI OK` and `CodeQL OK` green on its head: #27, #100, #105, #91, #103,
  #113, #90, #87, #108, #106, #102, #95, #98, #96, #109, #89, #110, #111, #112, #115, #117, #94, #104, #107,
  #114, #97, #93, #92, #88, and the release-notes PR #134 (the readiness PRs' CHANGELOG bullets, taken out of
  them so they stopped conflicting). Not merged: the Spring Boot 4 port #99 (draft, waits for the soak
  decision), Dependabot and other agents' PRs. No tag and no release in flight. Generated docs (module
  graph, config reference, bundled platform guide) had drifted on `main` and were regenerated inside #92,
  #93 and #97.
- 2026-10-23 02:37, release-readiness lead: merged #118 (`next` 16.4.2: two critical RCE advisories and a DoS
  published 2026-10-22, main and the published 0.1.9 web image shipped 16.3.6) at the maintainer's request,
  after `CI OK` and `CodeQL OK` were green on its head `5293fa37`; result `56e3411f`. No tag and no release
  in flight. A patch release of the web image is still needed for installs to get the fix: the release
  agent decides. #88 also bumps `next` and needs a trivial rebase.
- 2026-10-22, lean personal edition agent: nothing pushed or merged to `main` yet. Claimed the areas in the row above (tenancy, payments, billing, platform, omnichannel, mcpserver, web, deploy/compose personal files, the three docs). The work sits on local branches `lean/*` merged into `feat/personal-lean-edition`; the release / install agent and the personal start-up agent are asked to review it before the pull request merges. Not run on a stack yet; see the CHANGELOG entry for what was verified.
- 2026-10-22 11:52 UTC, personal start-up agent: merged #83 (`personal --minimal` and `--no-valkey` no
  longer exit in silence) and #86 (plan page for a lighter personal install) after green CI. Open and
  waiting on CI: #82 (start-up budget), #84 (preparing an install never stops another install), #85
  (core image stores the jar once). Claimed the files in the row above; the release / install agent's
  review of #82 and #84 is welcome. Measured on one laptop with Docker Desktop, empty database, local
  Ollama: published 0.1.9 took 39-64 s from launcher start to a healthy console, the branch 23-28 s.

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
- 2026-10-23, IDE plugins / console pagination agent (Devin): opened a PR off `main` adding the standard `usePagination` + `PaginationControls` to the record lists that did not page (AI Model Control features, prompt registry catalog, prompt history, admin access matrix, Sentinels synced events, Clip Studio image picker, semantic-search scope chips, face enrollments) and updated the registry test for the pager's second `role=status` live region. Everything else already pages client- or server-side. Merged as #138 (`15c449ae9`) after green `CI OK`/`CodeQL OK`; the PR also regenerated `docs/architecture/core-module-graph.md` (stale on `main` since the agents-jobs/providers additions).
- 2026-10-23, App Builder change-turn agent (Devin): the /build change turn now streams its stages (`POST /projects/{id}/generate/stream`: context/writing/files/checking/publishing/done), the browser renders the applied files' preview while the build check still runs (reverted on failure), the model returns a plain-English `summary` shown as the result headline with per-file +/- counts, retrieval reads project files in parallel, and the Studio surface shows the model's `NOTE:` line instead of "N changes applied". Maintainer merged #140 mid-CI (squash `1332ccca3`) ahead of the spotless/ruff-format fix-ups; #141 carries the identical code with the required formatting and waits on green. Verified locally: 25/25 app-builder pytest, 316 web vitest, tsc/eslint/prettier/ruff clean; Java compiles on CI only (no JDK 21 on this machine).
