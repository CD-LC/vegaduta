# Coding Standards and Infrastructure Audit

Audit date: 2026-09-29. Scope: the whole repo (`core/`, `web/`, `web-admin/`, `services/`, `deploy/`, `.github/`).
Method: read-only. We ran static counts with grep, one ESLint run on `web/` (JSON output, no `--fix`), and read the configs.
We did not run Maven, `tsc` or `npm install`.

**How to read the counts.** All counts are approximate grep counts. They exclude `target/`, `node_modules/` and `.next/`. Unless noted, they cover main sources only.

**What this plan is for.** It is a ratchet. The code is large and runs in production elsewhere, so every change below is incremental:
- a new check only applies to changed lines or new files, or
- it starts in warn mode.

The plan contains no big-bang reformatting.

---

## 1. Scorecard

Effort: S = under a day, M = 1 to 3 days, L = a week or more. Risk means the risk to production behaviour.

| Area | Current (evidence) | Target | Gap | Effort | Risk |
|---|---|---|---|---|---|
| Java formatting | Spotless is in `core/pom.xml`: `ratchetFrom origin/main`, google-java-format AOSP. It is **not bound to any phase and CI does not run it.** | `spotless:check` runs in CI on changed files | CI wiring only | S | None |
| Java static analysis | No Checkstyle, PMD, SpotBugs, Error Prone or NullAway | Error Prone in warn mode, later NullAway on new packages | Missing entirely | M | Low (warn) |
| Build hygiene | No `maven-enforcer-plugin`. Spring Boot parent is **3.4.4**, which is past its OSS support window. | Enforcer (Java 21, Maven 3.9, dependency convergence, banned deps). Boot 3.5.x. | Missing. Upgrade due. | S / M | Low / Medium |
| Coverage | JaCoCo exists only in the opt-in `-Pcoverage` profile. About 48% measured earlier. No threshold. | Report in CI. Ratchet gate on changed code. | Not in CI | S | None |
| Dependency injection | 70 `@Autowired` in main code; only 3 are field injection. Everything else uses constructor injection. `InjectableConstructorTest` guards multi-constructor beans. | Constructor-only | Very small | S | None |
| Configuration binding | **484 `@Value(`** against 20 `@ConfigurationProperties` classes. Hotspots: `DemoSandboxSettingsService` (17), `DemoSessionRateLimiter` (12), `PhonePeGatewayClient` (10), `LlmModelSupport` (8). | New configuration uses `@ConfigurationProperties` records with `@Validated`. Migrate hotspots opportunistically. | Large, but it is not a correctness problem | L (incremental) | Low |
| Error handling | One `@RestControllerAdvice` (`core/app/.../GlobalExceptionHandler.java`, **1642 lines, 154 `@ExceptionHandler`**) returns a hand-rolled `Map<String,Object>`. `ProblemDetail` is used **0** times. | RFC 9457 `ProblemDetail` with a stable `type` and `code`, behind a feature flag. Split the advice per feature. | Format is not standard. The class is a god object. | M | Medium (API clients parse the error body) |
| Logging | SLF4J throughout (7 `System.out` or `printStackTrace` in main code). MDC `requestId`, `tenantId` and `userId` come from `RequestCorrelationFilter` and `TenantContextFilter`. The console pattern is text. There is no `logback*.xml` and no structured logging. | Opt-in `logging.structured.format.console=ecs` (built into Boot 3.4) for k8s/Loki. Add `traceId` to MDC. | JSON logging is missing | S | Low (opt-in property) |
| Transactions and data | 841 `@Transactional`. 1271 records (good immutability uptake). | ArchUnit rule: no `@Transactional` on controllers | Rule only | S | None |
| Null safety | 1 `@Nullable` import in 2535 main files | JSpecify `@NullMarked` on new packages, then NullAway | Missing | M | Low |
| Large classes | Over 1500 lines: `AgentService` (2038), `AgentChatService` (1815), `GlobalExceptionHandler` (1642). Another 11 files are between 1000 and 1480 lines (see §2.1). A file-size ratchet exists (`scripts/ci/check-file-size.sh`). | No new file over 1000 lines. Extract along seams when files are touched. | God-object risk in the chat and agent path | L | Medium |
| Module boundaries | 42 Maven modules. Maven forbids module cycles. `agents` depends on 24 sibling modules (a hub). `security` has 30 inbound and `tenancy` 27. The `archrules/` package has 2 classpath-scan tests (unique bean names, injectable constructors) and **no ArchUnit or Spring Modulith**. | ArchUnit: package-cycle freedom inside a module, no web→repository shortcuts, no `..internal..` access across modules | Missing | M | None (test-only) |
| Tests | JUnit 5 (1152 imports), Mockito (116). 629 test files. `@SpringBootTest` ×5. **Testcontainers: 0.** CI uses a hand-started Postgres container. | Testcontainers for the Postgres (pgvector + AGE) and Redis integration slices, and one context-boot smoke test | Missing | M | None |
| API contracts | No springdoc or OpenAPI. `/api/<feature>` has no version prefix. `contracts/` holds only inbound webhook fixtures. | springdoc behind a profile. Generated `openapi.json` checked in CI. Document the versioning policy (header or `/api/v1` for new public APIs). | Missing | M | Low |
| TS strictness (web) | `strict:false`, `noImplicitAny:true` (step 1 of 3 of the ladder). There is a `tsconfig.strictcheck.json`. | `strictNullChecks`, then `strict` | Ladder in progress | L | Low |
| TS strictness (web-admin) | `strict:false`, plus strictFunctionTypes, strictBindCallApply, noImplicitThis, noImplicitOverride and noUnusedParameters | `strict` (435 errors were measured) | Ladder | M | Low |
| ESLint (web) | **188 errors** / 1448 warnings. `npm run lint` is an unconditional CI step, so the web job cannot be green. | 0 errors. Warnings ratcheted. | Burn-down | M | Low to medium (promise fixes touch runtime) |
| `any` usage | web about 1352, web-admin about 236 (`: any`, `as any`, `<any>`). web has 149 `@ts-ignore/expect-error/nocheck` and 114 `eslint-disable`. | Warning budget with no growth | Ratchet | S | None |
| Formatting (TS) | Prettier is configured (`.prettierrc.json` in both apps) but CI does not enforce it | `prettier --check` on changed files | CI wiring | S | None |
| Env handling | Runtime `getPublicEnv` (`web/app/lib/env.ts`, `web-admin/app/lib/env.ts`) is used in 39 files. **95 direct `process.env.NEXT_PUBLIC_*` reads remain in web** (`EDGE_AGENTS` 46, `BILLING_ENABLED` 22, `API_BASE_URL` 7). | All public env goes through `getPublicEnv`. ESLint `no-restricted-properties` rule. | Partial | S / M | Medium (`API_BASE_URL` is the localhost-bake trap) |
| Python tooling | Ruff is configured in `sidecar` and `code-intel` `pyproject.toml` and enforced in CI for those two only. No mypy or pyright anywhere. Other services are only covered by the "advisory" matrix. | Ruff for all services. mypy `--strict` on new modules only. | Partial | S | None |
| Python deps | Fully `==` pinned: sidecar, code-intel, voice-agent. Ranges: app-builder (5 of 7), rl-trainer (9), tool-sandbox (3 of 3). No hash or lock files. | `==` pins or `uv.lock` everywhere | Partial | S | Low |
| Python types | Return-annotated defs: sidecar 64/72, tool-sandbox 71/80, voice-agent 44/59, code-intel 182/303, app-builder 66/160, rl-trainer 13/27 (no tests) | Over 80%, and mypy in CI | Partial | M | None |
| Container base images | Tag-pinned, never digest-pinned. `e2bdev/code-interpreter:latest` in `core/sdlc/e2b-template`. Compose defaults first-party images to `:latest`. | Digest pins, kept fresh by Dependabot's docker ecosystem (already configured) | Medium | S | Low |
| Node 20 (EOL 2026-04-30) | `core/Dockerfile` (NodeSource `setup_20.x`), `web/Dockerfile`, `web-admin/Dockerfile`, `services/mcp-stdio-runner/Dockerfile`, `examples/mcp-servers/http-gateway/Dockerfile`. CI already uses Node 22. | `node:22-*` (or 24 LTS) | Runtime is past end of life | S | Low to medium (rebuild and smoke test) |
| Non-root and HEALTHCHECK | Non-root in all app images except `rl-trainer` (no `USER`) and `tool-sandbox` (root by design so it can `setpriv` to uid 5001). HEALTHCHECK only in core, tool-sandbox, web, web-admin and http-gateway. Compose supplies the rest. | `USER` in rl-trainer | Small | S | Low |
| .dockerignore | Present: core, web, web-admin, sidecar, code-intel, tool-sandbox. **Missing:** app-builder, rl-trainer, voice-agent, mcp-stdio-runner, mock-provider, postgres, keycloak realm. core's ignore file lacks `**/node_modules` and `docs`. | Everywhere | Missing | S | None |
| Image size | core runtime: JRE + curl + Node + uv + Playwright Chromium + Chrome-for-Testing + Puppeteer Chrome (multi-GB). sidecar: torch-cpu. | Split the browser/MCP toolchain into an optional variant or the mcp-stdio-runner. Make `app-jobs` slim. | Architectural | M / L | Medium |
| JVM options | `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError` in compose and every core Helm chart. Lite uses `+UseSerialGC`. No GC is chosen for full-size containers (G1 is ergonomic at 2 or more CPUs, Serial below). | Explicit `-XX:+UseG1GC` for limits of 2 GiB or more (or Generational ZGC for latency). `-Xss` review. Keep 70%. | Minor | S | Low |
| Virtual threads | `spring.threads.virtual.enabled: true` | Keep. Watch pinning on `synchronized` plus blocking I/O (JDK 21). | OK | — | — |
| DB pool | Hikari max 15, min 5, connect timeout 20s. PgBouncer `POOL_MODE: session`, `DEFAULT_POOL_SIZE: 25`, `MAX_CLIENT_CONN: 200`. | **Keep session mode** (schema-per-tenant `search_path`). Assert pool sum ≤ 25 × replicas in a Helm guard. | OK | S | — |
| Graceful shutdown | No `server.shutdown` or `spring.lifecycle.timeout-per-shutdown-phase` set anywhere. Boot 3.4 defaults to graceful with a **30s** phase. Helm `terminationGracePeriodSeconds: 90`, `preStop sleep 10`. | Set both explicitly (for example 60s) to line up with the 90s grace for long SSE and LLM streams | Implicit | S | Low |
| Actuator | Exposes health, info, prometheus. `show-details: when-authorized`. Liveness and readiness groups exist, with readiness including `tenantMigration`. `/actuator/*` is blocked at Caddy only. Helm probes point at the same endpoints (startup 30×10s). | Separate management port once a dedicated SecurityFilterChain exists | Deferred (documented) | M | Medium |
| Redis timeouts | `spring.data.redis.timeout: 2s` in `application-lite.yml` **only**. The base config leaves Lettuce at its 60s default. | `REDIS_TIMEOUT` defaulting to 2s in the base config | Missing | S | Low |
| Outbound HTTP timeouts | Global RestClient connect 10s / read 120s. LLM connect 10s / read 10m, stream idle 90s, buffered turn 240s. Some clients build their own (57 builder or `newBuilder` sites), and **0** `new RestTemplate()` or `newHttpClient()`. | Keep. Add an ArchUnit rule banning unconfigured clients. | OK | S | — |
| Helm | Hardened defaults in `vegaduta-lib/_helpers.tpl`: runAsNonRoot, drop ALL, readOnlyRootFilesystem, RuntimeDefault seccomp, no privilege escalation. PDB enabled. HPA available but off. Topology spread. NetworkPolicy default deny. RollingUpdate maxUnavailable 0. Image `digest:` field. core requests 1536Mi / limit 4Gi against about 1.1 GiB measured steady. | Lower the core request to about 1280Mi and limit to 3Gi once measured on k8s. A CPU limit is deliberately absent (fine). | Minor | S | Low |
| Secrets | `existingSecret` in 19 value files. Not in app-builder, code-intel, mcp-stdio-runner, sidecar, tool-sandbox, web, web-admin (plus postgres, seaweedfs, valkey via their own mechanisms). Compose uses `:?` required vars. | `existingSecret` on every chart that takes a token (code-intel, tool-sandbox, sidecar internal tokens) | Partial | S | Low |
| Compose | `x-restart: unless-stopped` on every service. 33 healthchecks. Memory limits on almost every service. **No `logging:` block (json-file is unbounded).** | `x-logging` anchor: `json-file`, `max-size: 10m`, `max-file: 3` | Missing | S | None |
| Supply chain | CodeQL, dependency-review, Scorecard, gitleaks (`static-gates.yml`), Dependabot (maven, npm, pip, docker, terraform, actions). Release: cosign keyless signing, syft SPDX SBOM, SLSA provenance, image scan. Actions are SHA-pinned. | Also run image scan on PRs that touch a Dockerfile | Near target | S | None |
| Web security headers | CSP is **Report-Only** in both apps. `connect-src` hard-codes `api/auth/codeintel.vegaduta.{xyz,ai}`, which is wrong for OSS self-hosters. | Build the CSP from runtime env (`API` and `AUTH` origins). Add a `report-to` endpoint. Enforce once reports are clean. | Medium | M | Medium |
| CORS and rate limits | `.cors(Customizer.withDefaults())` in `SecurityConfig` (1267 lines). About 100 files touch rate limiting (Redis-backed limiters). | Document the CORS allowed-origins property for self-hosters | Docs | S | — |

