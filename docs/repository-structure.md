# Repository structure

How the repository is arranged, where each kind of thing lives, and how the pieces are linked:
the platform, the open-source and commercial public pages, the dependencies you embed, the
documentation, the plugins and apps, and the build and release machinery.

Where this page and the code disagree, the code wins. Module-level detail that is generated from
the build lives in [Core module graph](architecture/core-module-graph.md).

## At a glance

```
vegaduta/
├── core/                  Java 21 / Spring Boot reactor: the platform (57 Maven modules)
├── web/                   Next.js tenant console + the public pages (open-source and classic)
├── services/              services: sidecar (Python ML), code-intel, app-builder, tool-sandbox,
│                          mcp-stdio-runner, piece-host, voice-agent, rl-trainer, workspace-runner,
│                          whatsapp-qr (WhatsApp linked-device bridge), browser-runner (Playwright
│                          server for the personal browser add-on), desktop-agent (loopback
│                          computer-use helper for personal installs)
├── sdk/
│   ├── java/              Java Agent SDK (io.github.cd-lc:vegaduta-agent-*, 7 modules + BOM, including the
│   │                      agent-scanner risk-map CLI), its own Maven reactor
│   ├── python-agent/      Python Agent SDK (vegaduta-agent), the governance layer for Python apps
│   ├── typescript/        generated REST client for the platform API
│   ├── python/            generated REST client for the platform API
│   ├── generate.mjs, lib/, test/   the client generator
│   └── README.md          the REST clients (the agent SDKs have their own READMEs)
├── examples/
│   ├── agent-bundles/         importable agent bundles
│   ├── sdk/governed-tools/    runnable Spring Boot app using the Java Agent SDK
│   ├── sdk/refund-agent/      the same support agent with and without the SDK, in Python and Java
│   ├── workflow-templates/    importable workflow templates
│   └── mcp-servers/           example MCP servers
├── docs/                  documentation source (Markdown + the HTML handbook); feature pages sit at
│                          the top (cost-and-roi, local-models, edge-relay, sms-channel, ...), with
│                          pilots/ (30-day pilot process), channels/, compliance/, security/, sdk/,
│                          architecture/, engineering/ (plans, audits, generated config reference),
│                          operations/, maintainers/ and api/ (OpenAPI and AsyncAPI documents, API guides)
├── mkdocs.yml             builds docs/ into the documentation site
├── deploy/                Compose tiers and overlays, Helm charts (22), supporting configs (Postgres,
│                          Keycloak, Prometheus/Grafana/Loki, LiveKit, Temporal, kind, models/catalog.txt),
│                          omnibus/ (the single-container personal image)
├── infra/                 Terraform reference modules (30: Azure, GCP, AWS, Cloudflare, Upstash) and Keycloak
├── contracts/             wire-format contracts (JSON Schemas + fixtures)
├── tests/                 end-to-end (Playwright), load tests, and install/ (installer shell tests)
├── install.sh             the POSIX-sh installer: downloads and verifies a release tarball, then runs
│                          scripts/vegaduta (secrets -> doctor -> up -> URLs)
├── installer/             vegaduta-setup, the Go Setup app: a browser wizard for non-technical installs
│                          (preflight, Docker bootstrap, verified release, first-run link) that reuses
│                          scripts/vegaduta so it cannot drift
├── scripts/
│   ├── vegaduta, vegaduta.ps1   the install/run CLI
│   ├── ci/                static gates (also run by `make gates`)
│   ├── demo/              seed-demo.sql / seed-demo-remove.sql: fictional "Example" tenant data for the
│   │                      Cost & ROI page and the pilot report (`scripts/vegaduta seed-demo`)
│   ├── backup/, keycloak/ backup helpers and Keycloak reconcile scripts
│   ├── dev/               maintainer helpers (identity, labels, git hooks)
│   └── docs/              MkDocs hook (embeds READMEs, rewrites links that leave docs/) and
│                          gen-shapes.py (regenerates the tables in docs/deploy/DEPLOYMENT-SHAPES.md)
├── .github/               workflows, CODEOWNERS, Dependabot, labeler, issue/PR templates
├── Makefile               build / test / lint / gates / docs targets
└── AGENTS.md, CONTRIBUTING.md, README.md, ROADMAP.md, CHANGELOG.md, GOVERNANCE.md,
    MAINTAINERS.md, SECURITY.md, SUPPORT.md, CODE_OF_CONDUCT.md, LICENSE, NOTICE, CITATION.cff
```

