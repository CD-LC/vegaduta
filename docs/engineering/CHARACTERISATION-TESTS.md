# Characterisation tests (ADR-0012, phase P0)

These suites pin what core did when they were written, on Spring Boot 3.4.4 / Spring AI 1.1.7 / Jackson 2 /
Hibernate 6.6 / MCP SDK 0.18.2 (core is now on Spring Boot 3.5.16 / Spring AI 1.1.8, `core/pom.xml`), so that the Boot 4 + Spring AI 2 migration (ADR-0012, P2 and P3) can
be checked against behaviour rather than against opinion. They are additive: no production code
changed. A failure after a migration step means "behaviour changed", which is either a bug to fix or a
deliberate decision to record by updating the test and the ADR.

They are not a substitute for the existing unit tests; they sit on top of them and cross module
boundaries where the risk is at the seams.

## Suites

| Suite | Module | What it pins | Migration risk it detects |
|---|---|---|---|
| `ApiJsonGoldenTest` + `src/test/resources/golden/*.json` | `app` | The JSON the API emits for error bodies (RFC 9457 and legacy), agent, chat turns, usage/spend report, workflow run, tenant settings, data usage, payment mandate and transaction, a Spring Data `Page`, plus a mapper probe (Instant with nanos, `LocalDate`, `Duration`, `BigDecimal` scale, `Optional`, enums, `byte[]`, map order, nulls). Populated and "sparse" (nulls, empty collections) variants of the big records. The mapper is the one Spring Boot builds from the app's own `application.yml`. | **Jackson 3.** Date format, default property inclusion, ordering, `Optional`/Jdk8 handling, `PageImpl` shape. |
| `JsonbRoundTripTest` | `app` | All 12 `@JdbcTypeCode(SqlTypes.JSON)` attributes (scan-checked: a new one fails the test until it gets a fixture). Fixture documents shaped like current rows are read and written back through Hibernate's `FormatMapper`. Pins: `String` attributes are passed through verbatim, `Map`/`JsonNode` attributes keep key order and normalise `1.10` to `1.1`. | **JSONB / Hibernate 7.** Which JSON mapper Hibernate 7 picks when both Jackson 2 and 3 are on the classpath. |
| `JsonbJdbcWritersTest` | `app` | The `JdbcTemplate` writers that cast to `?::jsonb`: `BillingWebhookEventLedger` (raw body bound verbatim), `KnowledgeMetadataFilter` (containment document, dotted keys, type sensitivity), `KnowledgeIngestionService` metadata document. | **Jackson 3** and the P1 `JsonMappers` factory changing text that never goes through Hibernate. |
| `JsonbRoundTripIT` (`*IT`, tag `integration`) | `app` | Real Postgres: every JSON attribute is a `jsonb` column, a Hibernate write/read/rewrite round trip is stable, and the JdbcTemplate writers store and query what the unit tests say they bind. **Not run when written (no Docker); may need small adjustments on first run.** | **JSONB.** Column type drift, jsonb normalisation, real Hibernate binding. |
| `ToolLoopCharacterisationTest` | `agents` | At `ChatClient` level over a scripted model: non-JSON tool arguments repaired in replayed history, invented tool names answered not fatal, tool exceptions redacted, the round cap (soft stop, then hard stop with no further model call), `ToolContext` reaching every round, tenant context following a tool onto the streaming thread, `returnDirect`, cancellation during a tool. | **Tool loop.** The loop moves from each `ChatModel` into the ChatClient's `ToolCallingAdvisor`; if `ResilientToolCallingManagerFactory` is not re-seated, sanitising, redaction and the cap silently stop applying. |
| `MeteringCharacterisationTest` | `agents` | Token totals recorded by `LlmMetricsAdvisor` (log row and Micrometer meters, including `CacheTokenAccounting`), `FreeTierTokenTrackingAdvisor` and `PerUserBudgetTrackingAdvisor` for a plain turn and a 3-iteration tool turn, streaming and non-streaming, and for cancellation. Exact totals, one row per turn, nothing counted twice. | **Metering (most important).** Where usage accumulates and where the metering advisors sit relative to the tool advisor. Money and quota depend on it. |
| `McpServerStreamableHttpContractTest` | `mcpserver` | Inbound `POST /mcp` Streamable HTTP with the real Spring AI server auto-configuration and `McpServerToolConfig`: `initialize` handshake and session id, `tools/list` returns exactly the curated tools, `tools/call` result shape, an uncurated tool is not callable. | **MCP.** Auto-configuration class moves, transport wiring, tool naming and result shape under SDK 2.0. |
| `McpClientPerRequestBearerContractTest` | `agents-tools` | On the wire: an OAuth-backed remote MCP connection sends a freshly read bearer on every HTTP request, never the connect-time one (the `McpRemoteHeaders` invariant); static headers unchanged. Uses a real MCP client against a local fake server. | **MCP.** SDK 2.0 removes `McpSyncHttpClientRequestCustomizer`; the hook moves to the transport builder. |

Shared fixtures live next to the suites that use them: `ScriptedChatModel` and `ToolLoopHarness`
(`agents/src/test/.../characterisation`) and `GoldenJson` / `RecordFixtures` (`app/src/test/.../golden`).

### How the scripted model behaves

