# ADR-0012: Migrate core to Spring Boot 4.1 + Spring AI 2.0, and adopt selected 2.x features

| | |
|---|---|
| **Status** | Proposed. P1 (prep on Boot 3.5) is done; P2/P3 (Boot 4.1, Spring AI 2.0) are not started. Checked 2026-10-21 (v0.1.9): `core/pom.xml` is on Spring Boot 3.5.16 and Spring AI 1.1.8, and the P1b helpers named under Progress now exist (`core/json/.../JsonMappers.java`, `core/chat/.../OpenAiCompatModelBuilder.java`, `core/chat/.../LlmRetry.java`). |
| **Progress** | **P1a done (2026-09-30):** Spring Boot 3.4.4 to 3.5.16, Spring AI 1.1.7 to 1.1.8, `testcontainers.version` override dropped (Boot 3.5.16 manages 1.21.4), `spring-ai-starter-model-*` replaced by plain model modules in `chat` and `eval` (exclusion lists in `CoreApplication`, `JobsApplication`, `WorkerApplication` shrunk to the MCP `ToolCallbackConverterAutoConfiguration`), `migrator` Maven profile added, one property fix. Full reactor 6,551 tests, 0 failures; P0 guards unchanged. P1b (`JsonMappers`, `OpenAiCompatModelBuilder`, option helpers, `LlmRetry`) not started. Log: `migration-p1a.md`. |
| **P3 (2026-09-30)** | Spring AI 1.1.8 to **2.0.1**: OpenAI/Anthropic on the vendor SDKs behind one pooled OkHttp transport (request URLs of all providers unchanged, the `LlmFactoryWireParityTest` snapshot unchanged), retry on Framework core-retry, tool loop on `ToolCallingAdvisor` (order 1000, metering outside it), MCP SDK 2.0. Ported onto the ADR-0023 module layout on 2026-10-22; not yet soaked on staging. |
| **Date** | 2026-09-30 |
| **Deciders** | Platform Engineering (maintainers) |
| **Related** | ADR-0003 Spring AI dynamic clients (`docs/platform-documentation/decisions/`), [ADR-0011 provider plugins](ADR-0011-provider-plugins.md), `docs/engineering/INTEGRATION-TESTS.md`, `docs/engineering/ERROR-PRONE-BASELINE.md` |

> **Evidence rules for this document.** Code facts come from grep over `core/**/pom.xml` and `core/*/src`
> on 2026-09-30 (commit `1447cfd`). Nothing was compiled or run. Version facts come from the sources
> in section 11, fetched on 2026-09-30. Where a source was ambiguous or I could not confirm something,
> the text says **verify**. Treat every "verify" as a task in phase P0/P1, not as a settled fact.

---

## 1. Context

### 1.1 Where we are

- `core/pom.xml`: parent `spring-boot-starter-parent` **3.4.4**, `java.version` **21**,
  `spring-ai.version` **1.1.7** (imported `spring-ai-bom`), `testcontainers.version` **1.21.4**.
  The reactor has about 45 modules.
- Spring AI is used as a **model-invocation and tool-calling substrate only**. There is no
  auto-configured `ChatModel`. Every `ChatClient` is built per tenant and per provider by
  `core/chat/*AgentChatClientFactory` (BYOK plus the hosted pool). `CoreApplication`,
  `JobsApplication` and `WorkerApplication` each exclude **13** Spring AI auto-configurations
  (Anthropic, ChatClient, ChatMemory, 6 OpenAI, 3 Google GenAI, and `ToolCallbackConverterAutoConfiguration`).
- RAG (`core/knowledge`: pgvector + full-text + RRF, tenant-scoped, sidecar embeddings), memory
  (`core/agents` `ChatMemoryRecall`, `core/graphmemory` on Apache AGE), evals (`core/quality`,
  `core/eval`) and observability (`LlmMetricsAdvisor`, `LlmCallLog`, Langfuse over OTLP) are all
  hand-written. Spring AI's `VectorStore`, `EmbeddingModel`, `ChatMemory` and `org.springframework.ai.evaluation`
  have **zero** usages. (Correction to an earlier note: `BeanOutputConverter` is now used, once, in
  `chat/structured/StructuredOutput.java`.)

### 1.2 Where the ecosystem is (as of 2026-09-30)

| Component | Latest GA | Notes |
|---|---|---|
| **Spring AI** | **2.0.1** (2026-08-21). 2.0.0 GA was 2026-06-12. | "Spring AI 2.0.x supports Spring Boot 4.0.x and 4.1.x." 2.1.0-M1 (2026-09-25) moves the baseline to **Boot 4.2** and is a milestone, so it is **not** a target. |
| **Spring Boot** | **4.1.1** (2026-08-21) | 4.1 OSS support runs to 2027-07-31. 4.0 OSS support ends 2026-12-31. |
| Spring Boot 3.5 / 3.4 | 3.5.16 / 3.4.13 | **Both are past OSS end of life**: 3.4 ended 2025-12-31 and 3.5 ended 2026-06-30. We run **3.4.4**, which is behind even 3.4's final patch. |
| Spring Framework | 7.0.x (Boot 4.1 ships 7.0.8 per HeroDevs) | Jakarta EE 11, Servlet 6.1, JSpecify nullness. |
| Spring Security | 7.0.x | Lambda DSL only. `AntPathRequestMatcher` and `MvcRequestMatcher` are removed. Dispatcher types are authorized by default. |
| Hibernate ORM | 7.x (Boot 4.0 ships 7.2) | |
| Jackson | **3.0.x** (`tools.jackson.*`). Jackson 2 is still supported but deprecated. | Annotations stay in `com.fasterxml.jackson.annotation`. |
| Testcontainers | 2.0.x | Artifacts are renamed with a `testcontainers-` prefix. Packages move (for example `org.testcontainers.postgresql.PostgreSQLContainer`). JUnit 4 support is removed. |
| Flyway | 11.x | It needs `spring-boot-starter-flyway` under Boot 4's modular starters. |
| Micrometer / Tracing | 1.16 / 1.6 (Boot 4.0) | New `spring-boot-starter-opentelemetry`. |
| Kafka client / spring-kafka | 4.1 / 4.0 | |
| MCP Java SDK | **2.0.0** (via Spring AI 2.0) | Implements MCP spec 2025-11-25. The WebMVC and WebFlux transports moved into Spring AI (`org.springframework.ai:mcp-spring-*`). |
| Java | **Boot 4.x minimum is 17**. Boot 4.1 supports 17 to 26 and has first-class support for 25. | **Nothing forces Java 21 → 25.** |