## Who starts where

| You are | Start at | Then |
|---|---|---|
| Evaluating the project | [README](https://github.com/CD-LC/vegaduta/blob/main/README.md), the open-source landing page of a running console | [Architecture overview](architecture/overview.md) |
| Running it | [Getting started](getting-started.md) → [Installation](installation.md) | [Configuration](configuration.md), [Operations](operations/README.md) |
| Calling the platform from your code | [SDKs and dependencies](sdk/README.md): TypeScript and Python clients | [API and OpenAPI](api/README.md) |
| Embedding governance in your own Java or Python application | [Java Agent SDK](sdk/java-agent-sdk.md) and its [example](sdk/java-agent-sdk-example.md); [Python Agent SDK](sdk/python-agent-sdk.md) | [ADR-0013](architecture/ADR-0013-embeddable-agent-sdk.md) |
| Running a pilot with a customer | [Running a 30-day pilot](pilots/README.md) | [Cost and ROI](cost-and-roi.md) |
| Using it from an editor, browser or phone | [Plugins, apps and mobile](plugins-and-apps.md) | MCP server in [Extending](extending.md) |
| Extending the platform | [Extending](extending.md) | [Architecture](architecture/overview.md), module graph |
| Contributing code | [CONTRIBUTING](https://github.com/CD-LC/vegaduta/blob/main/CONTRIBUTING.md), [AGENTS.md](https://github.com/CD-LC/vegaduta/blob/main/AGENTS.md) | `make gates` |
| Maintaining or releasing | [Maintainers](maintainers/repo-setup.md) | [Releasing](maintainers/releasing.md), [Releasing the SDK](maintainers/releasing-sdk.md), [Publishing these docs](maintainers/docs-site.md) |

## The platform

### `core/`: Java 21, Spring Boot, one Maven reactor

One parent POM (57 `<module>` entries), a modular monolith. The chat hot path stays in one process (see
[architecture](architecture/overview.md)). Directories, grouped by role; the generated
[module graph](architecture/core-module-graph.md) is the authoritative list of dependencies.

| Role | Directories |
|---|---|
| Foundations | `json`, `crypto`, `crypto-kms`, `security`, `secrets`, `audit`, `storage`, `speech`, `reliability` |
| Tenancy, identity, accounts | `tenancy`, `admin`, `sharing`, `dbauth`, `billing` |
| Agent subsystem ([ADR-0023](architecture/ADR-0023-agents-module-decomposition.md)) | `agents` (kernel: the `Agent` entity, settings, quotas, shared tool vocabulary, ports), `agentspi` (SPI other vendors implement), `agents-budget` (below the kernel), `agents-turn` (the turn path), `agents-tools`, `agents-browser` (web + Playwright tools), `agents-jobs`, `agents-chatport` (import/export), `agents-interop` (A2A, AG-UI, OpenAI-compat), `agents-studio` (app builder, coding, lab, demo, skills) — see [the decomposition index](architecture/agents-decomposition/README.md) |
| AI runtime | `chat`, `providers`, `knowledge`, `graphmemory`, `guardrails`, `quality`, `rl`, `mcp`, `mcpserver`, `codeintel`, `sdlc`, `brief`, `documentgen`, `templates` |
| Workflow and channels | `workflow`, `workflow-expression`, `workflow-portability`, `approval`, `eventbus`, `omnichannel`, `omnichannel-social`, `voice`, `devices`, `platform` |
| Vendor adapters | `elevenlabs`, `google`, `imagegen`, `payments`, `sarvam` |
| Deployable shapes | `app` (API server), `app-jobs` (background jobs; also the optional ingest pool), `app-worker` (Temporal), `app-config` (shared `application*.yml`, including the `shape-*` profiles) |
| Directories that are not Maven modules | `mcp-data`, `mcp-stdio-proxy`, `tools` (holds the module-graph generator) |

Module maturity is uneven — the repo carries the breadth of the product it was
split from, and not every module is equally proven:

- **Core path** (the chat turn, tenancy, auth, deployment): `security`,
  `tenancy`, `chat`, `providers`, `agents`, `guardrails`, `approval`,
  `workflow`, `knowledge`, `audit`, `mcp`, `mcpserver`. Heaviest test
  coverage and the load/verify evidence in `docs/architecture/`.
- **Deeper quality/eval machinery**: `quality` (datasets, graders,
  calibration, attack simulation) is the one evaluation engine; its
  `com.agenticai.core.quality.eval` package (merged from the former `eval`
  module) is the standalone golden-dataset CLI runner (LLM-as-judge,
  Langfuse client) - see `core/quality/eval-harness.md`.
- **Early / niche** (treat as preview: thinner tests, narrower review, may
  move): `reliability` (single-class placeholder), `devices`, `payments`
  (UPI mandates, x402 settlement), `agents-studio/.../demo` and `.../face`, `voice` menus,
  `documentgen`, `codeintel`, `sdlc`, `brief`, `sharing`.

Flyway migrations are one namespace shared by `controlplane/` and `tenant/` under
`core/app/src/main/resources/db/migration/` (382 files on 2026-10-21; the highest version is V661).

Scaling is by replica and workload class, never by a network hop between modules on the chat path:
[ADR-0014](architecture/ADR-0014-horizontal-scaling.md) and [Deployment shapes](deploy/DEPLOYMENT-SHAPES.md)
(which pod runs which scheduled job, running more than one API replica, autoscaling, the database
connection budget).

### Inside the code: where the seams are

Large classes and components were split into small collaborators behind a registry or an interface
([split plan](engineering/BIG-FILES-SPLIT-PLAN.md) has the per-file status). The seams you will meet, with the guide that
explains how to add to each:

| Seam | Location | Guide |
|---|---|---|
| Workflow node handlers (`NodeHandler`) | `core/workflow/.../workflow/*NodeHandler.java`, `WorkflowNodeExecutor`, shared traversal rules in `WorkflowTraversalRules` | [Extending 5](extending.md#5-add-a-workflow-node-type) |
| Tool sources (`ToolSource`) | `core/agents-tools/.../agents/tools/ToolSource*.java`, `ToolResolver`, `Agent*ToolResolver` | [Extending 10](extending.md#10-add-a-tool-source) |
| HTTP request pipeline | `core/agents-browser/.../agents/tools/web/Http*.java` | [Extending 11](extending.md#11-change-the-http-request-pipeline) |
| LLM call sinks (`LlmCallSink`) | `core/chat/.../chat/Llm*.java`, `GenAiSpanRecorder`, `MicrometerLlmMetrics` | [Extending 12](extending.md#12-add-an-llm-call-sink) |
| Turn journal (`TurnJournal`) | `core/agents-turn/.../agents/turnjournal/` | [Extending 13](extending.md#13-record-more-of-a-turn-in-the-turn-journal) |
| Agent settings, access and versions | `core/agents/.../agents/core/Agent*Service.java`, `AgentSettingsPatch`, value objects such as `GenerationParams` | [Architecture overview](architecture/overview.md#internal-seams-of-the-core) |
| Console workflow node forms (`NODE_FORMS`) | `web/app/components/workflow/nodeConfig/` | [Extending 5](extending.md#5-add-a-workflow-node-type) |
| Console channel inspectors (`CHANNEL_ADAPTERS`) | `web/app/components/channels/adapters/` | [Extending 6](extending.md#6-add-a-channel) |
| Catalogues as data | `web/app/components/templates-data/{agents,workflows}`, `web/app/lib/edge/tier1/models`, `web/app/components/tour/steps` | each folder has a registry and a schema test |

### `web/`: Next.js

`web/` is the tenant console, **all signed-out public pages**, and the platform-admin console under
`app/platform-admin/` (merged from the former `web-admin/`; there is one Next.js application, not two).
The console's navigation is defined in `web/app/components/nav/navSections.ts`, which assembles two halves —
`navSectionsAuthor.ts` (Build, Library, Inbox & people, AI & engine, Configuration) and
`navSectionsOperate.ts` (Test & Monitor, Plans, Help, Administration); list screens
share `web/app/components/PaginationControls.tsx`. Public configuration is read at runtime (`window.__ENV`, see
`web/docs/RUNTIME-CONFIG.md`), so one image serves any domain and either site mode.

### `services/`

Optional and isolated runtimes (12 service directories): the Python ML sidecar, code intelligence, the app builder, the tool sandbox
(untrusted code), the MCP stdio runner, `piece-host` (Activepieces pieces as MCP tools; preview, off
by default, see its README), the voice agent, the RL trainer, `whatsapp-qr` (the WhatsApp
linked-device bridge), `browser-runner` (the Playwright server behind the personal install's browser
add-on), `workspace-runner` (the Assistant's shell-command runner for personal
installs, only the projects folder mounted) and `desktop-agent` (the loopback computer-use helper
that runs on the laptop itself, never in a container). `services/lock-requirements.sh` is a helper
that regenerates the hashed Python dependency locks, not a service.

## Dependencies you embed or call

| Artifact | Lives in | For | Documented in |
|---|---|---|---|
| `io.github.cd-lc:vegaduta-agent-*` (Java, 7 modules + BOM) | `sdk/java/` | governance inside your own Java application; the `-scanner` module reports the risk map of an existing jar | [Java Agent SDK](sdk/java-agent-sdk.md) |
| `vegaduta-agent` (Python) | `sdk/python-agent/` | governance inside your own Python application | [Python Agent SDK](sdk/python-agent-sdk.md) |
| `vegaduta-sdk` (TypeScript) | `sdk/typescript/` | calling the platform API | [TypeScript and Python clients](sdk/clients.md) |
| `vegaduta_sdk` (Python) | `sdk/python/` | calling the platform API | same |

The Java SDK is **not** a client of the platform: it runs inside your process and needs no
server. Its modules:

```
vegaduta-agent-api              contracts only: JDK + JSpecify (JPMS module)
 └─ vegaduta-agent-core         reference implementations, the Governance entry point
     ├─ vegaduta-agent-jdbc            durable approvals, effect journal, audit (PostgreSQL, H2)
     ├─ vegaduta-agent-testkit         JUnit 5 / AssertJ assertions
     ├─ vegaduta-agent-scanner         CLI: risk map and readiness score of @Tool/@Governed methods in a jar
     └─ vegaduta-agent-spring-ai       governed ToolCallback, @Governed scanning, GovernanceAdvisor
         └─ vegaduta-agent-spring-boot-starter   vegaduta.agent.* auto-configuration
vegaduta-agent-bom              version alignment
```

Why it is a separate reactor and not under `core/`: different Java baseline (17 vs 21), no
Spring Boot parent (its POMs are published to consumers), its own release cadence (`sdk-v*`
tags) and its own API-compatibility gate. `core/` may consume it later
([ADR-0013](architecture/ADR-0013-embeddable-agent-sdk.md) §6.2).

## Documentation system

| Layer | Source | Audience | Published as |
|---|---|---|---|
| Documentation site | `docs/**/*.md` + `mkdocs.yml` | operators, integrators, contributors | MkDocs Material site on GitHub Pages |
| Embedded READMEs | `sdk/java/README.md`, `sdk/python-agent/README.md`, `sdk/README.md`, `examples/sdk/governed-tools/README.md`, `examples/sdk/refund-agent/README.md` | SDK users | pages of the same site, via `scripts/docs/mkdocs_hooks.py` (one source) |
| HTML handbook (legacy) | `docs/platform-documentation/` | architecture and decisions (ADR-0001 to ADR-0010) from the `agentic-ai` era — the Markdown `docs/` tree is the maintained source; keep the handbook for the older ADRs, prefer `docs/` where they disagree | static files inside the site |
| In-app help | `web/app/help/docs-content.tsx` | console users | `/docs` and `/help/docs` in the console |
| API reference | `docs/api/openapi.json` | integrators | `/api-reference` in the console; basis of the generated clients |
| GitHub-facing files | root `*.md`, `docs/**/*.md` | everyone on GitHub | rendered by GitHub |

Rules that keep it consistent:

- Markdown under `docs/` stays valid on GitHub **and** on the site. Links that leave `docs/` are
  rewritten to GitHub URLs by the hook at build time; the files on disk are unchanged.
- `mkdocs build --strict` runs on every pull request that touches the docs, and
  `scripts/ci/docs-link-check.sh` checks relative links and anchors offline.
- A new page is added to the `nav` in `mkdocs.yml` and to the index in `docs/README.md`.
- New configuration goes into `deploy/compose/.env.example` (if Compose sets it) and
  [Configuration](configuration.md).

Publishing and local preview: [Publishing these docs](maintainers/docs-site.md);
`make docs-serve`, `make docs-build`.

## Public pages: open source and commercial

One console image serves two sets of signed-out pages, selected at runtime by
`NEXT_PUBLIC_SITE_MODE` (`oss` by default, or `classic`). The full wiring map, with every
surface and where it is linked from, is in [Site modes and public pages](site-modes.md).

| | `oss` | `classic` |
|---|---|---|
| Landing | technical landing: stack, standards, install tiers, **Use it as a dependency**, **Where it runs** | original marketing landing |
| Sales routes (pricing, enterprise, hire, earn, compare, ...) | redirect to `/` | served |
| Cross-links | header and footer: Docs, Dependencies, Plugins and apps, GitHub; optional **Hosted version** (`NEXT_PUBLIC_COMMERCIAL_URL`) | footer: Plugins and apps, Java Agent SDK, Open source, Self-host guide |
| Docs link | published site (`NEXT_PUBLIC_DOCS_URL`) or in-app `/docs` | same |

Shared by both: `/plugins`, `/docs`, `/api-reference`, `/integrations`, `/changelog`, `/templates`,
`/contact`, `/terms`, `/privacy`. All link targets that depend on configuration come from one
module, `web/app/lib/siteLinks.ts`.

## Plugins, apps and mobile

The **platform side** is in this repository: the plugin catalog API
(`GET /api/plugins/catalog`), artifact upload and download, the `/plugins` page with the honest
status list (`web/app/components/plugins/surfaces.ts`), the Android APK panels
(`NEXT_PUBLIC_MOBILE_APK_URL`), device pairing (`core/devices`) and the MCP server. The
**client builds** (VS Code, JetBrains, Chrome, Edge, Eclipse, Android, Wear OS) are not in this
tree. See [Plugins, apps and mobile](plugins-and-apps.md).

## Build, test, release

| Workflow (`.github/workflows/`) | Runs | Purpose |
|---|---|---|
| `ci.yml` | every PR and push to `main` | path-filtered jobs: core, **sdk/java**, web, Python, Node services, shellcheck, hadolint, Compose, Helm, static gates, DCO, dependency review, docs links; the single required check is `CI OK` |
| `static-gates.yml` | called by `ci.yml`, or manual | Flyway, bean/entity names, skip expiry, file and module size, generated SDKs and module graph, OpenAPI breaking changes, docs links, internal-jargon and no-telemetry ratchets, secrets |
| `installer.yml` | PRs and pushes to `main` touching `installer/`, or manual | builds and tests the `vegaduta-setup` installer; manual runs also cross-compile the per-OS binaries |
| `codeql.yml` | every PR and push to `main`, weekly | code scanning |
| `scorecard.yml` | pushes to `main`, weekly, on branch-protection changes | OpenSSF Scorecard |
| `e2e.yml` | nightly, or manual | Playwright end-to-end |
| `load-nightly.yml` | nightly, or manual | load gate: 32-user chat benchmark on a throwaway kind cluster with the mock model; fails when p95 turn latency or the failed-turn rate crosses its threshold (`make load-gate` runs the same against an existing stage; see [tests/load/README.md](../tests/load/README.md#load-test-gate)) |
| `docs.yml` | doc-affecting paths on PRs and pushes to `main` | strict site build; deploys to Pages when enabled |
| `trivy.yml`, `zap.yml`, `sonarqube.yml`, `license-scan.yml` | PRs and pushes, or weekly | report-only scans (dependencies and IaC, DAST baseline, code quality, redistributed-file licenses); see [Scan reports](security/scanning.md) |
| `pypi-release.yml` | manual, on a `pysdk-v*.*.*` tag | Python Agent SDK sdist and wheel, provenance, GitHub Release; PyPI upload only when `SDK_PUBLISH_PYPI` is `true` |
| `rest-sdk-release.yml` | manual, on a `restsdk-v*.*.*` tag | generated REST clients (TypeScript npm + Python PyPI, both `vegaduta-sdk`): dists, provenance, GitHub Release; npm upload only when `SDK_PUBLISH_NPM` is `true`, PyPI only when `SDK_PUBLISH_PYPI` is `true`; runbook [Releasing the REST client SDKs](maintainers/releasing-rest-sdks.md) |
| `release.yml` | manual, on a `v*.*.*` tag | platform images, charts, signatures, SBOM, provenance, draft release |
| `sdk-release.yml` | manual, on an `sdk-v*.*.*` tag | SDK jars, SBOMs, provenance, GitHub Release, optional Maven Central upload |
| `labeler.yml`, `stale.yml` | on PR events, daily | labels, housekeeping |

`make` targets: `build`, `test`, `lint`, `gates`, `up`, `load-gate`, `docs-serve`, `docs-build` (run `make help`).

## Where new things go

| You are adding | Put it in | Also touch |
|---|---|---|
| A platform feature | the matching `core/<module>` (new module only if it is a new concern) | `docs/`, `CHANGELOG.md` |
| An LLM provider, workflow node, channel, tool source, LLM call sink | see [Extending](extending.md) | the web registry listed there (`NODE_FORMS`, `CHANNEL_ADAPTERS`) |
| A configuration flag | `core/app-config` or the `NEXT_PUBLIC_*` list in `web/app/lib/env.ts` | `deploy/compose/.env.example`, Helm values, [Configuration](configuration.md) |
| A public-page link to docs, SDK, plugins | `web/app/lib/siteLinks.ts` (`DOC_PAGES`) | [Site modes](site-modes.md), `mkdocs.yml` nav if it is a new page |
| A plugin or app status | `web/app/components/plugins/surfaces.ts` | [Plugins, apps and mobile](plugins-and-apps.md) |
| A new SDK module | `sdk/java/agent-<name>` + `sdk/java/pom.xml` modules and BOM | `sdk/java/README.md`, ADR-0013 |
| A feature page that tenants or operators read | `docs/<feature>.md` (or the matching subfolder) | `mkdocs.yml` nav, `docs/README.md`, `CHANGELOG.md` |
| Demo or pilot data | `scripts/demo/` | [Pilots](pilots/README.md), the safety header of `seed-demo.sql` |
| An architectural decision | `docs/architecture/ADR-*.md` | `docs/README.md`, `mkdocs.yml` nav |
| A refactor of a file over 665 lines (the file-size gate: hard cap 700, fails within 5% of it unless baselined) | follow its row in the [split plan](engineering/BIG-FILES-SPLIT-PLAN.md) | lower `scripts/ci/file-size-baseline.txt`, update the plan's status |
| A scheduled job or background poller | the owning `core/<module>`; take the advisory lock or add `@AllowUnlockedSchedule` with a reason | [Deployment shapes](deploy/DEPLOYMENT-SHAPES.md), the `shape-*` profiles in `core/app-config`, `JobsPoolsCoverEveryJobTest` |
| An in-memory store that must survive 2+ API replicas | a Valkey switch beside it | `ReplicaModeSafetyCheck`, the env var in `deploy/helm/charts/vegaduta-lib/templates/_core.tpl` (`ReplicaStoreCoverageTest` enforces both) |
| A docs page | `docs/` | `mkdocs.yml` nav, `docs/README.md` |
| A maintainer procedure | `docs/maintainers/` | `docs/README.md` |

Commits follow Conventional Commits with a DCO sign-off (`git commit -s`);
user-visible changes get a line under `## [Unreleased]` in `CHANGELOG.md`.

## State and open decisions

Facts about the repository's hosting that affect what is reachable today. Update this table when
one changes.

| Item | State | Consequence |
|---|---|---|
| Repository visibility | public (`CD-LC/vegaduta`) | links from public pages to the repository are reachable; Pages stays gated as below |
| GitHub plan | organization on the Free plan | rulesets and Pages are available because the repository is public; no ruleset is configured on `main` yet, see [Branch protection](maintainers/branch-protection.md) |
| Documentation site | workflow and config exist; deploy job gated by repository variable `DOCS_PUBLISH_PAGES` | set Pages source to "GitHub Actions" and the variable to `true` ([Publishing these docs](maintainers/docs-site.md)) |
| CODEOWNERS team | `@CD-LC/maintainers`; the team does not exist in the organization yet (`gh api repos/CD-LC/vegaduta/codeowners/errors` lists every rule as "Unknown owner") | create the team ([Repository setup](maintainers/repo-setup.md)), or change it with `scripts/dev/set-repo-identity.sh --team` |
| Security and conduct contacts | `contact@cdandlc.com` for both; private vulnerability reporting is on | keep the address monitored ([Repository setup](maintainers/repo-setup.md)) |
| GitHub Actions | jobs do not start: the account's payments failed or the spending limit is reached | no remote CI result exists until billing is fixed; run `make gates` and the module builds locally |
| Java Agent SDK on Maven Central | not published; `0.1.0-SNAPSHOT` builds from source | verify the `io.github.cd-lc` namespace, then follow [Releasing the SDK](maintainers/releasing-sdk.md) |
| Python Agent SDK on PyPI | not published (`sdk/python-agent/README.md`: "not yet on PyPI"); PyPI pending publisher registered for `pypi-release.yml` + environment `pypi`; upload runs only when `SDK_PUBLISH_PYPI` is `true` | tag `pysdk-v*` and dispatch the workflow; the first upload claims the project |
| TypeScript and Python client packages | not on npm or PyPI; `rest-sdk-release.yml` covers both on `restsdk-v*` tags; PyPI pending publisher registered (environment `pypi-rest-sdk`); npm needs a reserved name and an `NPM_TOKEN` secret | one-time setup in [Releasing the REST client SDKs](maintainers/releasing-rest-sdks.md), then tag `restsdk-v*` |
| Client builds for plugins and apps | not in this repository | published through the plugin catalog per deployment |