`ScriptedChatModel` replays a list of steps (text chunks, tool calls, a known `Usage`). Like every
provider `ChatModel` in Spring AI 1.1.x it runs the tool loop **internally** and accumulates usage
across iterations with `UsageCalculator.getCumulativeUsage`, which discards the provider-native usage.
Consequences pinned by the metering tests: a tool turn is one log row with cumulative prompt and
completion tokens; Anthropic cache read/write counts exist only on single-call turns and are absent
(`null`, no meter series) on tool turns.

### What P3 changed (outcome, Spring AI 2.0.1)

The tool loop now lives in the ChatClient's tool advisor, not in the model. Decisions taken (ADR-0012 P3):

1. **Row count and totals of a tool turn: unchanged.** The tool advisor is seated at order 1000
   (`LlmModelSupport#TOOL_CALLING_ADVISOR_ORDER`), i.e. INSIDE every advisor this codebase registers (content
   policy -1500, system-message merge -1000, metrics / free-tier / per-user budget 0, default advisors such as RL
   100). They see one call carrying the turn's accumulated usage - one row, 450 prompt / 50 completion - and never
   both the accumulated and the per-iteration numbers. Spring AI's own default (order HIGHEST_PRECEDENCE + 300)
   would have put the loop outside all of them.
2. **Cache counters on a tool turn: intended improvement.** Spring AI 2 carries cache read/write counts on the
   `Usage` interface and sums them across the loop, and `LlmMetricsAdvisor` passes the provider to
   `CacheTokenAccounting.from(usage, provider)` (the summed usage has no native object to infer the billing
   convention from). `toolTurnSumsAnthropicCacheCountersAcrossIterationsAndKeepsCumulativeTokens` (was
   `toolTurnDropsAnthropicCacheCountersButKeepsCumulativeTokens`) now expects write 100 / read 400 instead of null.
   Prompt/completion stay 350 / 40. This is the only assertion changed in the P0 suites.

`ScriptedChatModel` no longer runs a loop (it returns one scripted response per call, like a real 2.0 model) and
`ToolLoopHarness#client` hands its manager to the advisor via `chatClientBuilder(model, manager)`. Streaming
cancellation while a tool runs needed a small subclass (`CancellationAwareToolCallingAdvisor`): Spring AI 2's
advisor otherwise starts one more (billed) model round after the subscriber is gone.

## Running

```bash
cd core
export JAVA_HOME="<JDK 21 or later>"  # CI uses Temurin 21, JDK 24 works; one Maven build at a time

# One suite (always -am: without it Maven resolves sibling modules from ~/.m2, which can be stale)
mvn -o -pl app     -am test -Dtest=ApiJsonGoldenTest,JsonbRoundTripTest,JsonbJdbcWritersTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -pl agents  -am test -Dtest='ToolLoopCharacterisationTest,MeteringCharacterisationTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -pl agents-tools -am test -Dtest=McpClientPerRequestBearerContractTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -pl mcpserver -am test -Dtest=McpServerStreamableHttpContractTest -Dsurefire.failIfNoSpecifiedTests=false

# Everything except the Testcontainers ITs
mvn -o test

# The Testcontainers IT (needs Docker; see INTEGRATION-TESTS.md for the Postgres image switch)
mvn -o -P it -pl app -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=JsonbRoundTripIT
```

## Golden files

Golden files are under `core/app/src/test/resources/golden/`. They are compared **textually** (pretty
printed, two-space indent, LF), so property order and number and date formatting are part of the
contract. A mismatch fails with a line diff (`-` golden, `+` actual).

Regenerate after an intended change:

```bash
mvn -o -pl app -am test -Dtest=ApiJsonGoldenTest -Dsurefire.failIfNoSpecifiedTests=false -Dgolden.update=true
git diff core/app/src/test/resources/golden/     # review: every changed line is a wire-format change
```

Rules: never regenerate to make a migration PR green without reading the diff; a diff on the Jackson 3
step is expected to be reviewed line by line, and the fix is normally to configure the new mapper to
keep the old output (`spring.jackson.use-jackson2-defaults=true`, ADR-0012 P2), not to accept the new
output. The one golden compared with sorted keys (`page-of-transactions`) is a bean-style class whose
property order follows reflection order and differs between JVM runs; records keep declaration order
and are compared exactly. Error bodies mask only `timestamp`.

To add a DTO: add a test method in `ApiJsonGoldenTest` using `RecordFixtures.populated/sparse` (builds
every record component deterministically, so a field added later shows up in the diff), run once with
`-Dgolden.update=true`, review, commit.

## Known gaps

- `JsonbRoundTripIT` was not executed when written (no Docker on the authoring machine). It is in
  `core/app/src/test/java/com/agenticai/core/it/`, so the CI `core` job's `mvn -Pcoverage,it verify` now runs it.
- The tool-loop and metering fixtures model the Spring AI 1.1 provider loop (internal loop, cumulative
  usage); they are not a recording of a specific provider. Provider-specific streaming quirks (for
  example Gemini mixed content, OpenAI-compatible usage chunks) are covered by their own unit tests,
  not here.
- The MCP server test runs without the API-key filter (covered by `McpApiKeyAuthFilterTest`) and
  without a knowledge service, so `search_knowledge` is not listed.
- Full `ApplicationContextIT`, `MigrationsIT` and the web e2e contract remain the gates named in
  ADR-0012 section 7.