---

## 2. Evidence details

### 2.1 Java (`core/`)

- **Size.** 2535 main and 629 test Java files. 42 reactor modules. Java 21. Spring Boot 3.4.4. Spring AI 1.1.7.
- **Layout.** Mostly package-by-feature: modules are features (`billing`, `voice`, `workflow`, ...). Inside `app`, `com.agenticai.core.common.*` is a grab-bag of feature packages (`agentgen`, `budget`, `studioapp`, `quicktask`, ...). `common` is the wrong name for feature code. Move it only when those packages are touched.
- **Files over 1000 lines:**

  | Lines | File |
  |---|---|
  | 2038 | `agents/.../AgentService.java` |
  | 1815 | `agents/.../AgentChatService.java` |
  | 1642 | `app/.../common/GlobalExceptionHandler.java` |
  | 1479 | `workflow/.../WorkflowExecutionEngine.java` |
  | 1429 | `agents/.../tools/DynamicMcpConnectionManager.java` |
  | 1381 | `workflow/.../WorkflowNodeExecutor.java` |
  | 1313 | `voice/.../VoiceCallController.java` |
  | 1283 | `workflow/.../WorkflowService.java` |
  | 1267 | `security/.../SecurityConfig.java` |
  | 1262 | `agents/.../Agent.java` (entity) |
  | 1187 | `brief/.../BriefApplyService.java` |
  | 1069 | `brief/.../BriefPlannerService.java` |
  | 1011 | `chat/.../LlmMetricsAdvisor.java` |
  | 1007 | `agents/.../tools/AgentToolProvider.java` |

  Much of this length is long rationale comments, which is valuable, but responsibilities are still mixed.
