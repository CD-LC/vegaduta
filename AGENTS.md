# AGENTS.md

> **⚠ Repository history rewritten (2026-10-19).** `main` was force-pushed to a
> clean linear history; the old repository's merged-in commit history was
> removed (content unchanged). If your local checkout predates this:
> **`git fetch origin && git reset --hard origin/main`** — do NOT `git pull`,
> do NOT push from a stale clone, and do NOT merge any other remote, fork or
> branch back into `main` without asking first; that re-imports the old
> history. Remove this notice once all active checkouts have reset.

Instructions for AI coding agents (and humans in a hurry) working in this
repository. Read this before editing. Human contributors: the full guide is
[CONTRIBUTING.md](CONTRIBUTING.md).

> **More than one agent works here at once.** Before you push, merge, tag, release or deploy,
> read [docs/maintainers/agent-coordination.md](docs/maintainers/agent-coordination.md), claim your
> area there, and log what you did.

## Layout

```
core/                 Java 21 / Spring Boot Maven reactor (one parent pom, 57 modules)
  app/                API server assembly (CoreApplication) + Flyway migrations
  app-jobs/           background-jobs shape (JobsApplication)
  app-worker/         Temporal worker shape (WorkerApplication)
  app-config/         shared application*.yml (Spring profiles)
  agents/             kernel: Agent entity, settings, quotas, shared vocabulary + ports
  agents-turn/ agents-tools/ agents-browser/ agents-jobs/ agents-chatport/
  agents-interop/ agents-studio/ agents-budget/ agentspi/   the ADR-0023 split
  chat/ providers/ mcp/ mcpserver/ workflow/ approval/ knowledge/
  graphmemory/ omnichannel/ guardrails/ security/ tenancy/ crypto/ ...
mkdocs.yml            builds docs/ into the documentation site (make docs-serve); see
                      docs/repository-structure.md for the full layout and linking map
sdk/                  First-party SDKs. typescript/ and python/ are generated REST clients
                      (sdk/generate.mjs); java/ is the VegaDūta Agent SDK: a separate Maven
                      reactor (group io.vegaduta, Java 17, own parent, NOT under core/) with
                      agent-api (framework-free governance contracts), agent-core,
                      agent-spring-ai, agent-spring-boot-starter, agent-jdbc, agent-testkit,
                      agent-scanner (risk-map CLI), agent-bom. Design: docs/architecture/ADR-0013-*.md
                      python-agent/ is the Python Agent SDK (vegaduta_agent): the same
                      governance contracts as the Java SDK (@governed, ApprovalGate,
                      EffectJournal, budgets, guardrails, audit — plus the halt switch,
                      autonomy levels, mandates, tamper-evident evidence chain, SQLite
                      stores, provenance, governed memory, cost ledger and replay) as a
                      dependency-free package; it must never import an HTTP client
                      (tests/test_contracts.py enforces it — transports are injected ports);
                      hand-written, tests under sdk/python-agent/tests (pytest).
web/                  Next.js console (tenant UI + public pages + platform-admin at app/platform-admin/)
services/             sidecar (Python ML), code-intel, app-builder, tool-sandbox,
                      mcp-stdio-runner (Node), piece-host, voice-agent, rl-trainer,
                      browser-runner, workspace-runner, whatsapp-qr, desktop-agent
examples/             workflow-templates/, mcp-servers/, agent-bundles/, sdk/
tests/e2e             Playwright suite;  tests/load  load tests;  tests/install  installer tests
deploy/compose        lite.yml, docker-compose.yml (+ *.build.yml, *.ci.yml, *.domain.yml overlays), .env.example
deploy/helm           umbrella chart charts/vegaduta + per-service charts + vegaduta-lib library
deploy/postgres       Postgres 16 + pgvector + Apache AGE image
scripts/vegaduta      install/run CLI;  scripts/ci  CI gates (also run by `make gates`)
infra/terraform/modules  reusable Terraform modules
contracts/            API/contract files;  docs/  documentation
```

## Build and test commands

