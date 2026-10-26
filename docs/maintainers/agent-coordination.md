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
| Build/troubleshoot agent (Devin) | local editor, branches `fix/studio-waiting-progress`, `feat/local-model-gates` | `web/app/build/studio/**` (turn progress text, cloud timeout, SEARCH/REPLACE marker-leak fix) and the local-model capability gate: `localModelManagedHere` in `web/app/lib/api/localModels.ts`, `web/app/troubleshoot/page.tsx`, `web/app/providers/{page,local-model/page}.tsx`, `web/app/lib/personalInstall.ts` (route table) - overlaps the release-readiness lead's `web/app/**` claim, so the PRs say so in their titles and wait for their review |
| Production agent | local editor | the production deployment: which release it runs, its configuration and its verification. Does not change application code without a PR |
| Agent-authored tools agent (Devin) | local editor, branch `feat/agent-authored-tools` (PR #156) | agent-authored custom tools: `services/tool-sandbox/**` (JS/TS runtime + jsvalidate + Dockerfile + tests), `core/agents-tools/**` (`CreateCustomToolToolCallback`, `CustomCodeToolEnableApprovalExecutor`, `CustomCodeToolService`, `AgentCustomToolResolver`, `ToolResolverConfiguration`, `ToolSourceConfiguration`, `AgentToolDiscovery`), `core/agents/.../CustomCodeToolLanguage.java` + `BuiltInToolClassifications`, `core/approval/ApprovalPolicyService`, `core/agents-jobs/.../ApprovalActionLabel`, `web/app/components/customCodeTools/**`, `web/app/lib/edge/codegen.ts`, `deploy/compose/{personal,lite}.yml` sandbox service + `scripts/vegaduta` profile wiring, `tests/install/vegaduta-personal.test.sh`, `docs/configuration.md`, `CHANGELOG.md` - overlaps the release / install agent (launcher, compose, install tests) and the release-readiness lead (web/app), so the PR says so in its title and waits for their review |
| KB-URL-ingest / MCP static-upgrade agent (Devin) | local editor, branch `fix/kb-url-ingest-mcp-static-upgrade` | web-document ingestion into the knowledge base (`core/knowledge` `KnowledgeUrlFetcher`/`KnowledgeUrlIngester`, `AddToKnowledgeBaseToolCallback` `url` arg, `KnowledgeController` `POST /api/knowledge/ingest-url`, `IngestUrlRequest`), per-agent KB write config (`core/agents` `AgentController` knowledge-write endpoints + new DTOs), dead static-MCP repair (`core/mcp` `McpStaticReferenceUpgrader`, `McpStaticServerAvailability`, `McpReferenceServerCatalog` launch specs, `McpRegistryService`/`McpRegistryController`, `core/agents-tools` `StaticMcpServerAvailabilityAdapter`, `AgentKnowledgeToolResolver` + `ToolResolverConfiguration`), `web/app/lib/api/agents.ts`, `web/app/components/agents/workbench/{KnowledgeSection,KnowledgeWriteSection}.tsx`, `web/messages/en.json`, `deploy/compose/{personal.build,lite,docker-compose}.yml` + `.env.example`, `core/app-config/application.yml`, `docs/configuration.md`, `CHANGELOG.md` - overlaps the release-readiness lead (`web/app/**`, `core/agents-tools`, docs), the release / install agent (`deploy/compose`, `.env.example`) and the agent-authored-tools agent (`ToolResolverConfiguration`), so the PR says so in its title and waits for their reviews |
| Capability/latency/auth agent (Devin) | local editor, worktree `vd-capfix-wt`, branches `feat/ollama-auto-context`, `feat/local-password-reset`, `docs/today-changes` | local-model capability + turn latency + local-auth UX: `core/providers/localmodels` (context self-heal, `LocalModelOllamaGateway`, `LocalModelsService`), `core/admin` + `core/security` `localauth` (reset-request endpoint, `password-reset.txt`), `scripts/vegaduta{,.ps1}` reset-password, `web/app/components/localauth/SignInPanel.tsx`, `web/app/lib/localAuthProxy.ts`, `web/messages/en.json`, docs + CHANGELOG - overlaps the release/install agent (`scripts/vegaduta`, `.env.example`) and the release-readiness lead (`web/`, `docs/`), so the PRs say so in their titles and wait for their reviews |
| Search-capability-honesty agent (Devin) | local editor, worktree `vd-capfix-wt`, branch `fix/search-capability-honesty` | fixes for the incident where an agent with no configured search provider told the user it had no web access, then burned ~125k prompt tokens answering from memory: `core/agents-browser/.../web/WebSearchToolCallback` + `WebSearchProviderWiring` + `BuiltInWebTools` (a refusing `web_search` stub instead of silent absence, boot WARN on an unconfigured chain), `core/chat/` `ToolLoopTranscript`/`ToolLoopRunHooks`/`ToolLoopProperties`/`ArgumentSanitizingToolCallingManager`/`ChatPrompts` + `tool-loop.properties` + `prompts/en/toolresult.tool_budget.tokens.md` (cumulative per-run prompt-token soft stop, `app.chat.tools.maxPromptTokensPerTurn`), `core/agents-turn` `ChatContextCompaction`/`ContextCompactionProperties` (reserve headroom off the window) + `ChatTurnExecutor`/`BehaviourSettingsCatalog` (`chat.toolLoop.maxPromptTokensPerTurn`), `core/app-config/application.yml`, `web/messages/en.json` + the generated `settingsCatalog.snapshot.json`, `docs/configuration.md`, `docs/prompts.md`, `CHANGELOG.md` - **overlaps the KB/MCP agent's parallel audit work** (their `ArgumentSanitizingToolCallingManager`/`ChatTurnExecutor`/`application.yml`/`tool-loop.properties`/`docs/configuration.md`/`CHANGELOG.md` edits; see `AGENT-COORDINATION-NOTE.md` they left in the worktree - our edits sit in disjoint methods, take both on merge) and the release-readiness lead (`web/`), so the PR says so in its title and waits for their review |
| Desktop-recipes lead (Devin, 5-agent fan-out) | local editor, worktree `vd-capfix-wt`, branch `feat/desktop-recipes` (PR #168) | "record once, replay later, monitor-or-auto" for the PERSONAL tier's `local_desktop_*` surface, mirroring browser recipes: new `desktop/` classes in `core/agents-tools` (entity `desktop_recipe` - spec/validator/recorder/replayer/service, `/api/desktop/recipes`, `run_desktop_recipe`, `/api/desktop/monitor/frame` proxy), `DesktopToolSource`/`DesktopAgentClient`/`RunBrowserRecipeToolCallback`/`BuiltInToolClassifications*` small edits, `services/desktop-agent` (`GET /frame`, `GET /capabilities`, bearer-auth on every route), `application.yml` + `.env.example` + `docs/configuration.md`, `web/app/components/desktop/**` + `web/app/lib/api/desktopRecipes.ts` + in-turn live monitor + console screen dock, `CHANGELOG.md` - feature is OFF by default, personal-tier only. V672 was adopted by the scratchpad agent, so the recipe table renumbered to a fresh version. Overlaps the release-readiness lead (`web/app/**`), so the PR says so and waits for their review |

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