- **Module graph.**
  - Fan-out: `app` 35, `app-worker` 33, `app-jobs` 33, `agents` 24, `workflow` 15, `brief` 14.
  - Fan-in: `security` 30, `tenancy` 27, `crypto` 18, `audit` 18.
  - Direction is clean at module level (`workflow` → `agents` in 17 files, never the reverse), and Maven rejects cycles.
  - Package-level cycles inside the big modules (`agents`, `workflow`) are unmeasured. ArchUnit's `slices().should().beFreeOfCycles()` would measure them.
- **Existing arch tests.** `core/app/src/test/java/com/agenticai/core/archrules/UniqueBeanNamesTest.java` and `InjectableConstructorTest.java` are Spring classpath scans that prevent the two recorded context-start failures. No `com.tngtech.archunit` dependency exists.
- **Error body.** `errorResponse(HttpStatus, message)` returns a `Map`. Switching to `ProblemDetail` changes the JSON shape that web, web-admin, mobile and the SDKs parse, so it needs a compatibility window. Keep the old fields (`error`, `message`) as `ProblemDetail` properties.
- **Tests.** No Testcontainers. The CI `core` job runs `mvn -B -ntp verify` against a hand-started `deploy/postgres` image. `mvn verify` does not boot the full app jar. The known prod crash-loop class (context-start failures) is only partly covered by the two scan tests.
- **Logging.** No logback config. The pattern is set in `application.yml` `logging.pattern.console`. `GlobalExceptionHandler` explicitly avoids echoing request bodies. A secret-redaction check is not enforced by tooling.