---

## 2. Decision drivers

1. **Security and support.** We are on an EOL line (3.4.4). 3.5 is also EOL for OSS. The only
   OSS-supported path is 4.x, and 4.1 has the longer window of the two.
2. **Spring AI 1.1 is a dead end for features.** The 2.0 capabilities we want (tool-calling
   advisor, MCP SDK 2.0 / spec 2025-11-25, native cache-token usage, structured-output validation)
   are only available on Boot 4.
3. **The MCP spec moves both ways.** We are both an MCP server (`core/mcpserver`) and an MCP client
   (`DynamicMcpConnectionManager`). Staying on SDK 0.18.x drifts us away from the spec that
   Claude, ChatGPT and IDE clients now speak.
4. **Blast-radius control.** This is a multi-tenant production platform. The migration must never
   change the JSON API shape, JSONB column contents, auth behaviour or billing (token metering)
   without a test catching it.
5. **Keep what we built well.** Tenant-isolated RAG with RRF, schema-per-tenant memory, and our
   own governance advisors are better than the framework defaults for this platform. We adopt
   framework features only where they remove code or add capability without weakening isolation.

---

## 3. Options considered

| # | Option | Verdict |
|---|---|---|
| A | Stay on Boot 3.4.4 / Spring AI 1.1.7 | **Rejected.** EOL and unpatched, and it gets no MCP spec updates. |
| B | Boot 3.5.x only (the last 3.x) | **Rejected as an end state**, because 3.5 is already OSS-EOL. It is **accepted as a stepping stone (P1)**: 3.5 surfaces most Boot 4 deprecations as warnings, and Spring Security 6.5 has the 7.0 preparation flags. |
| C | Big-bang: Boot 4.1 + Jackson 3 + Spring AI 2.0 in one PR | **Rejected.** About 425 files touch Jackson, and 13 or more files touch removed Spring AI APIs. The tool loop also moves out of `ChatModel`. One PR could not be reviewed or bisected. |
| D | **Phased: P1 prep on 3.5 → P2 platform (Boot 4.1 + Jackson 3) → P3 Spring AI 2.0 → P4 adopt features** | **Chosen.** Each phase ships behind a green verification gate and can be reverted on its own. |
| E | Boot 4.2 / Spring AI 2.1 | **Deferred.** Both are milestones today. Revisit once 2.1 is GA; the 2.1 features we want are the Responses API, MessageParts and Session. |

## 4. Decision

Adopt **option D**. Targets are **Spring Boot 4.1.x (4.1.1 today)**, **Spring AI 2.0.x (2.0.1 today)**,
**Java 21** (unchanged), **Jackson 3** for our own code (with Jackson-2 wire defaults held on
explicitly), **Testcontainers 2.0.x**, and the **MCP Java SDK 2.0.x** managed by the Spring AI BOM.
The work happens on a dedicated branch (`feat/spring-ai-2-boot4`) and merges only after the
section 7 gates pass on staging.

Two structural sub-decisions:

1. **Drop the Spring AI model *starters* in favour of the plain model artifacts.** Use
   `spring-ai-openai`, `spring-ai-anthropic` and `spring-ai-google-genai` in `core/chat` and
   `core/eval`, and keep `spring-ai-client-chat` everywhere else, as 7 modules already do. We never
   use the auto-configured beans. This removes most of the 13-class exclusion list, and with it
   the "a Spring AI bump silently adds a new auto-config" failure mode. This is done in **P1**.
2. **One seam per concern.** Before P3, the 9 OpenAI-compatible factories must go through a single
   `OpenAiCompatModelBuilder`, and all JSON must go through a single `JsonMappers` factory. That way
   the Spring AI 2 changes and the Jackson 3 changes each land in one place, not in dozens.

---

## 5. Consequences

**Positive**
- We are back on an OSS-supported line until mid-2027, with a clear path to 4.2 / Spring AI 2.1.
- The MCP client and server on spec 2025-11-25 gain Streamable HTTP as the default, stateless
  servers, structured tool output, elicitation, and OAuth 2.0 support modules.
- Per-provider cache-token parsing (`CacheTokenAccounting`) can be replaced by
  `Usage#getCacheReadInputTokens` and `getCacheWriteInputTokens`.
- The OpenAI and Anthropic clients move to the vendors' official Java SDKs, so we no longer
  maintain drift in hand-rolled request records.

**Negative / costs**
- **The tool loop moves out of `ChatModel` into `ToolCallingAdvisor`.** Our resilience wrappers
  (`ResilientToolCallingManagerFactory`, `ArgumentSanitizingToolCallingManager`,
  `RedactingToolExecutionExceptionProcessor`) and two custom models (`GeminiMixedContentToolCallChatModel`,
  `AwsBedrockChatModel`) must be re-seated. Advisor ordering also changes what `LlmMetricsAdvisor`
  and the budget advisors see, so **billing/metering is at risk** (section 8).