| Component | Build | Test | Lint |
|---|---|---|---|
| core | `cd core && mvn -B -ntp -DskipTests package` | `mvn -B -ntp -pl <module> test`; full: `mvn -B -ntp verify` | (compiler; `make gates`) |
| sdk/java | `cd sdk/java && mvn -B -ntp -DskipTests package` (JDK 17+) | `mvn -B -ntp verify` | `mvn spotless:check` (google-java-format, Google style; run it on JDK 21, it does not start on JDK 17.0.20's javac) |
| sdk/python-agent | `cd sdk/python-agent && pip install -e '.[dev]'` (Python 3.10+) | `python -m pytest tests/ -q` | `ruff check vegaduta_agent tests` |
| web | `cd web && npm ci && npm run build` | `npm test` (vitest) | `npm run lint && npm run typecheck` |
| services/sidecar | `pip install -r requirements.txt -r requirements-dev.txt` | `python -m pytest tests/ -q` | `ruff check services/sidecar` |
| services/code-intel | `pip install -r requirements.txt` | `for f in tests/test_*.py; do python -m tests.$(basename $f .py); done` (**not pytest**; `make test-python`) | `ruff check services/code-intel` |
| services/rl-trainer | `pip install -r requirements.txt -r requirements-dev.txt` | `python -m pytest tests/ -q` | |
| services/mcp-stdio-runner, workspace-runner, whatsapp-qr, desktop-agent | none | `node --test` (`make test-node-services`) | |
| deploy | | `make compose-validate`, `make helm-lint` | `make shellcheck` |
| everything | `make build` | `make test` | `make lint`, `make gates` |

Toolchains: JDK 21+ (CI uses Temurin 21; JDK 24 works), Node 22, Python 3.12,
Docker with Compose v2.

## Rules that are easy to break

1. **Run one Maven build at a time.** Concurrent builds share `~/.m2` and read
   each other's half-written jars. Don't start a second `mvn` while one runs.
   This includes `sdk/java/` and `core/`: they are separate reactors but share `~/.m2`.
2. **`-pl X -am` does not build dependents.** It compiles X's dependencies, not
   the modules that use X. After changing a signature, compile the whole
   reactor (`mvn -DskipTests package`) before reporting success.
3. **Flyway versions are ONE namespace across `controlplane/` and `tenant/`**
   (`core/app/src/main/resources/db/migration/`). `V412` in one folder takes
   `V412` in the other. A duplicate fails every core boot. Never edit, rename
   or renumber a migration that has been released; add a new one. Run
   `bash scripts/ci/flyway-lint.sh`.
4. **Never hardcode `localhost` or `127.0.0.1` in a browser-facing URL or a
   token issuer.** Browser URLs come from `NEXT_PUBLIC_*` / `PUBLIC_*`
   configuration. Internal service-to-service URLs (Compose DNS such as
   `http://core:8080`, healthchecks, JWK fetches) are the only exception.
5. **`APP_ENCRYPTION_KEY` must be identical for every core shape** (api, jobs,
   worker, migrate) and every process that shares a database. A different key
   makes every stored provider key and channel secret undecryptable. It is
   base64 of exactly 32 bytes (`openssl rand -base64 32`).
6. **Keep the chat hot path in-process.** An agent turn (API, guardrails, tool
   calls, retrieval, LLM call) must not gain a network hop between platform
   modules. New services are for async/background work, isolating untrusted
   code, or a different runtime.
7. **Token issuers:** core validates the primary issuer plus
   `KEYCLOAK_ADDITIONAL_ISSUERS` (comma-separated). When the browser reaches
   Keycloak on a public URL but core reaches it internally, the public issuer
   must be listed there, or every token is rejected with 401.
8. **PgBouncer must stay in session pooling mode.** Tenants are Postgres
   schemas selected with `SET search_path`; transaction pooling silently runs
   queries in the wrong tenant's schema.
9. **Duplicate Spring bean or JPA entity names across modules**, and **a bean class
   with two constructors and no `@Autowired`**, compile and unit-test cleanly but
   crash core at startup. `InjectableConstructorTest` and `UniqueBeanNamesTest` (core/app,
   part of `mvn verify`; alone: `make spring-wiring`) catch both from bytecode, and
   `bash scripts/ci/entity-name-lint.sh` covers tables and controller base paths;
   before shipping a change to Spring wiring or migrations, boot the real jar with
   `bash scripts/ci/boot-check.sh`.
10. **No secrets in the repo**, including `.env*.example`, fixtures and docs.
    `bash scripts/ci/secret-scan.sh` runs gitleaks over new commits.
11. **Be exact about verification.** Compiled is not tested; tested is not run
    on a stack. Say which one you did.
12. **Work handed to another thread must carry the tenant.** `TenantContext` is a
    ThreadLocal; an executor, `CompletableFuture` async stage, Reactor
    `subscribeOn`/`publishOn`, raw thread or `@Async` method runs without it (fails
    closed) or, on a pooled thread left holding one, in the wrong tenant's schema.
    Capture `TenantScope.capture()` on the originating thread and re-apply it on the
    worker (`agents.turn.TenantScopedFlux` for a Reactor subscription); never set the context in
    `Flux.defer` and clear it in `doFinally`. `AsyncHopTenantScopeRulesTest` fails on
    an unwrapped hop; see [docs/extending.md](docs/extending.md#8-hand-work-to-another-thread).
13. **Architecture baselines only shrink.** `StructureRulesTest` and
    `ModuleBoundaryRulesTest` freeze today's violations in
    `core/app/src/test/resources/archunit_store` (package cycles, controllers touching
    repositories or entities, cross-area repository use, constructors over 12 parameters,
    kernel/vendor edges). A new violation fails the build. Fix violations and commit the
    smaller store; never refreeze to make a build pass. Details and commands are in the
    javadoc of `ModuleBoundaryRulesTest`; background in
    [the structural audit](docs/architecture/STRUCTURAL-AUDIT-2026-10-14.md).

## Conventions

- Commits: [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat(workflow): ...`), signed off with `git commit -s` (DCO).
- New user-visible behavior: add a line under `## [Unreleased]` in `CHANGELOG.md`.
- New configuration: add it to `deploy/compose/.env.example` (if Compose sets
  it) and to [docs/configuration.md](docs/configuration.md).
- Java: prefer constructor injection in new code, one feature per module, package
  `com.agenticai.core.<module>`. New REST controllers must not reuse another
  controller's base path unless the split is deliberate (allowlisted in
  `scripts/ci/entity-name-lint-allowlist.txt` with a reason).
- Frontend: TypeScript, existing design tokens and components; no new UI
  framework.
- Files over 665 lines (within 5% of the 700-line hard cap, or over it) fail
  the file-size ratchet unless baselined; the baseline only shrinks. Split
  rather than grow them.
- Naming: the product is **VegaDūta** ("VegaDuta" only where ASCII is
  required: identifiers, file names). `agentic-ai` / "Agentic AI" is the
  project's earlier internal name and survives in identifiers that are
  persisted or externally visible: the Java packages `com.agenticai.*`, the
  Maven `groupId`, the Keycloak realm `agentic-ai` and theme `agentic-ai`,
  database names and Flyway history. Do not rename those (it breaks stored
  data, token issuers and existing installs); do not introduce the old name in
  new user-facing text.
- Applied Flyway migrations are never edited, including their comments (the
  checksum covers the whole file), even where a comment names an internal
  document or person.

## Where things are

| Task | Start here |
|---|---|
| Add an LLM provider | `core/providers/.../ProviderName.java`, `core/chat/.../AgentChatClientFactory.java` |
| Add a workflow node | `core/workflow/.../WorkflowNodeType.java`, `WorkflowNodeExecutor.java`, `web/app/components/workflow/nodeDefs.ts` |
| Add a channel | `core/omnichannel/.../session/ChannelType.java`, an adapter implementing `core/approval/.../ApprovalActionExecutor.java` |
| Add an identity provider (users, SSO) | `core/admin/.../identity/IdentityUserAdminPort.java`, `IdentityProviderAdminPort.java`; Keycloak implementation in `core/admin/.../keycloak/KeycloakAdminClient.java` |
| MCP client / registry | `core/agents-tools/.../tools/DynamicMcpConnectionManager.java`, `core/mcp` |
| Configuration | `core/app-config/src/main/resources/application*.yml` |

Step-by-step guides: [docs/extending.md](docs/extending.md).