### 2.2 TypeScript

- **web ESLint** (`eslint .`, flat config: `eslint-config-next/core-web-vitals` + typescript-eslint type-aware rules) found 188 errors:

  | Rule | Errors |
  |---|---|
  | `@typescript-eslint/no-floating-promises` | 88 |
  | `react/no-unescaped-entities` | 50 |
  | `@typescript-eslint/no-misused-promises` | 46 |
  | `no-unused-vars` | 2 |
  | `await-thenable` | 1 |
  | `no-namespace` | 1 |

  It also found 1448 warnings: `no-explicit-any` 1350, `react-hooks/exhaustive-deps` 37, unused-disable and other rule-less messages 29, `consistent-type-imports` 17, `no-img-element` 15.
  - The 50 `no-unescaped-entities` errors are mechanical (JSX text).
  - The 134 promise errors are real bug candidates (unhandled rejections). Fix them with `void` or `.catch()` after review, not a blanket `void`.
- **Components.** web has 998 TS/TSX files, of which 529 are `'use client'` (about 53%). web-admin has 64 of 109. The high client ratio inflates the JS bundle. Moving leaf data-fetch or static sections to server components is phase-2 work.
- **Next config.** `output: "standalone"`, `poweredByHeader: false`, `reactStrictMode: true`.
- **web-admin dependencies.** web-admin pins `react` and `react-dom` 18.3.1 with `next ^16.3.5`, while web uses React 19. Verify that the peer range is supported, and align them.