- **`OpenAiApi` and `AnthropicApi` are removed.** Our HTTP-client tuning (`LlmHttpClientFactory`:
  Apache HC5 pool, JDK streaming connector, first-byte timeouts) no longer applies to OpenAI and
  Anthropic, because those modules are now on OkHttp inside the vendor SDKs. **There is no
  `completionsPath` option**, and the hosted pool relies on it.
- Jackson 3 is a mechanical change to about 425 files, with real behaviour differences (dates,
  property ordering, checked → unchecked exceptions, renamed `JsonNode` methods).
- MCP SDK 2.0 removes `McpSyncHttpClientRequestCustomizer`, which our per-request OAuth header
  refresh (`McpRemoteHeaders`) is built on.
- Langfuse and Grafana queries keyed on Spring AI span names must change (`tool_call <name>` →
  `execute_tool <name>`; `gen_ai.operation.name` `framework` → `execute_tool`).

---

## 6. Migration plan

Effort is in engineer-days for one engineer who knows the codebase, and includes writing tests.
File counts come from grep (section 10).

### P0: Characterisation tests (on current 3.4.4), about 4 to 6 days

Without these, the P2 and P3 gates cannot detect regressions. All are additive tests.

1. **API JSON golden tests.** Serialise a representative DTO set (errors, agent, chat turn, usage,
   workflow run) and compare it against checked-in JSON. Cover date formats, property order,
   nulls and empty collections. This extends `ErrorResponseContractTest`.
2. **JSONB round-trip tests.** Cover each of the 12 `@JdbcTypeCode(SqlTypes.JSON)` entities plus
   the JdbcTemplate JSON writers. Read a fixture row written by the *current* code, then write it
   back and compare. Run this in the `it` profile against `pgvector` Postgres.
3. **Tool-loop behaviour tests** against a scripted fake `ChatModel` that emits tool calls:
   argument sanitisation, redaction of tool exceptions, the max-iteration guard, `ToolContext`
   tenant propagation, `returnDirect`, and cancellation.
4. **Metering tests.** The prompt/completion/cache token totals that `LlmMetricsAdvisor`,
   `FreeTierTokenTrackingAdvisor` and `PerUserBudgetTrackingAdvisor` record for a 3-iteration tool
   turn, streaming and non-streaming. **This is the most important P0 test**, because P3 changes
   where usage accumulates.
5. **MCP contract tests:** the server `POST /mcp` Streamable HTTP handshake plus a tools/list and
   tools/call against `McpServerToolConfig`, and a client test that asserts the OAuth bearer is
   re-read **per request** (the `McpRemoteHeaders` invariant).

### P1: Prepare on Boot 3.5.x (last 3.x), about 6 to 9 days

The goal is to clear deprecations and create the seams, with no behaviour change.

| Change | Mechanical? | Files (est.) |
|---|---|---|
| Parent 3.4.4 → **3.5.16**. Remove the `testcontainers.version` override if 3.5 manages ≥1.21.4 (**verify**). | mech | 1 |
| Spring AI 1.1.7 → latest 1.1.x patch (**verify** whether one exists after 1.1.7). | mech | 1 |
| Swap `spring-ai-starter-model-*` → plain `spring-ai-openai` / `-anthropic` / `-google-genai` in `chat` and `eval`, and shrink the 3 exclusion lists to what is still on the classpath (the MCP client/server starters stay). | manual | 5 |
| Run OpenRewrite `org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_5` and Spring Security 6.5 → 7 prep recipes. Turn on the Security 6.5 opt-in 7.0 defaults. | mech | 3 security configs |
| Introduce `JsonMappers` (one configured `ObjectMapper`/`JsonMapper` factory) and replace the **179 main-code `new ObjectMapper()`** sites with it. This is the single biggest de-risking step for Jackson 3. | semi-mech | ~180 |
| Introduce `OpenAiCompatModelBuilder` and route all 9 OpenAI-compatible factories plus `HostedLlmChatClientFactory` through it. Do the same for Anthropic (`AnthropicAgentChatClientFactory`, `EvalRunner`). | manual | ~13 |
| Move `options.copy()` (4 sites: `ArgumentSanitizingToolCallingManager`, `StructuredOutput`) and `ModelOptionsUtils.OBJECT_MAPPER` (`GeminiMixedContentToolCallChatModel`, `GeminiSchemaSelfHealer`) behind small helpers. | manual | 4 |
| Wrap the `spring-retry` usage (`LlmModelSupport`, `GeminiMixedContentToolCallChatModel`) behind an internal `LlmRetry` interface. | manual | 3 |
| Add `spring-boot-properties-migrator` (runtime scope, removed in P2) and fix every warning. | manual | yml |

**Gate P1:** the gate set in section 7, plus zero properties-migrator warnings.
**Rollback:** revert the P1 PR. There are no data or format changes.

### P2 + P3: Boot 4.1 platform and Spring AI 2.0, on one branch

P2 cannot ship on its own, because Spring AI 1.1 does not run on Boot 4.

