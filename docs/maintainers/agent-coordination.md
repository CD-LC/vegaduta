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
5. **One agent per working tree.** Do not share a checkout between agents: a shared tree means
   shared `git status` (your `git add -A` picks up another agent's half-written files), shared
   HEAD (their `git checkout` switches the branch out from under your build), and stash pops
   that conflict with the other agent's commits. Give yourself a worktree instead -
   `git worktree add ../<repo>-<agent-or-area> <branch>` (or `git worktree add -b
   <your-branch> ../<repo>-<agent-or-area> origin/main` for new work) - and do all staging,
   committing and building inside it. When a worktree is finished, `git worktree remove` it;
   `git worktree list` shows who has one. If you do have to touch a shared tree, never run a
   blanket `git add -A` - stage your own paths only, and `git stash` only what is yours.
6. **Production runs published releases only.** Deploy a release's images by version
   (`VEGADUTA_VERSION=X.Y.Z`); never images built from a branch or from `main`.
7. **Never change `APP_ENCRYPTION_KEY`** on a deployment that has data (AGENTS.md, rule 5).

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
| Build/troubleshoot agent (Devin) | local editor, branches `fix/studio-waiting-progress`, `feat/local-model-gates` | `web/app/build/studio/**` (turn progress text, cloud timeout, SEARCH/REPLACE marker-leak fix) and the local-model capability gate: `localModelManagedHere` in `web/app/lib/api/localModels.ts`, `web/app/troubleshoot/page.tsx`, `web/app/providers/{page,local-model/page}.tsx`, `web/app/lib/personalInstall.ts` (route table) - overlaps the release-readiness lead's `web/app/**` claim, so the PRs say so in their titles and wait for their review |
| Production agent | local editor | the production deployment: which release it runs, its configuration and its verification. Does not change application code without a PR |
| Agent-authored tools agent (Devin) | local editor, branch `feat/agent-authored-tools` (PR #156) | agent-authored custom tools: `services/tool-sandbox/**` (JS/TS runtime + jsvalidate + Dockerfile + tests), `core/agents-tools/**` (`CreateCustomToolToolCallback`, `CustomCodeToolEnableApprovalExecutor`, `CustomCodeToolService`, `AgentCustomToolResolver`, `ToolResolverConfiguration`, `ToolSourceConfiguration`, `AgentToolDiscovery`), `core/agents/.../CustomCodeToolLanguage.java` + `BuiltInToolClassifications`, `core/approval/ApprovalPolicyService`, `core/agents-jobs/.../ApprovalActionLabel`, `web/app/components/customCodeTools/**`, `web/app/lib/edge/codegen.ts`, `deploy/compose/{personal,lite}.yml` sandbox service + `scripts/vegaduta` profile wiring, `tests/install/vegaduta-personal.test.sh`, `docs/configuration.md`, `CHANGELOG.md` - overlaps the release / install agent (launcher, compose, install tests) and the release-readiness lead (web/app), so the PR says so in its title and waits for their review |
| KB-URL-ingest / MCP static-upgrade agent (Devin) | local editor, branch `fix/kb-url-ingest-mcp-static-upgrade` | web-document ingestion into the knowledge base (`core/knowledge` `KnowledgeUrlFetcher`/`KnowledgeUrlIngester`, `AddToKnowledgeBaseToolCallback` `url` arg, `KnowledgeController` `POST /api/knowledge/ingest-url`, `IngestUrlRequest`), per-agent KB write config (`core/agents` `AgentController` knowledge-write endpoints + new DTOs), dead static-MCP repair (`core/mcp` `McpStaticReferenceUpgrader`, `McpStaticServerAvailability`, `McpReferenceServerCatalog` launch specs, `McpRegistryService`/`McpRegistryController`, `core/agents-tools` `StaticMcpServerAvailabilityAdapter`, `AgentKnowledgeToolResolver` + `ToolResolverConfiguration`), `web/app/lib/api/agents.ts`, `web/app/components/agents/workbench/{KnowledgeSection,KnowledgeWriteSection}.tsx`, `web/messages/en.json`, `deploy/compose/{personal.build,lite,docker-compose}.yml` + `.env.example`, `core/app-config/application.yml`, `docs/configuration.md`, `CHANGELOG.md` - overlaps the release-readiness lead (`web/app/**`, `core/agents-tools`, docs), the release / install agent (`deploy/compose`, `.env.example`) and the agent-authored-tools agent (`ToolResolverConfiguration`), so the PR says so in its title and waits for their reviews |
| Capability/latency/auth agent (Devin) | local editor, worktree `vd-capfix-wt`, branches `feat/ollama-auto-context`, `feat/local-password-reset`, `docs/today-changes` | local-model capability + turn latency + local-auth UX: `core/providers/localmodels` (context self-heal, `LocalModelOllamaGateway`, `LocalModelsService`), `core/admin` + `core/security` `localauth` (reset-request endpoint, `password-reset.txt`), `scripts/vegaduta{,.ps1}` reset-password, `web/app/components/localauth/SignInPanel.tsx`, `web/app/lib/localAuthProxy.ts`, `web/messages/en.json`, docs + CHANGELOG - overlaps the release/install agent (`scripts/vegaduta`, `.env.example`) and the release-readiness lead (`web/`, `docs/`), so the PRs say so in their titles and wait for their reviews |
| Search-capability-honesty agent (Devin) | local editor, worktree `vd-capfix-wt`, branch `fix/search-capability-honesty` | fixes for the incident where an agent with no configured search provider told the user it had no web access, then burned ~125k prompt tokens answering from memory: `core/agents-browser/.../web/WebSearchToolCallback` + `WebSearchProviderWiring` + `BuiltInWebTools` (a refusing `web_search` stub instead of silent absence, boot WARN on an unconfigured chain), `core/chat/` `ToolLoopTranscript`/`ToolLoopRunHooks`/`ToolLoopProperties`/`ArgumentSanitizingToolCallingManager`/`ChatPrompts` + `tool-loop.properties` + `prompts/en/toolresult.tool_budget.tokens.md` (cumulative per-run prompt-token soft stop, `app.chat.tools.maxPromptTokensPerTurn`), `core/agents-turn` `ChatContextCompaction`/`ContextCompactionProperties` (reserve headroom off the window) + `ChatTurnExecutor`/`BehaviourSettingsCatalog` (`chat.toolLoop.maxPromptTokensPerTurn`), `core/app-config/application.yml`, `web/messages/en.json` + the generated `settingsCatalog.snapshot.json`, `docs/configuration.md`, `docs/prompts.md`, `CHANGELOG.md` - **overlaps the KB/MCP agent's parallel audit work** (their `ArgumentSanitizingToolCallingManager`/`ChatTurnExecutor`/`application.yml`/`tool-loop.properties`/`docs/configuration.md`/`CHANGELOG.md` edits; see `AGENT-COORDINATION-NOTE.md` they left in the worktree - our edits sit in disjoint methods, take both on merge) and the release-readiness lead (`web/`), so the PR says so in its title and waits for their review |
| Desktop-recipes lead (Devin, 5-agent fan-out) | local editor, worktree `vd-capfix-wt`, branch `feat/desktop-recipes` (PR #168) | "record once, replay later, monitor-or-auto" for the PERSONAL tier's `local_desktop_*` surface, mirroring browser recipes: new `desktop/` classes in `core/agents-tools` (entity `desktop_recipe` - spec/validator/recorder/replayer/service, `/api/desktop/recipes`, `run_desktop_recipe`, `/api/desktop/monitor/frame` proxy), `DesktopToolSource`/`DesktopAgentClient`/`RunBrowserRecipeToolCallback`/`BuiltInToolClassifications*` small edits, `services/desktop-agent` (`GET /frame`, `GET /capabilities`, bearer-auth on every route), `application.yml` + `.env.example` + `docs/configuration.md`, `web/app/components/desktop/**` + `web/app/lib/api/desktopRecipes.ts` + in-turn live monitor + console screen dock, `CHANGELOG.md` - feature is OFF by default, personal-tier only. V672 was adopted by the scratchpad agent, so the recipe table renumbered to a fresh version. Overlaps the release-readiness lead (`web/app/**`), so the PR says so and waits for their review |
|| OSS-integrations agent (Devin) | local editor, branch `feat/oss-integrations-docling-composio-n8n` | the three Tier-1 integrations from the 2026-10 OSS-integration review: `services/docling/**` (new FastAPI Docling adapter) + `core/knowledge` Docling routing (`DoclingConversionService`, `SidecarClient`, `KnowledgeController`, `KnowledgeUrlIngester`, `KnowledgeIngestionService`, `application-knowledge.yml`), Composio/Rube remote-MCP catalog entries (`core/mcp` `McpPlatformAndRemoteCatalog`, `web/app/components/agents/templatePrereqs.ts`, `templateAudience.ts`), n8n bridge (`examples/integrations/n8n/`, `docs/integrations/`, `mkdocs.yml`), `deploy/compose/{docker-compose,lite}*.yml` + `.env.example` + `scripts/vegaduta{,.ps1}` + `services/lock-requirements.sh`, `AGENTS.md`, `docs/configuration.md`, `CHANGELOG.md`, this file. Overlaps the release / install agent (`scripts/vegaduta`, `deploy/compose`, `.env.example`, install tests), the release-readiness lead (`web/app/**`, `docs/`), and the KB-URL-ingest agent (`core/knowledge`, `.env.example`), so the PR says so in its title and waits for their reviews |

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

## Blocked dependency bumps (as of 2026-10-24)

Dependabot keeps these open so they self-merge when upstream ships support. Do not force them —
each is blocked outside this repository.

| PR | Bump | Blocker |
|---|---|---|
| #67 | eslint 9 → 10 | `eslint-plugin-jsx-a11y@6.10.2` peers `eslint ^9` max; no eslint-10-compatible release exists. Merging would mean dropping accessibility linting. |
| #59 | typescript 6 → 7 (web) | `typescript-eslint@8.x` peers `typescript <6.1` and TS7 is the Go rewrite (`tsgo`) with no classic compiler API — the lint stack cannot run on it. |
| #99 | Spring Boot 4.1 + Spring AI 2.0 | Tracked migration (ADR-0012 P2/P3) owned by another workstream: Jackson 2→3, vendor SDKs, tool-loop changes, MCP SDK 2.0. Merge conflicts + maintainer decision, not a version bump. |

Closed as superseded by #99: #45 (spring-ai 2.0 alone), #47 (spring-boot-starter 4 alone) — either would
break the build standalone.

## Log

Newest first. One line per push, merge, tag, release or deploy: time (UTC), agent, what.

- 2026-10-27, academy author (Devin, worktree `vd-academy`): reviewed PR #254's
  CI failures (piper-tts GPL-3.0 dep review rejection, main.py 691>678 file
  ratchet, ruff) — `d51860556` resolves all three and is verified locally
  (ruff 0.12.4 gate clean on changed files, main.py now 555, MIT binary path
  sha256-pinned). NOTE: worktree `/private/tmp/va-fix` still has pre-fix
  `3d6776cd7` checked out — pull before touching it further. Watching CI on
  the new head; also re-triggered #253's flaky AgeOptionalIT run (flyway
  history PK race, unrelated to that PR's catalog change).

- 2026-10-28, academy author (Devin, worktree `vd-academy`): ACTIVE CLAIM —
  full re-record of Series A (episodes 00-10) on the flicker-free recorder
  with the seeded Ledger newsroom dataset, Kokoro-82M local neural narration
  (`academy/tools/narrate-kokoro.mjs`, new preference above edge-tts),
  script passes rewritten to story-driven real-world newsroom framing, then
  push `feat/academy-video-pipeline` + open the PR to main. Batch-committing
  agent: please hold off sweeping academy/ until this lands — ping here if
  you need to touch it first.

- 2026-10-27, academy author (Devin, worktree `vd-academy`): ACTIVE CLAIM on
  Series-A lessons **06-10** (`06-guardrails`, `07-memory-learning`,
  `08-quality`, `09-operate`, `10-ship`) - scripts + lesson.md twins only.
  Lessons 00-05 are committed (`8e670962e`). If you are the batch-committing
  agent, sweep `academy/lessons/0[6-9]-*/` and `10-*/` like the earlier ones;
  do not re-number.

- 2026-10-27, academy author (Devin, worktree `vd-academy`): EXTENDED CLAIM —
  Series-B deep-dive lessons for all 62 personal-edition routes, plus
  narration upgrade to ElevenLabs (`academy/tools/narrate-elevenlabs.mjs`,
  edge-tts stays the no-key fallback). Enterprise-only routes
  (`/studio`, `/eval`, `/roi`, `/admin/mcp-servers`, `/admin/sso`,
  `/admin/branding`, `/admin/payments`, `/admin/api-keys`,
  `/admin/agent-trust`, `/admin/leads`, `/admin/commerce`,
  `/shared-with-me`, `/directory`) are deferred until a lite/full stack
  is installed for capture.

- 2026-10-27, OSS-integrations agent (local Devin session): pushed
  `feat/oss-integrations-docling-composio-n8n` and opened a PR for the three Tier-1
  OSS-integration picks - Docling-first knowledge conversion (`services/docling` +
  `core/knowledge` routing, off by default), Composio Connect + Rube remote-MCP catalog
  entries, n8n bridge examples/docs. Local deploy of the branch follows for smoke-testing.
- 2026-10-26, capability/latency/auth agent (local Devin session, worktree `vd-capfix-wt`): a day of
  fixes, all merged to main unless noted - #169 mid-loop narration leak (tool-call rounds classified
  by `hasToolCalls`, not the provider `finish_reason`), #170 install/downloads resolve the newest
  platform `vX.Y.Z` release not `/releases/latest` (sdk-v0.1.1 had hijacked it), #171 shared
  pre-turn deadline (`app.chat.pre-turn-max-wait`), #172 `browser_navigate` sandbox-vs-desktop
  honesty, #173 Windows desktop-agent. Open: #200 `app.llm.ollama.auto-context-fix` (derives a
  `-ctxNk` variant when the runner loads a smaller window than `num-ctx`) and #202 local-auth
  password reset (`POST /api/auth/local/reset-request` writes `password-reset.txt`; `vegaduta
  reset-password` + a "Forgot password?" view). Docs pass on `docs/today-changes`.
- 2026-10-25, production deploy (local Devin session): deployed **v0.1.10** to the Azure Container Apps
  production (`vegaduta-ai-az-rg` / `vegadutaaiazacr`), in the documented order core → web + web-admin →
  sidecar + mcp-stdio-runner. Release required five fix cycles before publish: #161 (arm64 libatomic),
  #162 (arm64 puppeteer postinstall), #163 (python:3.14.8, CVE-2026-19445), a Slack-test flake re-run,
  and #164 (release body >125K). One deploy hazard to watch: `az containerapp update --image` without
  the correct `--container-name` ADDS a phantom container to the template instead of failing; removed
  via `az rest` PATCH of `properties.template`. All five apps verified Healthy on 0.1.10; 10 Flyway
  migrations (V662–V671) applied cleanly on core boot; public health + status endpoints green.

- 2026-10-25, edge media generation (local Devin session): opened PR #165 (`feat/edge-media-gen`) —
  `local_generate_image` (SD-Turbo ONNX via onnxruntime-web/WebGPU) + `local_generate_audio`
  (MusicGen via transformers.js) as out-of-the-box local tools; consent-gated downloads, capability
  gates, session media registry + Edge console gallery; `LocalTool.timeoutMs` added.
- 2026-10-25, search-capability-honesty agent (local Devin session): fixes for the research-run incident
  (agent reported "no web access" on a misconfigured provider, then spent 133 s / ~125k prompt tokens
  answering from memory). `web_search` is always offered - a refusing stub when no provider in the chain
  is configured - plus a boot WARN naming the gap; a cumulative per-run prompt-token budget
  (`app.chat.tools.max-prompt-tokens-per-turn`, tenant/agent `chat.toolLoop.maxPromptTokensPerTurn`)
  soft-stops the loop; context compaction now reserves output headroom
  (`app.context.compaction.reserve-output-tokens`, 8192) so the trigger is not at the window's brink.
  In isolated worktree `vd-capfix-wt` on `fix/search-capability-honesty`. Overlaps the KB/MCP agent's
  parallel audit PR (#166 files per their AGENT-COORDINATION-NOTE.md) - disjoint methods, take both on
  merge; PR title flags it and waits for rr-lead + #166-owner review.

- 2026-10-24, merge queue + background-handoff (local Devin session): root-caused the
  `HotPathQueryBudgetIT` regression blocking every core PR (bisect to `f29212078`: +3 legitimate
  per-turn queries; budgets raised to 19/20) and the surefire `app`-module heap OOM
  (`-Xmx8g` per-fork arg, from #75); both shipped in #153 which unblocked the queue. Merged:
  #152, #63, #66, #65, #60, #55, #153, #157, #154, #150, #75, #120, #41, #42, #46. Opened
  #155 (approval-status await flake fix), #156 (agent-authored tools), #158
  (`feat/background-handoff`: `continue_in_background` tool handing over-budget tasks to a
  multi-turn `agent_job`; `maxTurns` on `AgentJobStartRequest` + `app.agent-jobs.max-turns-cap=8`;
  never offered inside a job session). #44 (spotless 3.x major bump) rebased, watch CI.
  Deployed v0.1.9 to Azure prod (see entry below).

- 2026-10-24, agent-authored tools (local Devin session): opened PR #156
  (`feat/agent-authored-tools`) - `create_custom_tool` built-in, structural human approval
  (`custom_code_tool.enable` executor, default-gated so NEVER_GATE agents cannot bypass),
  JavaScript/TypeScript in tool-sandbox (jsvalidate + Node `--experimental-permission`), and
  tool-sandbox on personal/lite via the `tools` profile. Touches release/install and
  release-readiness areas; waits for their review.

- 2026-10-24, production deploy (local Devin session): deployed **v0.1.9** to the Azure Container Apps
  production (`vegaduta-ai-az-rg` / `vegadutaaiazacr`), in the documented order core → web + web-admin →
  sidecar + mcp-stdio-runner. Images copied registry-to-registry (`docker buildx imagetools create` from
  `ghcr.io/cd-lc/*:0.1.9`; `web-admin:0.1.9` retagged from `web:0.1.9`, same digest as before). All five
  revisions Healthy; `/actuator/health` UP, console 200, `/downloads` lists `VegaDuta-Setup-0.1.9`, core
  image tag verified `core:0.1.9`. Flyway delta vs 0.1.8 is one tenant index (`V661__chat_branch_session_owner_idx`)
  - applies per tenant, no control-plane change. Watch-outs seen in core logs (pre-existing, not from this
  deploy): Redis Enterprise `Balanced_B0` is at `maxmemory` with `VolatileLRU` eviction, so writes whose keys
  carry no TTL fail `OOM command not allowed` - needs an eviction-policy or SKU decision; the stale
  `wqr_*` external account still polls a tenant schema missing V27 (needs DB cleanup, as before).

- 2026-10-22 10:25, release agent: v0.1.9 published (tag on `b6269fd`, Release run 18) and verified:
  latest release; `.dmg`, Windows zip and source tarball match `SHA256SUMS`; no symlinks in the tarball;
  `application-personal.yml` has chat history on; voice-agent lock has certifi 2026.10.19 and pillow 13.0.0.
  Earlier attempts stopped at the image scan on advisories published after 0.1.8 (certifi, Pillow; fixed in
  #28, #29). `main` is open again. Production can deploy 0.1.9 by version.

- 2026-10-22 08:06, docs agent (local Devin session): pushed `docs/rest-sdk-release-workflow`
  (adds `rest-sdk-release.yml` to the workflow table and refreshes the PyPI/npm state rows; docs
  only, no code). Also on the repo: created the `pypi`, `pypi-rest-sdk` and `npm` environments,
  set `SDK_PUBLISH_PYPI=true` (arms the PyPI jobs of both `pypi-release.yml` and
  `rest-sdk-release.yml`; `SDK_PUBLISH_NPM` left unset until the npm name and `NPM_TOKEN` exist).

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
- 2026-10-25, KB-URL-ingest / MCP static-upgrade agent (Devin): pushed `fix/kb-url-ingest-mcp-static-upgrade` as **PR #166** (waits on green CI + the review overlaps it names). Also deployed it onto the maintainer's **local personal stack** (`vegaduta-personal` compose project, build overlay, images tagged 0.1.10 locally): `MCP_STDIO_SIDECAR_ENABLED=true` + `MCP_STDIO_RUNNER_WITH_BROWSERS=true` were set in `deploy/compose/.env` so the browser-capable runner serves dynamic MCP spawns. The live boot caught two wiring bugs unit tests could not - `final` on a `@Transactional` bean (CGLIB) and a two-ctor bean without `@Autowired` - both fixed in follow-up commits on the PR before the stack went healthy. Note: the edge media-gen agent's `git stash` earlier swept this work's tracked files into `stash@{0}` ("WIP on feat/edge-media-gen"); they were recovered and are what the PR carries - that stash should not be popped onto the edge branch.
- 2026-10-25, agent-loop forensics agent (Devin, same branch/PR #166): after a live incident report
  (133 s / 125k tokens / hallucinated reply on a parked SUPERVISED turn) audited the tool loop and
  fixed, on `df499ddcd`: parked/queued tool results now carry an `approval_parked` marker that ends
  the invocation (soft stop then hard stop) instead of letting post-park calls loop unbudgeted;
  dispatch plumbing (`search_tools`, `call_tool`, `restore_tool_result`) is refused behind a park
  without minting an approval request; the tool-loop round backstop follows the turn's own ceiling
  instead of the platform's largest; platform-origin tool answers registered with `ToolResultMarker`
  bypass the untrusted-content screen (the platform's own "call search_tools first" hint was being
  withheld as prompt injection); `search_tools` no-match answers now list the deferred catalog
  (names, groups, generic fallbacks, `select:`). Also cleared the app-module gate fallout from the
  KB-url work: `POST /api/knowledge/ingest-url` joins the PERM_MANAGE_AGENTS rule, knowledge-write
  endpoints allowlisted, openapi.json + SDK regenerated, tool-description registry defaults updated.
  Verified: chat 1113 / guardrails 483 / agents-tools 1408 / agents-turn 2189 / app gate tests all
  green; rebuilt and deployed to `vegaduta-personal` - core healthy in ~14 s, `time` MCP reconnected.
  KNOWN PRE-EXISTING ISSUE (not touched): `scripts/ci/file-size-baseline.txt` is comments-only in
  this repo (entries lost in the history rewrite) which makes check-file-size.sh pass vacuously -
  awk's FNR==NR treats the whole scan as baseline when the baseline file is empty. A real baseline
  reset needs a maintainer decision (merge-base ratchet fails on any newly added path).
- 2026-10-25, agent-loop forensics agent (Devin) - ACTIVE CLAIM, uncommitted in-flight work on
  `fix/kb-url-ingest-mcp-static-upgrade` (PR #166). Claiming these files for the remaining audit
  findings (F2/F5/F6/F7/F8/F9): `core/chat/.../ArgumentSanitizingToolCallingManager.java`,
  `ToolLoopTranscript.java`, `ToolLoopRunHooks.java`, `ToolLoopProperties.java`;
  `core/agents-turn/.../turn/{ChatTurnExecutor,ChatTurnDispatcher,ChatTimeouts,TurnTimeLimit,
  FastStreamTurn,FastStreamContinuation,BufferedTurnCalls,BufferedTurnContinuation,ChatServiceParts}.java`;
  `core/agents/.../tools/{McpToolOutputSanitizer,TenantContextPropagatingToolCallback}.java`;
  `core/agents-tools/.../search/*` and `tools/{ToolResolver,ToolResolverConfiguration,
  ToolSearchDeferral}.java`; `core/app-config/.../application.yml`; `scripts/ci/check-file-size.sh`
  + `file-size-baseline.txt` (reseeded - the empty-baseline FNR==NR vacuity is FIXED, the gate is
  real now; baselined files may not grow).
  ⚠ OVERLAP: an agent on `fix/search-capability-honesty` (worktree /private/tmp/vd-capfix) has
  UNCOMMITTED work on some of the same files - their prompt-token budget
  (ToolLoopTranscript.maxPromptTokens, ToolLoopRunHooks.overPromptBudget, toolresult.tool_budget.tokens)
  and web_search unavailable-stub (BuiltInWebTools, WebSearchToolCallback) are complementary, not
  conflicting semantically. Merge guidance for whoever lands second: take BOTH - their token-budget
  seam + my cumulative-rounds/FAST-ceiling changes touch disjoint methods of the same files
  (ArgumentSanitizingToolCallingManager, ToolLoopTranscript, ChatTurnExecutor, application.yml,
  tool-loop.properties).
- 2026-10-25, agent-loop forensics agent (Devin) - CLAIM COMPLETE, committed as 8d4956ad8 on
  `fix/kb-url-ingest-mcp-static-upgrade` (PR #166): FAST wall-clock ceiling (FastTurnCeiling),
  cumulative round budget (roundsSpentBefore across passes), Redis ToolActivationStore,
  no-identity call_tool refusal bound, marker-through-wrap + catalog build-time screening
  (SearchCatalogScreen), file-size ratchet repaired + reseeded then shrunk (dispatcher 688,
  executor 769, BufferedTurnCalls extracted under cap). Overlap guidance for
  fix/search-capability-honesty stands (their stash@{0} in this clone is just my note file;
  their real work is committed at 374fdf6ac on their branch).
- 2026-10-25, Devin - SDK publishing repair on `fix/kb-url-ingest-mcp-static-upgrade` (PR #166):
  sdk/java bumped 0.1.0 -> 0.1.1 (sdk-v0.1.0's Central upload was rejected - plugin 0.7.0 produced a
  bundle with no .pom in any artifact dir; 0.12.0's bundle verified correct via local dry-run;
  coordinate retired since its GitHub Release already shipped). Added <organization> to the parent
  pom, NOTICE to license-files in sdk/python-agent and sdk/python (Apache-2.0 §4 - verified in both
  wheels+sdists). Releasing-sdk.md gained a credential-free bundle dry-run recipe + the incident
  note. Remaining one-time setup is maintainer-side: tag sdk-v0.1.1 then run SDK release on it,
  tag pysdk-v0.1.0 for the Python SDK (SDK_PUBLISH_PYPI already true; needs the `pypi` GitHub
  environment + PyPI trusted-publisher entry), restsdk-v* needs SDK_PUBLISH_NPM + NPM_TOKEN + the
  npm name reserved first.
- 2026-10-25, Devin - ACTIVE CLAIM, scratchpad feature on `fix/kb-url-ingest-mcp-static-upgrade`
  (PR #166): three parallel workers. Files: `core/app/.../tenant/V672__agent_scratchpad.sql`,
  `core/agents-tools/.../tools/scratchpad/**` (entity/repo/service/properties + callbacks + tests),
  `core/agents/.../BuiltInToolClassificationsExtended.java`, `ToolResolverConfiguration.java`,
  `ToolSearchDeferral.java` (deferred-name list), `core/app-config/application.yml`,
  `deploy/compose/.env.example`, `docs/configuration.md`, `CHANGELOG.md`, `web/messages/en.json`,
  `web/app/lib/api/fixtures/settingsCatalog.snapshot.json`, `docs/prompts.md`.
- 2026-10-25, Devin - ACTIVE CLAIM, strict HITL + terminal-only streaming on
  `fix/kb-url-ingest-mcp-static-upgrade` (PR #166): parked turns close without a
  model call (`ArgumentSanitizingToolCallingManager`, `ToolLoopParkedClose`,
  `message.approval.parked` prompt, `ChatPrompts`), streamed turns emit only the
  terminal round (`TerminalRoundStreamAdvisor`, `app.chat.parity.fast-terminal-rounds-only`,
  `ChatParityProperties`, `parity.properties`, `application.yml`,
  `docs/configuration.md`, `CHANGELOG.md`, `scripts/ci/file-size-baseline.txt`),
  plus null-translate guard + soft/hard-stop extraction (`ToolLoopSoftStops`,
  `FastStreamRecovery` comment, `FastStreamTurn`).
- 2026-10-26, Devin - CLAIM COMPLETE, shared-agent on-device voice on `feat/share-on-device-voice`
  (PR #201): `web/app/agent/[shareId]/` gained `shareLocalTurn.ts` (whisper on-device -> shared
  /chat -> kokoro/browser voice), `shareLiveCall.ts` + `useShareVoiceSetup.ts` (extractions),
  `ShareVoiceSetup.tsx` (one-tap STT+TTS downloads on the visitor's device), plus panel/page edits
  for the local turn fallback and stronger loading indicators. ALSO on the branch: dropped the
  self-referential `web/node_modules` symlink that was tracked in git (broke every local npm run),
  and two main-repair fixes - `GenerationClaimGuard` extracted from `ChatTurnExecutor` (spotless
  growth had pushed it to 837 > the 769 baseline; now 763) and the `ChatTimeoutsConversationPolicyTest`
  constructor seam updated for `preTurnMaxWait`. Docs touched: `docs/local-models.md`,
  `docs/personal-mode.md` - ⚠ OVERLAP with the docs agent's open PR #203 (`docs/today-changes`)
  on those same files + CHANGELOG/coordination-log: my edits are additive in different sections;
  whoever lands second takes BOTH hunks.

### Rollout note: prod fixes 2026-10-26 (Devin)

- **Stale-IP outage fixed on `vegaduta-ai-az-core`**: `MCP_STDIO_SIDECAR_URL` pointed at dead
  external IP `52.172.129.99:8097` (reassigned long ago). Repointed to
  `vegaduta-ai-az-mcp-stdio-runner.internal.mangosand-cef7f848.centralindia.azurecontainerapps.io:8097`
  (TCP ingress, exposedPort 8097). This was the root cause of "MCP tools not available to agents".
- `TOOL_SANDBOX_BASE_URL` (`http://52.172.129.99:8095`) and `LIVEKIT_URL` (`ws://52.172.129.99:7880`)
  point at the same dead IP AND their container apps do not exist in `vegaduta-ai-az-rg` at all.
  `TOOL_SANDBOX_ENABLED` set to `false` for honest unavailability; LiveKit needs a real deployment
  (or the env var cleared) before voice works on prod.
- **Version skew fixed**: `vegaduta-ai-az-code-intel` and `vegaduta-ai-az-app-builder` were running
  the pre-0.1.8 image `9a8139f2` while core/web ran `0.1.10`. Pushed amd64 `0.1.10` to
  `vegadutaaiazacr` ACR (NB: `docker tag && docker push` of a local tag pushes the LOCAL arch -
  pull `--platform linux/amd64` by manifest digest first) and updated both apps; healthy at
  traffic weight 100.
- Not fixed (needs user/GCP-side work): Google OAuth env is fully configured
  (`GOOGLE_OAUTH_CLIENT_ID` set, redirect `https://api.vegaduta.ai/oauth/google/callback`,
  console redirect `https://app.vegaduta.ai/tools`); if connect still fails the remaining causes
  are on the Google Cloud OAuth client itself (redirect URI not registered, consent screen not
  published, missing scopes). `GOOGLE_OAUTH_MODE` should be `web` (or unset - the default).







MERGE/RELEASE (Devin, 2026-10-26): PR #217 merged at 15:06 BEFORE the
last five branch commits (CORE_INTERNAL_URL, personal invites, personal
code-intel, golden fix, AGENTS rule 14), so v0.1.11 (tagged on the merge)
failed its own release gate. Followed up: #218 (the five commits),
#219 (editionCapabilities/personalInstall test updates - the web job
pinning the same deny row), #221 (ToolSearchDeferral async hop carried
no tenant - AsyncHopTenantScopeRulesTest on main; TenantScope.wrapSupplier
applied). v0.1.11 retagged to the fixed HEAD (c7e35d31d) - the first
release run produced no artifacts, so the move invalidates nothing.
ADD (Devin, 2026-10-26): `code-intel` service on the personal tier - the
profile-gated service was missing from personal.yml entirely (the whole
reason repo management showed nothing on local installs). New `code-intel`
profile + service (auth_mode=internal - personal has no Keycloak; core and
the console proxy carry X-Internal-Token) + core wiring
(CODE_INTEL_ENABLED/BASE_URL/INTERNAL_TOKEN) + code-intel-data volume.
Live and healthy on the local personal stack.
FIX (Devin, 2026-10-26): "users can't sign up on a personal install" - by
design POST /api/signup is closed in local mode (anyone on the LAN would
get a full tenant on the owner's box) and the proxy 404s /signup AND the
admin pages the owner would use instead. Un-denied /admin/access and
/admin/users (a redirect to access) in web/app/lib/edition.ts: the
LocalUserAdminClient invite path (createUserForInvite + issueInviteLink,
minting /login?setup= links since local mode has no mail server) is the
shipped way to add people on personal - the UI was the only missing piece.
ALSO: the personal stack itself was crash-looping - .env leaked
CORE_PROFILES_EXTRA=,workflow,temporal into personal core, which has no
Temporal service (env_file vs environment: scoping). Removed the extra
profiles; personal core is healthy.
FIX (Devin, 2026-10-26): the local-auth setup screen failed "Could not reach
the server" on submit - web's server-side proxy routes fetch core via
CORE_INTERNAL_URL which compose never set, so they fell back to the public
host:port inside the container. Wired CORE_INTERNAL_URL (default
http://core:8080) into web-env + documented it in .env.example; the unlock
proxy now returns real responses (passkey challenge verified end-to-end).
SPLIT (both agents, 2026-10-26): a parallel agent's `ruff format` commit grew
app/main.py (700) + app/mcp_bridge.py (672) over the 665-line ratchet. The
other agent landed `analysis_routes.py` + `deps.py` (shared service/tenant)
+ `mcp_analysis_tools.py` + `mcp_tool_schema.py` - I removed a dead
dispatch_analysis duplicate and verified include_router mounts all 12
routes via TestClient (this FastAPI version defers include into
_IncludedRouter placeholders; import-time app.routes listing is misleading).
LOCAL DEPLOY + local auth (Devin, 2026-10-26): deployed the branch's images to the
local full stack (`docker compose ... build code-intel core web`, `up -d`) - all healthy;
/new analysis endpoints verified live against a smoke repo (map/symbols/api-surface/
dead-code all answered). Wired the two documented-but-unmapped auth-mode vars into
docker-compose.yml (`APP_AUTH_MODE` on x-core-env, `NEXT_PUBLIC_AUTH_MODE` on web-env,
`WEB_AUTH_MODE` in .env drives it) - the deployment now runs `app.auth.mode=local`, so
the console's own unlock screen handles owner setup (password/passkey/PIN/recovery).
Verified: LocalFirstRunBootstrap emitted the setup link, `/api/auth/local/unlock/*`
endpoints live, `window.__ENV` carries `NEXT_PUBLIC_AUTH_MODE:"local"`.
### CLAIM (PR #220 open): approval-resume + desktop watch/learning (Devin, 2026-10-26)

Branch `fix/desktop-resume-learning`. Three reported defects: (1) "carry on" after an
approval has no context — continuation only fires when the request's replay envelope
carries the chat identity, and nothing fills the gap when it can't; (2) the desktop
watch panel dies with the console tab — adding a document-PiP popout on
DesktopScreenDock; (3) recipe replays bypass the tool journal and nothing proposes a
recipe after a successful desktop run. Touches `core/agents-turn`,
`core/agents-tools` (desktop + approval executor), `web/app/components/desktop`.
Do not rebase or amend the branch while the claim is open.

Shipped on PR #220: the factory derives the conversation identity from the fields
every request carries (scopedSessionId over tenant/agent/subject/session, goal from
session history, a derived id with no history is refused); DesktopScreenDock pops
out to a document-PiP window that keeps polling while the console tab is hidden;
DesktopRecipeStepJournal journals each replayed step minus secret fields;
app.learning.desktop-recipes.enabled (personal profile) files a DESKTOP_RECIPE
proposal applied through the new agents-kernel DesktopRecipeSavePort. The
dispatcher learning-loop wraps moved to TurnCompletionObservers (ratchet,
baseline 674).

Follow-up on the same branch: `app.tools.search.enabled` defaults ON in the
personal profile (`TOOL_SEARCH_ENABLED`); a relevance-trimmed tool was otherwise
unreachable for the turn and the model reported "limited tools". Personal
`.env.example`/`configuration.md` updated.

### CLAIM COMPLETE: services/code-intel capability expansion (Devin, 2026-10-26)

Shipped on `feat/code-intel-out-of-the-box`: 12 deterministic read-only analysis
endpoints + agent tools - repo map (PageRank skeleton under char budget), find_symbols,
symbol_detail (exact body), outline, references (calls+inherits+value/type refs),
implementations, call-path BFS, dead-code candidates, tests-for linkage, api-surface
(FastAPI/Flask/Express/Spring/Go/Rust/ASP.NET route extraction), hotspots (git churn x
size), import-cycles (file-level SCCs). Extraction gains signature/doc/is_test on symbols,
`references` edges, api_routes table (schema auto-migrates via ALTER). Core side:
`CodeIntelAnalysis` (split out of CodeIntelClient for the 700-line ratchet) +
spec-driven `CodeIntelAnalysisToolCallback`/`CodeIntelAnalysisTools` - all togglable per
agent via existing allowedToolNames/ToolsGrid, scoped by allowedCodeIntelRepoIds.
Workbench: repo-access editor un-gated from codeWorkspaceEnabled (read-only agents
couldn't reach their own scope). Tests: tests/test_analysis.py 27 checks +
CodeIntelAnalysisToolCallbackTest 6 tests; full suites green (ci ~380 py, codeintel 38,
agents-tools 1531). mcp_bridge also fixed: GET params were sent as a JSON body FastAPI
ignored - now real query strings.

### v0.1.12 release cut (Devin, 2026-10-26)

The v0.1.11 release run failed at the verify gate (CI on the tagged commit
399ee43aa failed on BrowserToolDescriptionGoldenTest - fixed later by #218);
the v0.1.11 tag remains but no GitHub release was ever published for it.
Rather than retag a pushed ref, the cut moves to v0.1.12 at the post-#223
main tip: approval-resume + desktop watch/learning (#220), deferred tool
search in the personal profile + tool-sandbox export fix (#223), code-intel
suite (#217) and the release-follow-up fixes (#218-#224).

### Release-pipeline hardening (Devin, 2026-10-26)

v0.1.12 exposed three release-workflow gaps: verify's 60-minute poll window is
shorter than a serialized main-CI queue (two consecutive merges timed it out),
it could not tell "CI queued" from "CI never ran", and prepare gated nothing -
a tag on a non-main commit or a version with no CHANGELOG section failed only
late. Fixed in release.yml (main-ancestry + CHANGELOG-section gate in prepare;
150-attempt wait + early no-run detection + actions:read in verify) plus
scripts/ci/tag-ready.sh, a pre-flight humans run before tagging; releasing.md
step 1 now points at it.

### ACTIVE CLAIM: scorecard gaps (Devin, 2026-10-27)

OpenSSF >8 push, split to avoid colliding with the claim below: taking
log4j-api GHSA-qv9r-c865-cp47 (transitive via log4j-to-slf4j, pinned to
2.26.1 - PR #230), the dependabot queue triage, and FuzzingID research.
Pushed `40c261f22` onto this branch for #228 - gate fixes only, not the
security content: ruff-format services/docling, dependabot.yml coverage
for the new service, and the psycopg.sql facade the test fake needed
after the baas.py sql-composition change. Verified locally before push.
MaintainedID score-0 is unfixable - the Oct-19 history rewrite makes the
project read <90 days old; it self-heals with age.

### ACTIVE CLAIM: security hygiene + release/CI hardening (Devin, 2026-10-26)

Working the OpenSSF Scorecard to >8 and clearing the open code-scanning
backlog: branch protection on main (required reviews + required checks),
Pinned-Dependencies fixes across Dockerfiles/workflows/scripts, Trivy
Dockerfile findings, CodeQL critical/high triage (partial-ssrf docs shim,
SQL/path injection, user-controlled-bypass, CSRF, ReDoS, weak crypto,
missing origin checks), the bulk agents-turn log findings, the undici
Dependabot alert, and a new-alert gate in CI. Touching: .github/workflows/,
deploy/omnibus/Dockerfile, services/*/Dockerfile, scripts/ci/,
core/agents-turn logging sites, services/code-intel, services/app-builder,
core/graphmemory, core/security, core/agents-jobs, docs/marketing.

### Docs: local-model wire protocol + recovery surface (Devin, 2026-10-27)

local-models.md gained the missing pieces: "Option 4: bring your own local
server" (vLLM, llama.cpp server, LM Studio, Jan, LocalAI - the LOCAL_OPENAI
provider path, including the allowed-private-hosts gate and host.docker.internal
guidance) and "The wire: what core sends to a local model" (the endpoint table:
/v1/models, /api/tags, /v1/chat/completions; the per-server option fields
num_ctx/num_batch/keep_alive/reasoning_effort/response_format/tool_choice; and
the SSRF-guard mechanics). getting-started.md + operations/README.md gained the
previously undocumented `scripts/vegaduta reset-password` local-account
recovery path.

### Voice: piper local TTS provider (Devin, 2026-10-27)

services/voice-agent gained a third TTS backend: app/tts_piper.py wraps the
`piper` subprocess CLI in the same one-shot ChunkedStream shape as
tts_sarvam.py; VOICE_AGENT_TTS_PROVIDER=piper selects it (deliberately not in
"auto"'s ordering - a local voice is a conscious quality pick). The binary is
the MIT rhasspy/piper release tarball, fetched per-arch + sha256-pinned in the
Dockerfile via piper_voices_download.py --binary (the piper-tts PyPI package's
>=1.3 releases are the GPL-3.0 piper1-gpl lineage, which dependency review
rejects). Dockerfile also bakes en_US-lessac-medium (+json) sha256-verified.
TTS selection moved to app/tts_select.py (main.py file-size ratchet). Verified:
3 tts_piper pytest green, ruff clean on changed files, imports resolve.
### CI: fuzz workflow -> ClusterFuzzLite (Devin, 2026-10-27)

Scorecard's Fuzzing check only detects CFL/OSS-Fuzz integration, not a bare
jazzer call, so .github/workflows/fuzz.yml now drives
google/clusterfuzzlite/actions (sha-pinned v1). New .clusterfuzzlite/
(project.yaml language:jvm, Dockerfile with pinned Temurin 21 + maven,
build.sh) packages the two sanitizer targets into the canonical $OUT layout
with jazzer launchers. Triggers/fuzz windows unchanged. Follow-ups on the
same PR: build.sh jlinks JRE 21 into $OUT (the CFL run container ships JDK
17 - UnsupportedClassVersionError - and jazzer_driver needs jdk.attach +
JVM_LD_LIBRARY_PATH repointed; --cp globs expanded at run time). Dispatched
run 40555305979: RedactionFuzzer 590,710 + ScrubberFuzzer 608,809 execs, 0
crashes. Gate fixes: /.clusterfuzzlite added to dependabot docker dirs;
--no-install-recommends (DS-0029); scoped .trivyignore entries for the
build-only image (DS-0002/DS-0026) and the spring-webmvc 6.2.19 CVEs whose
fix is the Spring 7 migration (#99) - the CVE exemption is also PR'd to
main directly as #261 since it blocks every open PR's Trivy scan.