### 2.3 Containers

Summary per Dockerfile:

- **core**
  - Multi-stage (maven → temurin 21 JRE jammy).
  - The pom-first layer gives good dependency caching. `dependency:go-offline || true` hides resolution failures.
  - No BuildKit `--mount=type=cache` for `~/.m2`.
  - Non-root, has a HEALTHCHECK, has an optional AppCDS stage.
  - `ENTRYPOINT java -jar` has no layered-jar extraction (except the AOT variant).
- **web and web-admin:** multi-stage, `npm ci`, non-root, HEALTHCHECK, Node 20.
- **sidecar:** single stage. Two pip layers (torch-cpu, then requirements). Non-root.
- **tool-sandbox:** JDK (not JRE) runtime, because it compiles tenant code. It is root by design.

Distroless or chiseled JRE (for example `gcr.io/distroless/java21-debian12:nonroot`, or Ubuntu chiseled temurin) is a good fit **only for `app-jobs`**, and for `app` once the MCP stdio toolchain moves out to `mcp-stdio-runner`. Today core needs a shell, Node and Chromium, and its HEALTHCHECK uses curl.

---

## 3. Top 15 recommendations (ranked by value ÷ risk)

1. **Fix the 188 web ESLint errors, or scope the gate.** CI `web` runs `npm run lint` unconditionally, so the OSS main branch cannot be green. Two steps:
   - First: autofix the 50 `no-unescaped-entities`, and add a baseline, either `eslint --max-warnings` with a suppressions file (ESLint 9.24+ `--suppress-all`) or a per-file override.
   - Then: burn down the 134 promise errors by hand.
2. **Add an `x-logging` anchor to compose** (json-file, 10m × 3) on every service in `docker-compose.yml` and `lite.yml`. Unbounded logs are a known cause of disk-full incidents on the staging host.
3. **Wire `spotless:check` into CI** (`mvn spotless:check`, which works with the existing `ratchetFrom origin/main`). CI must fetch `origin/main` (`fetch-depth: 0`). Add `prettier --check` on changed TS files and `ruff format --check` on changed Python files.
4. **Add `maven-enforcer-plugin`.** Rules:
   - `requireJavaVersion [21,)`
   - `requireMavenVersion 3.9`
   - `banDuplicatePomDependencyVersions`
   - `bannedDependencies` (commons-logging, log4j 1.x, `junit:junit`)

   Put `dependencyConvergence` in **warn** (`fail=false`) first, because it will flag Spring AI and cloud SDK trees.