Spring AI 1.1 cannot load on Boot 4 ("built on the Boot 4.0 dependency model and cannot be loaded
in a 3.x context", and the reverse is equally true). **P2 and P3 therefore land on the same
branch.** They are still reviewed as separate commits (P2 commit set, then P3 commit set), so a
bisect can tell a platform regression from a Spring AI regression.

#### P2 commits: platform, about 10 to 15 days

| Change | Tooling | Files (est.) |
|---|---|---|
| Parent → **4.1.1**. Run `org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0` (community edition in rewrite-spring). It chains Boot 3.5, Framework 7, Security 7, Hibernate 7.1 and `Testcontainers2Migration`. | OpenRewrite | all poms |
| Starter renames: `spring-boot-starter-web` → `spring-boot-starter-webmvc` (35 poms), add `spring-boot-starter-flyway`, and use per-technology test starters where needed. `spring-boot-starter-classic` is an acceptable interim. | OpenRewrite + manual | ~45 poms |
| **Jackson 3:** run `org.openrewrite.java.jackson.UpgradeJackson_2_3` (package move `com.fasterxml.jackson.{core,databind}` → `tools.jackson.*`, `JsonNode#asText` → `asString` (656 calls in 140 files), `fields()` → `properties()`, `isTextual()` → `isString()`, `JsonProcessingException` → `JacksonException` (unchecked, 37 files), removal of `JavaTimeModule`/`Jdk8Module` registrations (9 files)). Annotations (26 files) are unchanged. | OpenRewrite, then manual fix-ups | **~425 files** (325 main) |
| Hold Jackson-2 **wire behaviour**: `spring.jackson.use-jackson2-defaults=true` (dates as timestamps, no alphabetical sort, and so on), and make `JsonMappers` build with `configureForJackson2()`-equivalent features. **Verify** against the P0 golden files. | manual | 2 |
| Hibernate 7 JSON: pin the `hibernate.type.json_format_mapper` to a mapper built from `JsonMappers`, so JSONB output does not change silently. **Verify** which Jackson (2 or 3) Hibernate 7.x auto-detects when both are on the classpath: Temporal SDK, networknt `json-schema-validator` 2.0.0 and Playwright keep Jackson 2 on the classpath transitively. Re-check `MultiTenantConnectionProvider` / `CurrentTenantIdentifierResolver` generics on the schema-per-tenant SPI. | manual | ~5 |
| Security 7: 3 `SecurityFilterChain` classes. They already use the lambda DSL, with no `.and()` and no Ant/Mvc matchers. Main risk: **dispatcher types are now authorized**, so `/error` forwards can turn into 401s. Add `dispatcherTypeMatchers(FORWARD, ERROR).permitAll()` or equivalent. | manual | 3 |
| `META-INF/spring.factories` in `app-config` registers 3 `EnvironmentPostProcessor`s. The interface moved to `org.springframework.boot.EnvironmentPostProcessor` (the old one is deprecated). Update the key and the imports. | manual | 4 |
| Testcontainers 2: 4 test files. Rename the artifacts (`testcontainers-postgresql`, `testcontainers-junit-jupiter`) and move to the new `PostgreSQLContainer` package. | OpenRewrite | 5 |
| Observability properties: `management.otlp.tracing.*` and the tracing starters moved in Boot 4 (**verify** the exact new keys with properties-migrator). Consider `spring-boot-starter-opentelemetry`. | manual | yml |
| Kafka 4.x client (`spring-kafka`, 3 main files), Lettuce (47 Redis files, API stable), 16 WebSocket files, `HttpStatus` deprecated constants (10 files). | mostly mech | ~20 |
| **Not affected:** no `javax.*` EE imports, no `RestTemplate`, no `@MockBean`/`@SpyBean`, no springdoc, no Spring Cloud, no Keycloak adapter (we use resource-server JWT), and Temporal via the plain `temporal-sdk` 1.31.0 rather than the Boot starter. Keep the SDK; bump it only if its Jackson 2 conflicts. | none | 0 |

#### P3 commits: Spring AI 1.1.7 → 2.0.1, about 12 to 18 days

| # | Breaking change (upgrade notes) | Our impact | Files |
|---|---|---|---|
| 1 | **Tool execution loop removed from every `ChatModel`**; `ChatClient` auto-registers `ToolCallingAdvisor` (`HIGHEST_PRECEDENCE + 300`). | Re-seat `ResilientToolCallingManagerFactory` and `ArgumentSanitizingToolCallingManager` on `ToolCallingAdvisor.builder().toolCallingManager(...)` inside `LlmModelSupport.chatClientBuilder`. **Redesign `GeminiMixedContentToolCallChatModel`**: it overrides a protected method of the model's internal loop, and that method no longer exists. **Redesign `AwsBedrockChatModel`**: it has its own `ToolCallingChatOptions` branch. Re-order our advisors explicitly around the tool advisor. | ~10 |
| 2 | `ToolCallingAdvisor` **accumulates usage across all internal calls**. Default limits are 40 per tool and 150 total (`ToolCallLimitExceededException`). | Decide whether metering advisors sit **outside** the tool advisor (they see the accumulated total once) or **inside** (they see each iteration). Never both. Set limits explicitly to match our own guard. The P0 metering test is the gate. | 4 advisors |
| 3 | **`OpenAiApi` removed.** OpenAI is now on the official `openai-java` SDK (OkHttp). `OpenAiChatModel.builder()` takes options, `httpClientBuilderCustomizer` and `dispatcherExecutor`. Strict tool schemas now default to **false**. | 13 files, all behind the P1 seam. Port `LlmHttpClientFactory` timeouts and pool sizing to `OpenAiHttpClientBuilderCustomizer`. **`completionsPath` has no equivalent**: hosted-pool rows that set it need either an OkHttp interceptor that rewrites the path, or a base URL chosen so that `{base}/chat/completions` resolves. **Verify for each hosted row.** `StructuredOutput` `ResponseFormat` moves to the SDK's type. Decide per provider whether to opt in to `strict(true)` (our `SchemaTypeArrayNormalizer` exists because of strict-mode 400s). | 13 |
| 4 | **`AnthropicApi` removed.** Anthropic is on the official `anthropic-java` SDK. `AnthropicChatModel.builder().apiKey(..)`. The `maxTokens` default goes 500 → 4096. The cache classes move from `anthropic.api` to `org.springframework.ai.anthropic`. | `AnthropicAgentChatClientFactory`, `EvalRunner`, `AnthropicProviderCachingStrategy`, `CacheTokenAccounting`. | 4 |
| 5 | Options are immutable: `copy()`/`fromOptions()` are removed in favour of `mutate()`. `ChatClient.options()` takes a *builder*. `N()` → `n()`. | 4 sites (behind P1 helpers). There are no `ChatClient.options(built)` calls today. | 2 |
| 6 | `ModelOptionsUtils` JSON members removed; use `JsonHelper` / `JacksonUtils.getDefaultJsonMapper()`. | Gemini schema parsing (`GeminiSchemaSelfHealer`, `GeminiMixedContentToolCallChatModel`). | 2 |
| 7 | `retryTemplate(...)` now takes `org.springframework.core.retry.RetryTemplate` (Framework 7), not spring-retry. | `LlmModelSupport` (its `RetryListener` metrics must be ported), Gemini model. | 3 |
| 8 | MCP SDK 2.0: `McpSyncClientCustomizer` → `McpClientCustomizer<McpClient.SyncSpec>`; **`McpSyncHttpClientRequestCustomizer` removed** (use `httpRequestCustomizer` on the transport builder); `McpSchema.Tool.inputSchema()` is now a `Map`; sealed interfaces removed; required-field builders (`CallToolRequest.builder(name)`, and so on). | `McpClientTimeoutConfig`, **`McpRemoteHeaders`** (per-request OAuth: re-prove it with the P0 test), `DynamicMcpConnectionManager`, `StaticMcpStdioSidecarRouting` (`NamedClientMcpTransport`), `McpServerToolConfig` (`McpServerFeatures`, `McpToolUtils`). | ~7 |
| 9 | Tool-resolution fallback is disabled by default. | We always attach explicit callbacks, so no impact is expected. **Verify** `AgentToolProvider`. | 0–1 |
| 10 | Auto-config class names and packages changed or were added (for example tool-search and tool-calling auto-configs). | Re-derive the exclusion lists from the 2.0.1 jars. After the P1 starter swap, only the MCP ones should remain. | 3 |
| 11 | Observation names: `execute_tool <name>`, `gen_ai.operation.name=execute_tool`, new `spring.ai.tool.type` and `spring.ai.tool.call.id`. | Langfuse views, alert rules, `TracingSamplingDefaultTest`-style assertions. | dashboards |
| 12 | `mcp-json-jackson2` → Jackson 3 JSON mapper in SDK 2.0 (**verify**). | The `agents/pom.xml` comment already warns that `json-schema-validator` arrived transitively through `mcp-json-jackson2`. Keep it declared explicitly at 2.0.0. | 1 |

**Gate P2+P3:** the full section 7 set, plus a staging soak (section 7.3).
**Rollback:** the branch is not merged into `main` until the soak passes. After merge and deploy,
roll back by redeploying the previous core image tag. P2/P3 add **no Flyway migrations**, so the
image rollback is clean. If P4 adopts JDBC chat memory, its migration is additive only.

### P4: Adopt selected Spring AI 2.x features, incremental, about 10 to 20 days in total

Each item is a separate PR on Boot 4 `main`, flag-gated, and follows section 9.

---

## 7. Verification gates (every phase)

### 7.1 Build gates (JDK 21 toolchain; one Maven build at a time)
1. `mvn -f core/pom.xml clean verify`: the full reactor, unit tests, ArchUnit (`core/app/src/test/.../archrules`),
   Error Prone + NullAway (`-Pstatic-analysis`). Expect **new NullAway findings** from Framework 7's
   JSpecify annotations in `@NullMarked` packages. Fix them rather than baselining them.
2. `mvn -f core/pom.xml -Pit verify`: Testcontainers `ApplicationContextIT` (full context boot),
   `MigrationsIT` (all Flyway versions, controlplane and tenant), `KnowledgeVectorSearchIT` (pgvector + RRF).
3. **Boot the actual jar** (`java -jar core/app/target/*.jar`) against `deploy-postgres:latest`
   (pgvector + AGE). `mvn test` has missed context-start failures before.
4. The P0 suites: API JSON golden files, JSONB round-trip, tool-loop, **metering**, MCP contract.
5. `ErrorResponseContractTest`, and the web e2e API contract (`.github/workflows/e2e.yml`).

### 7.2 Behavioural checks (manual, staging)
- One chat turn per provider family: OpenAI, Anthropic, Gemini (API + Vertex), Bedrock, an
  OpenAI-compatible provider (Groq/DeepSeek/Mistral/Ollama), and each hosted-pool row that has a
  custom `completionsPath`. Test streaming and non-streaming, and with and without tools.
- An MCP client against one remote Streamable HTTP server with an OAuth token that expires during
  the session. An MCP server driven by an external client through `POST /mcp` with a grant key.
- Langfuse shows `chat` + `execute_tool` spans, and there is **no prompt/completion text on spans**
  (`log-prompt`/`log-completion` must stay false; re-check the property names in 2.0).

### 7.3 Soak
48 hours on staging with synthetic traffic. Compare token totals per tenant against the P1 baseline
(tolerance ±1%), p95 TTFT, error rate, and the thread and connection-pool counts that OkHttp now owns.

---

## 8. Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Token metering double-counts or under-counts after the tool loop moves into an advisor | **High** | **High** (billing, free-tier limits) | P0 metering test; explicit advisor order; soak comparison |
| Jackson 3 changes API JSON (dates, order, nulls) | High | High (web, mobile, SDK clients) | `use-jackson2-defaults`; `JsonMappers`; golden files |
| JSONB written in a new shape becomes unreadable by old code (during rollback) | Medium | High | Pin the Hibernate format mapper; round-trip tests; no mixed-version writers |
| Hosted pool `completionsPath` cannot be expressed | Medium | Medium | OkHttp path-rewrite interceptor; per-row test |
| HTTP timeouts regress (OkHttp defaults vs our tuned HC5/JDK clients) | Medium | High (hung turns) | Port `LlmHttpClientFactory` settings to `OpenAiHttpClientBuilderCustomizer` and its Anthropic equivalent; streaming first-byte timeout test |
| MCP remote OAuth header frozen at build time (a regression of a bug we already fixed once) | Medium | High | P0 per-request header test |
| Security 7 authorizes `/error`, so 401s replace structured errors | Medium | Medium | Dispatcher-type rule; `ErrorResponseContractTest` |
| Hibernate 7 multi-tenant SPI / `ddl-auto: validate` gets stricter | Low–Med | High (boot failure) | `ApplicationContextIT` + boot-the-jar gate |
| Two Jackson majors on the classpath (Temporal, networknt, Playwright pull Jackson 2) | Certain | Low | Fine by design (different packages). Just never inject a Jackson 2 `ObjectMapper` bean |
| OpenRewrite churn makes review impossible | Medium | Medium | Recipe-only commits separate from manual commits; spotless ratchet |

---

## 9. Feature adoption table (Spring AI 2.x)

"Fit" compares each feature against what we already have. Effort is engineer-days after P3.

| Feature | Benefit | Fit with our code | Risk | Effort | Recommendation |
|---|---|---|---|---|---|
| **`ToolCallingAdvisor`** (unified loop, limits, `ToolExecutionEligibilityChecker`) | One loop for every provider, and streaming parity | **Mandatory** in P3. It replaces the per-model loop our wrappers hook into | Metering order | (in P3) | **Adopt** (forced) |
| **Native cache-token usage** (`Usage#getCacheRead/WriteInputTokens`) | Deletes provider-specific parsing | Replaces most of `CacheTokenAccounting` | Low | 1–2 | **Adopt** |
| **MCP SDK 2.0 / spec 2025-11-25**: structured tool output (`outputSchema`), elicitation, progress, tool annotations | Our MCP server becomes modern for Claude/ChatGPT/IDE clients, and the client can consume structured results | `McpServerToolConfig` builds specs programmatically per grant. Keep that approach, but emit `outputSchema`/annotations from our `ToolDefinition` metadata | Medium | 3–5 | **Adopt** |
| **Stateless Streamable HTTP MCP server** | Lets `mcpserver` scale horizontally without sticky sessions (we run one replica today) | Good fit: our tools are request-scoped and auth is by API key | Low | 1–2 | **Adopt** (config flip plus tests) |
| **`@McpTool` / `@McpResource` / `@McpPrompt` annotations** | Less boilerplate for static tools | Poor fit. Our exposed tools are dynamic, per tenant and per grant, and rate-limited | Low | – | **Skip** for tenant tools. Possibly use for a few static platform tools |
| **MCP Security (OAuth 2.0 resource server / authorization server modules)** | Spec-compliant OAuth for remote MCP clients | Complements our API-key grants and Keycloak | Medium (it is a community module; check its Boot 4 version) | 4–6 | **Evaluate** in P4 |
| **`ToolSearchToolCallingAdvisor`** (regex/lucene/vector tool index) | Scales to hundreds of MCP tools with progressive disclosure | Overlaps our `ToolRelevanceSelector` | Medium | 3–5 | **Evaluate** with an A/B on the quality/stats harness; keep ours if it is not better |
| **`StructuredOutputValidationAdvisor`** + `BeanOutputConverter` via `JsonSchemaGenerator` | Automatic re-prompt on invalid JSON | Our `StructuredOutput` (13 callers) already does provider-native JSON mode | Low | 2 | **Adopt** as the retry layer under `StructuredOutput` |
| **Anthropic options**: thinking display, service tier, `AnthropicWebSearchTool`, `inferenceGeo` | Data-residency (`eu`), cost control, server-side web search | Map onto existing agent settings | Low | 2–3 | **Adopt** `inferenceGeo` + service tier. Web search is optional |
| **OpenAI `extraBody`, `promptCacheKey`** | Cleaner OpenAI-compatible extras; better cache hits | Replaces bespoke request tweaks | Low | 1–2 | **Adopt** |
| **Reasoning content** (`reasoningContent` metadata; 2.1 `ReasoningPart`) | Shows the thinking trace in the UI | Partial support in 2.0 (DeepSeek/vLLM only for OpenAI-compatible providers) | Low | 2 | **Adopt** read-only; wait for 2.1 MessageParts for full support |
| **JDBC `ChatMemoryRepository`** (Postgres, `sequence_id`) | Standard storage | Schema-per-tenant works via our datasource routing, but our memory recall is semantic plus graph (AGE) and richer | Medium (a second store for the same data) | 3 | **Skip**. Keep `ChatMemoryRecall` + graphmemory |
| **`spring-ai-session`** (community; event-sourced memory + summarisation) | Long-conversation compaction | Interesting. It becomes part of core in 2.1 | Medium | – | **Revisit** at 2.1 GA |
| **PgVector `VectorStore` + `QuestionAnswerAdvisor` / `RetrievalAugmentationAdvisor`** | Standard RAG API | **Our RAG is better for us**: hybrid FTS + vector with RRF, tenant schema isolation, HNSW tuning, re-embed jobs, sidecar embeddings | High (isolation regressions) | – | **Skip**. Optionally expose our search as a `DocumentRetriever` adapter later |
| **ETL readers** (PDF with page ranges, Tika) | Fewer custom parsers | Ingestion lives in the sidecar | Low | – | **Skip** (sidecar owns it) |
| **`Evaluator`s** (relevancy, fact-checking) | Standard judge prompts | `core/quality` has a stats-aware engine (k-repeats, Wilson intervals, paired A/B) | Low | 1 | **Skip**, or wrap as one extra `Evaluator` impl |
| **Observability** (`gen_ai.*` semconv on chat and tool spans, tool-call ids) | Better Langfuse traces without custom code | Complements `LlmMetricsAdvisor`; keep our DB `LlmCallLog` | Low | 1–2 | **Adopt** (remap dashboards) |
| **JSpecify null-safety** across Spring AI | Pairs with our NullAway pilot | Good | Low | incl. | **Adopt** (automatic) |
| **Tool call limits** (`spring.ai.tools.limits.*`) | A runaway-loop guard | Set explicitly on the builder | Low | incl. | **Adopt** |
| **OpenAI Responses API**, MessageParts, `VectorStore.upsert(EmbeddedDocument)` | Reasoning + tools on current OpenAI models | Only in **2.1.0-M1** | – | – | **Wait** for 2.1 GA (needs Boot 4.2) |
| **A2A** | – | No first-party A2A in Spring AI 2.0 (none found); our `core/agents/a2a` stays | – | – | **Keep ours** |
| **Agent frameworks** (Spring AI agentic project planned Nov 2026, experimental; `spring-ai-agent-utils`, Embabel) | – | Our agent runtime (`AgentChatService`, crews, workflows, Temporal) is the product | – | – | **Watch**, do not adopt |

---

## 10. Appendix: code inventory (grep, 2026-09-30, commit `1447cfd`)

### 10.1 Spring AI artifacts (versions from `spring-ai-bom` 1.1.7)
| Artifact | Modules |
|---|---|
| `spring-ai-client-chat` | agents, codeintel, graphmemory, guardrails, knowledge, quality, rl (7) |
| `spring-ai-starter-model-openai` | chat, eval |
| `spring-ai-starter-model-anthropic` | chat, eval |
| `spring-ai-starter-model-google-genai` | chat |
| `spring-ai-starter-mcp-client` | agents |
| `spring-ai-starter-mcp-server-webmvc` | mcpserver |

Pinned non-Spring libraries worth tracking: `okhttp` 4.12.0 (pinned in the root for google-genai),
`temporal-sdk` 1.31.0, `json-schema-validator` 2.0.0, `playwright` 1.49.0, `bcprov-jdk18on` 1.80,
AWS SDK 2.28.29, Lombok 1.18.38, ArchUnit 1.4.1.

### 10.2 Spring AI API usage
207 main and 72 test files import `org.springframework.ai.*`. Top classes by import count (main + test):
`ToolCallback` 134, `ChatClient` 111, `ToolDefinition` 105, `Prompt` 23, `ChatResponse` 23,
`OpenAiChatOptions` 21, `AssistantMessage` 18, `OpenAiApi` 16, `ToolContext` 16, `ChatClientRequest` 15,
`GoogleGenAiChatOptions` 13, `Message` 13, `CallAdvisorChain` 13, `ChatClientResponse` 13,
`OpenAiChatModel` 12, `Generation` 12, `StreamAdvisorChain` 11, `UserMessage` 10, `Usage` 8,
`ToolCallingManager` 7, `DefaultToolDefinition` 6, `CallAdvisor` 6, `StreamAdvisor` 6, `AnthropicApi` 5,
`AnthropicChatOptions` 5, `ToolCallingChatOptions` 5, `ToolCallbackProvider` 4, `GoogleGenAiChatModel` 4,
`SyncMcpToolCallbackProvider` 2, `GoogleGenAiCachedContentService` 2, `ModelOptionsUtils` 2,
`BeanOutputConverter` 1, `McpToolUtils` 1, `McpSyncClientCustomizer` 1, `AnthropicCacheOptions` 1.
MCP SDK direct imports: `McpSyncClient` 8, `ServerParameters` 6, `StdioClientTransport` 2,
`HttpClientStreamableHttpTransport` 1, `HttpClientSseClientTransport` 1,
`McpSyncHttpClientRequestCustomizer` 1, `McpServerFeatures` 1, `JacksonMcpJsonMapper` 1.

**Hot files for P3:** `chat/` `{Hosted,AzureOpenAI,DeepSeek,Grok,Groq,Meta,Mistral,Ollama,OpenAI,OpenRouter,Sarvam,Anthropic,Gemini,VertexAi}*ChatClientFactory`,
`LlmModelSupport`, `LlmHttpClientFactory`, `ResilientToolCallingManagerFactory`, `ArgumentSanitizingToolCallingManager`,
`RedactingToolExecutionExceptionProcessor`, `GeminiMixedContentToolCallChatModel`, `GeminiSchemaSelfHealer`,
`AwsBedrockChatModel`, `CacheTokenAccounting`, `AnthropicProviderCachingStrategy`, `GoogleGenAiCacheManager`,
`structured/StructuredOutput`, `LlmMetricsAdvisor`, `SystemMessageMergingAdvisor`, `DefaultAgentAdvisor`,
`gateway/GatewayGovernanceAdvisor`; `agents/` `FreeTierTokenTrackingAdvisor`, `PerUserBudgetTrackingAdvisor`,
`tools/AgentToolProvider`, `tools/DynamicMcpConnectionManager`, `tools/McpRemoteHeaders`, `tools/McpClientTimeoutConfig`,
`tools/StaticMcpStdioSidecarRouting`, `tools/AgentMcpToolResolver`; `mcpserver/McpServerToolConfig`; `eval/EvalRunner`;
`app/CoreApplication`, `app-jobs/JobsApplication`, `app-worker/WorkerApplication` (exclusions).

**Custom classes that wrap or replace Spring AI features:** tool-loop resilience (`ResilientToolCallingManagerFactory`,
`ArgumentSanitizingToolCallingManager`, `RedactingToolExecutionExceptionProcessor`, `GuardedToolCallback`,
`NamespacedMcpToolCallback`, `SanitizedMcpToolCallback`, `SchemaTypeArrayNormalizer`, `ToolRelevanceSelector`);
structured output (`StructuredOutput` + 13 callers); caching (`ProviderCachingRegistry` + 6 strategies);
RAG (`KnowledgeSearchService`, RRF); memory (`ChatMemoryRecall`, `graphmemory`); evals (`quality/EvalEngine`, `eval/EvalRunner`);
metrics (`LlmMetricsAdvisor`, `LlmCallLog*`); Bedrock (`AwsBedrockChatModel`, a hand-rolled `ChatModel`).

### 10.3 Boot-4-sensitive usages (files: total / main)
| Item | Count |
|---|---|
| `com.fasterxml.jackson.*` imports | **424 / 325** |
| `ObjectMapper` | 387 / 286; `new ObjectMapper()` 278 / 179 |
| `JsonNode` | 182 / 143; `.asText(` 656 calls in 140 files; `.isTextual()` 36/24; `.fields()` 20/12 |
| `JsonProcessingException` | 39 / 37 |
| `TypeReference<` | 40 occurrences / 26 files |
| `JavaTimeModule`/`Jdk8Module` | 9 / 2; `registerModule(` 8 files |
| `MappingJackson2HttpMessageConverter` | 2 / 1 |
| Jackson annotations (unchanged in 3) | 26 / 26 |
| `javax.{persistence,servlet,validation,annotation,transaction,inject}` | **0** |
| `RestTemplate` | **0**; `RestClient` 107/71; `WebClient` 18/18; `java.net.http.HttpClient` 48/42 |
| `SecurityFilterChain` | 3 main (`SecurityConfig`, `NoSecurityConfig`, `McpServerToolConfig`); `.and()` 0; Ant/Mvc matchers 0; `JwtDecoder` 4 |
| Hibernate APIs | `@JdbcTypeCode(SqlTypes.JSON)` 12 files; `MultiTenantConnectionProvider`, `CurrentTenantIdentifierResolver`, `MetadataSources` (1 each); `AttributeConverter` 1; no `UserType`, `@Type` or `@Where` |
| `JdbcTemplate` | 49 / 33 |
| Flyway API | 5 / 1 (no customizers) |
| Testcontainers | 4 test files (`PostgreSQLContainer`, `GenericContainer`, `ImageFromDockerfile`, `@Testcontainers`); `@ServiceConnection` 1 |
| `@MockBean`/`@SpyBean` | **0**; `MockMvc` 17 test files; `TestRestTemplate` 1 |
| spring-retry | 3 files (`LlmModelSupport`, `GeminiMixedContentToolCallChatModel`, a test) |
| `spring.factories` | 1 (`app-config`, 3 `EnvironmentPostProcessor`s); no `AutoConfiguration.imports` |
| Redis (`RedisTemplate`/Lettuce) | 77 / 47; no Jackson Redis serializers |
| Kafka | 4 / 3; no Kafka JSON serde |
| Actuator custom endpoints/health | 3 |
| Micrometer `ObservationRegistry`/`@Observed` | 20 / 18; `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` in 3 app poms; OTel API 3 files |
| Temporal | 7 / 6 (plain `temporal-sdk`, no Boot starter) |
| WebSocket | 19 / 16 |
| springdoc / Spring Cloud / Keycloak adapters | **none** |
| Deprecated `HttpStatus` names (`PAYLOAD_TOO_LARGE`/`UNPROCESSABLE_ENTITY`, and so on) | 14 / 10 |

---

## 11. Sources

- Spring AI 2.0.0 GA announcement, 2026-06-12: https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/
- Spring AI 2.0.1, 2026-08-21: https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/
- Spring AI 2.1.0-M1, 2026-09-25 (Boot 4.2 baseline, Responses API, MessageParts, agentic project Nov 2026): https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/
- Spring AI upgrade notes (2.0.0 breaking changes, OpenRewrite MCP recipes): https://docs.spring.io/spring-ai/reference/upgrade-notes.html
- Spring AI getting started ("2.0.x supports Spring Boot 4.0.x and 4.1.x"): https://docs.spring.io/spring-ai/reference/getting-started.html
- Spring AI OpenAI chat (2.0, SDK-based builder, no `completionsPath`, `OpenAiHttpClientBuilderCustomizer`): https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html
- Spring AI MCP overview / server starter / security: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html, https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html, https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html, https://github.com/spring-ai-community/mcp-security
- Stateless MCP on Spring AI 2.0 (community write-up): https://dev.to/jamilxt/mcp-goes-stateless-building-ai-agent-servers-with-spring-ai-20-4hfg
- Spring Boot 4.0 migration guide: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide
- Spring Boot 4.0 release notes (dependency versions): https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes
- Spring Boot support dates / Java ranges: https://endoflife.date/spring-boot ; https://www.herodevs.com/blog-posts/spring-boot-versions-eol-dates-and-latest-releases-april-2026
- Jackson 3 in Spring: https://spring.io/blog/2025/10/07/introducing-jackson-3-support-in-spring/ ; `use-jackson2-defaults`: https://github.com/spring-projects/spring-boot/issues/49951
- Spring Security 7 migration: https://docs.spring.io/spring-security/reference/migration/index.html
- OpenRewrite recipes: https://docs.openrewrite.org/recipes/java/spring/boot4/upgradespringboot_4_0-community-edition ; https://docs.openrewrite.org/recipes/java/jackson/upgradejackson_2_3 ; https://docs.openrewrite.org/recipes/java/testing/testcontainers/testcontainers2migration ; https://docs.openrewrite.org/recipes/java/spring/security7/upgradespringsecurity_7_0
- Testcontainers 2 artifact/package changes: https://mvnrepository.com/artifact/org.testcontainers/testcontainers-postgresql ; https://blog.doubleslash.de/en/software-technologien/coding-and-frameworks/testcontainers-2-an-upgrade-worth-it/

**Source caveats.** One secondary source dates the 2.0 GA to May 28; spring.io's own post is dated
2026-06-12, and that is the date used here. The upgrade-notes summary I fetched described the new
OpenAI module as built on "anthropic-java", which is plainly a summarisation error. The module is on
the official **openai-java** SDK, and the Anthropic module is on **anthropic-java**. The exact Boot 4
names for the OTLP tracing properties and Hibernate 7's Jackson auto-detection were **not** confirmed
from primary sources; both are marked **verify** above.
