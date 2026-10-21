# Agent brief: close the UI gaps, the backend leftovers, split the big files, start the architecture bets

Copy the block under "The prompt" into a fresh coding agent. Everything it needs is in the repository:

- `docs/engineering/UI-GAPS-OPEN.md`: 118 open or partial console gaps with evidence and the fix (17 billing items are deferred).
- `docs/engineering/BIG-FILES-SPLIT-PLAN.md`: every source file over 1,000 lines and the design pattern to split each by.
- `docs/architecture/ARCHITECTURE-BETS-2026-10.md`: thirteen proposed architecture bets, each with a first slice and an acceptance test.
- `docs/console/`: the console guide (what each page is for); `web/dev/ui-preview/`: a dev-only harness to render pages without signing in.

## Backend leftovers (non-billing)

> **Status, checked against the code on 2026-10-21.** Done: 2 (`actor` and `q` on `GET /api/audit-log`),
> 3 (`label` on `WorkflowVersionResponse`, `accessLevel` on the agent and workflow responses), 4 (the opt-in
> card reads `GET /api/channels/whatsapp-compliance/opt-in`), the file-size part of 5 (all five files are under
> the cap and out of the baseline), 6's compose part (`docker-compose.yml` passes
> `APP_SECURITY_OUTBOUND_EGRESS_ALLOW_LIST`) and the generated reference
> ([CONFIG-REFERENCE.generated.md](CONFIG-REFERENCE.generated.md)), 7 (the seed's ROI is now about 276%) and 8
> (see [I18N.md](I18N.md)). Partly done: 1 (`GET /api/agents`, approvals, coding tasks, agent jobs and platform
> audit search are paged). Not rechecked: the A2A authorization rules, the mkdocs nav and OpenAPI descriptions.

1. **Server paging still missing** (add `limit`/`offset` with `X-Total-Count` and `X-Has-More`, following `docs/api/LIST-ENDPOINT-PAGING.md`): `GET /api/agents` and the agent directory's `mine`, `GET /api/agents/{id}/jobs`, `GET /api/coding-tasks` (the Redis store hard-codes the newest 20), platform audit search, platform activity/abuse/billing-free lists, `GET /api/approvals` (the console pages the full list client-side), leads board (200 per column cap), image/audio/video job lists, knowledge and template lists that still cap silently.
2. **Audit log filters:** `GET /api/audit-log` cannot filter by actor or free text server-side; add `actor`, `q` and make the console filters server-side.
3. **Workflow versions** list returns no STAGING/PRODUCTION label state; add it. Expose the caller's **effective access level** (RUNNER/VIEWER/EDITOR) on agent and workflow GET responses so the UI can gate write actions instead of failing with a toast.
4. **Connect the new endpoints to the console:** the WhatsApp opt-in list endpoint exists (`GET /api/channels/whatsapp-compliance/opt-in`) but the opt-in card does not call it yet; the share status now returns `revokedReason`/`revokedAt` (verify both share panels show it).
5. **Gates that fail on `main`:** `AuthorizationCoverageTest` lists 12 A2A REST mutating endpoints with no explicit authorization rule (add the rules, do not allowlist them); the file-size ratchet fails on `LlmMetricsAdvisor.java`, `WorkflowExecutionEngine.java`, `WorkflowNodeExecutor.java`, `WorkflowService.java` and `agentTemplates.ts` (fixed by the splits below); `web/app/platform-admin/lib/safeLink.test.ts` fails (expected `/platform-admin/audit?x=1`, got `/audit?x=1`).
6. **Config and docs drift:** `deploy/compose/.env.example` documents `APP_SECURITY_OUTBOUND_EGRESS_ALLOW_LIST` but `docker-compose.yml` never passes it to core; about 500 older environment names in `application*.yml` are missing from `docs/configuration.md` (generate that reference from `@ConfigurationProperties`); 25 docs pages are not in the `mkdocs.yml` nav; only 17% of OpenAPI operations have a description.
7. **Demo data:** `scripts/demo/seed-demo.sql` produces an ROI of about 58,000% (USD 10 value per resolution against about USD 1.44 cost); use modest, round example values.
8. **Console strings:** 29 locales hold about 55% of the English keys and carry about 7,000 keys that no longer exist; add a parity script and a CI check, prune dead keys, and make new screens translatable.

Out of scope: subscription billing, plans, top-up and payment-gateway work.

## The prompt

```
You are working in the VegaDuta open-source repository (read AGENTS.md first; it has rules that are easy to break: one Maven build at a
time, -pl X -am does not build dependents, Flyway versions are one namespace, no localhost in browser URLs, architecture baselines only
shrink, files over 665 lines (hard cap 700) fail the ratchet, tenant context across threads, bean/entity name collisions).

GOAL: finish the console UI, close the backend leftovers, split the oversized files with proper design patterns, and start the
architecture bets. Work in a git worktree on a new branch from main. Do NOT deploy to the stage cluster, do NOT touch billing,
plans, top-up or payment-gateway code (not a priority for the open-source codebase), and do not push: leave commits on the branch
and report.

READ FIRST (all in the repo):
- docs/engineering/UI-GAPS-OPEN.md            118 open/partial console gaps with evidence and fix
- docs/engineering/AGENT-BRIEF-NEXT-PHASE.md  backend leftovers list (this file)
- docs/engineering/BIG-FILES-SPLIT-PLAN.md    files over 1000 lines and the pattern to split each
- docs/architecture/ARCHITECTURE-BETS-2026-10.md  13 proposed bets with first slices and acceptance tests
- docs/console/README.md                       what each console page is for
- web/dev/ui-preview/README.md                 render real pages without signing in (fake admin + fixture API)

WORK IN THIS ORDER, committing after each numbered item (Conventional Commits, git commit -s):
1. Shared building blocks the gaps depend on: components/ui select, checkbox and combobox (accessible, tokenised), a shared
   ConfirmDialog, and a lint rule or test that forbids native <select> and window.confirm in console code; move the 86 hard-coded hex
   colours to design tokens; fix the 59 jsx-a11y warnings. Then walk UI-GAPS-OPEN.md top to bottom (blocker, major, minor) and fix
   each item; for every fix add or extend a vitest test, and update the item as done in that file.
2. Backend leftovers 1-8 above. One Maven at a time; run the module tests, the arch guards (do NOT refreeze baselines), regenerate
   docs/api/openapi.json and the SDKs (node sdk/generate.mjs --check must pass). Wire the console to each new endpoint.
3. Split the files in BIG-FILES-SPLIT-PLAN.md, priority 1 first, each by the pattern named there (Strategy/registry for workflow
   nodes, Facade + focused services for AgentService/WorkflowService, Pipeline for HttpRequestExecutor, Composite of ToolSources,
   Observer for LlmMetricsAdvisor, reducer/state-machine + feature slices for AgentWorkbench/Playground/Canvas, data/registry split for
   templates and tier1). Pure refactors: pin behaviour with characterisation tests first, keep public APIs, lower the baseline in
   scripts/ci/file-size-baseline.txt after each split, and make sure no class ends with more than 12 constructor parameters.
4. Architecture bets: for bets 13, 1 and 10 (in that order) write an ADR under docs/architecture/, then implement ONLY the "first slice"
   with its acceptance test. Do not start the other bets; list what you learned that changes their plans.
5. Verify the UI visually with web/dev/ui-preview (install.sh, write fixtures for the pages you changed, screenshot desktop and 375px
   width) and fix what you see. Record what you could not verify.
6. Update docs: docs/console/*, docs/configuration.md, CHANGELOG.md ([Unreleased]), mkdocs.yml nav, and run bash scripts/ci/docs-link-check.sh.

DEFINITION OF DONE: web typecheck, eslint (0 errors) and the full vitest suite green (the one known failure, safeLink, fixed);
mvn -DskipTests package for the whole core reactor, plus the tests of every module you touched; bash scripts/ci/check-file-size.sh,
flyway-lint, bean-name-collision and entity-name-lint pass; AuthorizationCoverageTest green; no new files over 665 lines (hard cap 700); every item
in UI-GAPS-OPEN.md marked done or explained. Report exactly what you ran and its result, separating "compiled", "unit tested" and
"seen in a browser", and list anything not done with the reason.
```