5. **Move all Node 20 images to Node 22 LTS:** core NodeSource `setup_22.x`, web, web-admin, mcp-stdio-runner, http-gateway example. This matches CI. Smoke-test the stdio MCP servers and Playwright in core.
6. **Set runtime defaults explicitly in `application.yml`:**
   - `server.shutdown: graceful`
   - `spring.lifecycle.timeout-per-shutdown-phase: ${SHUTDOWN_TIMEOUT:60s}` (inside Helm's 90s grace minus the 10s preStop)
   - base `spring.data.redis.timeout: ${REDIS_TIMEOUT:2s}` plus a `connect-timeout`
7. **Add missing `.dockerignore` files** (app-builder, rl-trainer, voice-agent, mcp-stdio-runner, mock-provider). Extend core's with `**/node_modules`, `**/.idea`, `docs/`. Add `USER` to `rl-trainer`.
8. **Add opt-in JSON logging:** `logging.structured.format.console: ${LOG_FORMAT:}` (empty keeps text; `ecs` for k8s). Add `traceId` and `spanId` to MDC when Micrometer tracing is on.
9. **Add ArchUnit (test scope, `core/app`) with a frozen baseline** (`FreezingArchRule`), so existing violations are recorded and only new ones fail:
   - package slices free of cycles per module
   - controllers must not access repositories
   - no `@Transactional` on controllers
   - no field `@Autowired`
   - no `new RestTemplate()` or `HttpClient.newHttpClient()`
   - no `System.out`
10. **Run JaCoCo in CI** (`-Pcoverage`), upload the report, and add a changed-lines coverage comment. Hold off on a failing threshold.
11. **Add Error Prone in warn mode** (`-XDcompilePolicy=simple`, `-Xplugin:ErrorProne -XepDisableWarningsInGeneratedCode`, all warnings, no `-Werror`). Put it in a `-Pstatic` profile so local builds are unaffected. Then add NullAway on `@NullMarked` packages only.
12. **Make CSP host lists runtime-derived.** Build `connect-src` and `frame-src` from `API_BASE_URL` and `AUTH_URL` instead of hard-coded `vegaduta.{ai,xyz}`, which is required for OSS self-hosting. Add `report-to`, then enforce.
13. **Pin base images by digest** (`FROM node:22-slim@sha256:...`). Dependabot's docker ecosystem keeps digests fresh. Replace `e2bdev/code-interpreter:latest`. Default compose `VEGADUTA_VERSION` to the release tag, not `latest`.
14. **Add springdoc-openapi** under a `openapi` profile. Generate `openapi.json` in CI and diff it (breaking-change check with `oasdiff`). Document the API versioning policy: `/api/*` is unversioned-internal; new public APIs use `/api/v1` or a media-type version.
15. **Migrate error bodies to RFC 9457 `ProblemDetail`,** keeping the legacy fields during a deprecation window. Split `GlobalExceptionHandler` into per-feature `@RestControllerAdvice(basePackages=...)` classes. Adopt `@ConfigurationProperties` records for the `@Value` hotspots (`DemoSandboxSettingsService`, `DemoSessionRateLimiter`, `PhonePeGatewayClient`, `LlmModelSupport`).

---

## 4. Do now in the OSS baseline (safe, config-only)

None of these changes application behaviour in the default configuration.

- [x] Compose `x-logging` anchor with json-file rotation on all services (compose files only). *Done: `x-logging` in `docker-compose.yml` and `lite.yml`.*
- [x] CI: `mvn -B spotless:check` step (ratcheted) with `fetch-depth: 0`. *Done (`ci.yml`).*
- [x] CI: `prettier --check` and `ruff check` / `ruff format --check` on changed files. *Done (`ci.yml`).*
- [x] CI: extend `ruff check` from sidecar and code-intel to app-builder, voice-agent, rl-trainer, tool-sandbox (advisory first). *Done: advisory whole-tree step in `ci.yml`.*
- [x] `maven-enforcer-plugin` (Java, Maven, banned deps enforced; convergence warn-only). *Done (`core/pom.xml`).*
- [x] JaCoCo report in CI (`-Pcoverage`, no threshold). *Done: the `core` job runs `mvn -Pcoverage,it verify`.*
- [ ] ESLint baseline or suppressions so CI goes green without hiding new errors, plus the mechanical `no-unescaped-entities` autofix.
- [x] `application.yml`: explicit `server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase: 60s`, base Redis `timeout: 2s`, `logging.structured.format.console: ${LOG_FORMAT:}`. *Done, with a base Redis timeout of `${REDIS_TIMEOUT:5s}` and the shutdown phase fed by `SHUTDOWN_PHASE_TIMEOUT` / `app.shutdown.drain-seconds`.*
- [x] Missing `.dockerignore` files. `USER` in rl-trainer. Replace `:latest` in the e2b template. *Done: every service has a `.dockerignore`, rl-trainer runs as `USER 10001`, the e2b template base is digest-pinned.*
- [ ] Helm: `existingSecret` for the internal-token charts (code-intel, tool-sandbox, sidecar, mcp-stdio-runner). Document the measured core request of about 1.1 GiB.
- [x] `.editorconfig` exists. Add `.gitattributes` `* text=auto eol=lf` if it is not present. *Done: `.gitattributes` exists.*
- [ ] PR-time image scan on Dockerfile changes (reuse the release job's scanner).

## 5. Phase 2 (code changes, ratcheted)

*Status 2026-10-21:* done since the audit: ArchUnit with frozen baselines (`StructureRulesTest`, `ModuleBoundaryRulesTest`), Error Prone and the NullAway pilot (`-P static-analysis`, see [ERROR-PRONE-BASELINE.md](ERROR-PRONE-BASELINE.md)), Testcontainers integration tests (see [INTEGRATION-TESTS.md](INTEGRATION-TESTS.md)), Spring Boot 3.5.16, and an OpenAPI breaking-change check (`scripts/ci/openapi-breaking.mjs`). The rest was not rechecked.

- Node 22 image bump, with a smoke test of MCP stdio and Playwright.
- ArchUnit with a frozen baseline, then burn down.
- Error Prone (warn), then NullAway on `@NullMarked` new packages. JSpecify annotations on public module APIs.
- Testcontainers slice tests (Postgres pgvector + AGE image, Valkey), and one full-context boot smoke test against `deploy/postgres`.
- Hand-fix the 134 promise-misuse errors in web. Route the 95 direct `process.env.NEXT_PUBLIC_*` reads through `getPublicEnv` and ban direct reads with `no-restricted-properties`.
- TS strictness ladder: web `strictNullChecks`, then `strict`; web-admin `strict`. Cap the `any` count and burn it down.
- `@ConfigurationProperties` migration for the `@Value` hotspots, and for all new configuration.
- `ProblemDetail` migration behind a compatibility window. Split `GlobalExceptionHandler`.
- Decompose `AgentService`, `AgentChatService`, `WorkflowExecutionEngine`, `SecurityConfig` and `VoiceCallController` along existing seams when they are next touched (the file-size ratchet already blocks growth).
- Spring Boot 3.4.4 → 3.5.x (in OSS support). Boot 4 waits for Spring AI 2.0.
- Split the browser and MCP toolchain out of the core image so `app-jobs` and `app` can use a slim or distroless JRE. Digest-pin all bases.
- Runtime-derived CSP, then enforce it.
- springdoc and OpenAPI diff in CI, and the versioning policy.
- Python: `==` pins or `uv.lock` for app-builder, rl-trainer and tool-sandbox. mypy on new modules. Tests for rl-trainer.

## 6. Keep as-is (verified correct, do not "fix")

- PgBouncer `POOL_MODE: session`. Transaction pooling breaks schema-per-tenant `search_path`.
- `MaxRAMPercentage=70` and `ExitOnOutOfMemoryError`. Virtual threads on. No CPU limit on core in Helm.
- Actuator on the app port, with `/actuator/*` blocked at Caddy. A separate management port broke healthchecks (see the `application.yml` comment) and needs its own SecurityFilterChain first.
- `tool-sandbox` running as root in order to `setpriv` to uid 5001.
- Internal compose DNS URLs (`http://keycloak:8080`, `http://core:8080`) in healthchecks and JWK fetch.
