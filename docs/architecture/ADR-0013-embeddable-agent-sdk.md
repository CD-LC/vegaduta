# ADR-0013: An embeddable, governance-first agent SDK for Java teams (research + plan)

| | |
|---|---|
| **Status** | Proposed; phase 0 and the phase-1 artifacts implemented 2026-10-12 (see Progress) |
| **Progress** | **2026-10-12:** `sdk/java/` reactor created with `agent-api`, `agent-core`, `agent-jdbc`, `agent-testkit`, `agent-spring-ai`, `agent-spring-boot-starter` and `agent-bom`; `mvn -f sdk/java/pom.xml verify` is green (55 tests) on JDK 17 with Spring AI 2.0.1 and Boot 4.1.1; sources, javadoc and CycloneDX SBOM attached per artifact; flatten, enforcer, japicmp (skipped until a baseline exists) and the Central Portal release profile are configured. Decision change: **every artifact is Java 17** (section 7.4 updated); the only local JDK was 17 and 17 widens adoption. Also done: `examples/sdk/governed-tools` (Spring Boot app with `@Governed` tools, approvals endpoint, H2 stores; its test runs without a model and passes against the installed SDK), the `sdk` CI job in `.github/workflows/ci.yml` (spotless on JDK 21, verify on JDK 17, reproducible-build diff, example compile, SBOM upload), `sdk/java/README.md`. Not done: namespace verification and first publish (7.1), `core/` dogfooding (6.2), phases 2 and 3. Tooling note: google-java-format (any release) does not start on JDK 17.0.20's javac, so `spotless` runs on a JDK 21 (locally via `JAVA_HOME`, in CI as a separate setup-java step); the sources are formatted and `spotless:check` is clean. **2026-10-14:** governance-gaps batch (all `@Experimental`): `agent-scanner` module (risk map, readiness score, `--fail-under` CLI gate); in `agent-core` the packages `evidence` (hash-chained and signed audit, `EvidenceBundle`, EU AI Act / OWASP ASI / underwriting exporters), `autonomy` (L1-L4 registry and gate), `replay` (`PolicyReplay`, `ShadowGate`, `ShadowReport`, `PolicyDiff`), `mandate`, `halt` (kill switch, drill, `HaltStore` SPI), `provenance` (manifest pinning, drift), `cost` (model, ledger, showback), `memory` (governed long-term memory, poison probe), `journal` reconciliation, approval notifier (Slack/Teams) and signed receipts; `trace` record/replay in `agent-testkit`; `CompositeToolDispatchGate` ordering halt, mandate, autonomy, provenance, policy; one-call switches on `Governance.Builder`. A review pass over the batch fixed 84 findings (attribute shadowing in the chain canonical form, chain resume, fail-closed autonomy/mandate/provenance paths, replay double counting, report injection). Docs: `docs/sdk/compliance-mapping.md`, `security-review-pack.md`, `adoption-playbook.md`, `positioning.md`; README "What's new" and "governance you can prove"; the `examples/sdk/refund-agent/java` example demonstrates autonomy demotion, a signed `HashChainedAuditSink` and the OWASP coverage report and runs against the installed snapshot. `mvn -f sdk/java/pom.xml verify` is green on JDK 17.0.20.1 (322 tests: api 20, core 230, jdbc 5, testkit 23, scanner 32, spring-ai 8, starter 4). `spotless:check` was not run locally in this pass (only JDK 17 installed; it runs on JDK 21 in CI). Not committed yet. |
| **Date** | 2026-10-12 |
| **Deciders** | Platform Engineering (maintainers) |
| **Related** | [ADR-0011 provider plugins](ADR-0011-provider-plugins.md) (the `core-spi` extraction and starter pattern), [ADR-0012 Boot 4 / Spring AI 2](ADR-0012-spring-ai-2-boot-4.md) (the framework baseline the SDK must target), `docs/plans/adr/coding-agent-harness.md`, `docs/plans/adr/step-journal-resumable-jobs.md`, `contracts/outbound/audit-event.schema.json` |

> **Evidence rules.** Code facts come from grep over `core/*/src/main` and `core/*/pom.xml` on
> 2026-10-12 (branch `feat/oss-site-mode`). Nothing was compiled. External facts come from the
> sources in section 12, read on 2026-10-12; `github.com`, `docs.langchain4j.dev` and
> `opentelemetry.io` could not be fetched directly from this environment, so claims that rest
> only on secondary coverage are marked **verify**. Treat every **verify** as a phase-0 task.

---

## 1. Question

A maintainer asked: *"Can we build something like dependencies that a Java dev team adds to
their project to get agentic AI? Open source, standards followed, out of the box, something a
corporate team must implement and must use."*

Short answer: **yes, but not as another "call the model" framework.** Spring AI, LangChain4j,
Embabel, Google ADK and Koog already own model calls, tool loops and orchestration. What no
open-source Java library offers today is the **control plane an enterprise reviewer asks for**:
which tools an agent may reach, who approved a side-effecting action, what a run cost, whether a
replay is safe, one audit record across model calls, tool calls and approvals, and a mapping to
OWASP's agentic risk list and the EU AI Act. VegaDūta already has most of that in `core/`
(sections 3 and 4). The plan is to extract it as a small set of Maven Central artifacts that
sit **on top of** Spring AI (first) and LangChain4j (second), the way Embabel sits on Spring AI,
but for governance rather than planning.

Working name in this document: **VegaDūta Agent SDK**, Maven group `io.vegaduta`
(section 7.1 explains why the group is a phase-0 decision).

---

## 2. Context: what Java teams can already get (October 2026)

### 2.1 Frameworks

| Project | Status | What it gives | What it does not give (relevant to this ADR) |
|---|---|---|---|
| **Spring AI** 2.0.1 (GA 2026-06-12; 2.1.0-M1 2026-09-25) | GA | `ChatClient`, advisors, tool calling, MCP client/server (MCP Java SDK 2.x), observability via Micrometer. 2.0 moved the tool loop into a composable advisor and added context compaction. | No approval gate, no budgets, no tool risk model, no audit schema. The Spring team says "the new agentic support" ships **November 2026 as a separate project under Spring Projects Experimental**, to be merged into Spring AI 3.0 "mid next year". Scope is not public. |
| **spring-ai-agent-utils** (community) 0.11.x | community | Agent Skills implementation, file/shell/web-fetch tools, memory utilities. **verify** version and licence. | Governance, approvals, budgets: not advertised. |
| **LangChain4j** 1.22.x core, `langchain4j-agentic` 1.22.0-beta32 | core GA; agentic **beta** | Framework-agnostic (Quarkus, Micronaut, plain Java). Sequence/parallel/loop/supervisor agents, `AgenticScope`, crash-resilient human-in-the-loop suspend/resume with `AgenticScopeStore`, compensation for tool actions, A2A client module, MCP client on spec 2026-07-28. | Governance is per-workflow, not per-tool. No budgets, no tool risk classification, no audit schema, observability is "manual OTEL". **verify** against the official tutorial. |
| **Embabel** 1.0 (GA 2026-07, InfoQ 2026-08) | GA | GOAP planning over typed goals/actions/conditions, personas; Kotlin + Java; Apache-2.0; sits above Spring AI. | Built on Spring AI 1.1.7 / Boot 3.5 (Spring AI 2 support **verify**). No governance layer. |
| **Google ADK for Java** 1.0.0 (2026-03-30), later 1.x | GA (1.x); 2.0 GA for Java **verify** | A2A-centric multi-agent runtime, tight Gemini/Vertex integration. | Google-first; governance not a focus. |
| **Koog** 1.0 (JetBrains, KotlinConf 2026-05) | GA, 1-year API stability promise | Kotlin-first agents with Java support, multiplatform observability. | Kotlin idioms; no enterprise control plane. |
| **Microsoft Agent Framework** 1.0 (GA 2026-04) | GA for .NET and Python | Workflows, MCP, A2A, DevUI. | **No Java SDK.** |
| **Jakarta Agentic AI** 1.0 draft (M1, 2026-07-30) | draft spec, no RI or TCK yet | CDI-based `@Agent` with `@Trigger`/`@Decision`/`@Action`/`@Outcome` phases, a minimal `LargeLanguageModel` facade. | Explicitly **no tools, no memory, no provider APIs** in 1.0. Not a competitor; a possible future adapter target. |

### 2.2 Governance and security tooling

| Project | Java? | Open source? | Notes |
|---|---|---|---|
| Microsoft Agent Governance Toolkit (2026-04-02) | **No** (.NET, TypeScript, Rust, Go, Python as of v3.3.0) | MIT | Claims coverage of all ten OWASP agentic risks, EU AI Act / HIPAA / SOC 2 evidence mapping. The nearest thing to this ADR, in every language except Java. |
| AgentAdmit SDK `com.agentadmit:agentadmit-sdk` 1.5.1 | Yes (Spring Boot) | **Proprietary** | "User-mediated AI agent authorization". Approval only. |
| `ai.deep-code:agents-kt` 0.8.2 (2026-07) | Kotlin/JVM | licence **verify** | "Permission manifests and audit evidence by construction". Early. |
| `AgentBudgetGuard` 0.1.0 | Yes | OSS | Spend limit wrapper for LLM calls and Spring beans. Pre-1.0, single concern. |
| OWASP **Agent Control Standard (ACS)** v0.1 (donated by Zenity 2026-05; under OWASP since 2026-09-01) | spec, not code | MIT | Defines middleware hook points at agent execution points, portable declarative policies, an Agent Bill of Materials (AgBOM). v0.1 public preview; hook names **verify** from the GitHub repository. |

**Reading of the market.** Orchestration is crowded and about to get more crowded (Spring AI
Agents in November). Governance in Java is empty: the only full toolkit has no Java SDK, the only
Java approval SDK is proprietary, and the budget and audit libraries are single-purpose and
pre-1.0. This is the gap to fill.

### 2.3 Standards the SDK must speak

| Standard | Current version | Where it touches the SDK |
|---|---|---|
| **MCP** | 2026-07-28 (stateless core, `server/discover`, Multi Round-Trip Requests, cacheable list results, extensions framework, Roots/Sampling/Logging deprecated, DCR deprecated in favour of Client ID Metadata Documents, OTel `traceparent` in `_meta`) | Tool argument/output hardening, origin pinning, secret redaction, timeouts; reuse the MCP Java SDK 2.x (managed by the Spring AI 2.0 BOM) rather than a transport of our own. |
| **A2A** | 1.0 (Linux Foundation, 2026-01/03), Java SDK `org.a2aproject.sdk` (Red Hat Quarkus/WildFly teams; last release 2026-04-17) | Egress policy and card verification only; do not reimplement the protocol. |
| **AG-UI** | Java SDK `com.ag-ui:*` 0.0.1, client-side only; protocol "subject to change" | Out of SDK v1. The platform keeps its server-side mapper. |
| **Agent Skills** (agentskills.io, 2025-12-18) | `SKILL.md` + YAML frontmatter, progressive disclosure; ~40 client implementations by mid-2026 | Parser, integrity hash, supply-chain scan. |
| **OpenTelemetry GenAI semantic conventions** | `gen_ai.*` spans (`invoke_agent`, `create_agent`, `execute_tool`) are **Development**, not Stable, as of 2026-07 | Emit them, pin the semconv version, document `OTEL_SEMCONV_STABILITY_OPT_IN`. |
| **OWASP Top 10 for Agentic Applications 2026** (ASI01–ASI10, 2025-12-09) and **ACS** v0.1 | published / preview | Every control in the SDK is tagged with the ASI items it mitigates; hook points track ACS names once stable. |
| **EU AI Act** | Article 50 transparency in force **2026-08-02**; stand-alone high-risk obligations deferred to **2027-12-02** by Regulation (EU) 2026/1744 (the "AI Omnibus", in force 2026-07-27) | AI-disclosure helper, log retention policy (high-risk deployers keep automatically generated logs for at least six months), human-oversight evidence from the approval trail. |
| ISO/IEC 42001, NIST AI RMF | management standards | A mapping document, not code. |
| Java library hygiene | Maven Central Portal (OSSRH retired 2025-06-30), mandatory signing, publishing limits enforced from 2026-10 (**verify** exact date), reproducible builds, CycloneDX SBOM, SLSA provenance, OpenSSF Scorecard, semver with japicmp, JSpecify nullness, JPMS `module-info` | Section 7. |

---

## 3. What the platform already has (and how coupled it is)

All numbers from the tree on 2026-10-12.

### 3.1 Governance primitives that exist today

| Concern | Where | Shape | OWASP ASI it addresses |
|---|---|---|---|
| Tool dispatch policy | `guardrails/ToolDispatchGate` | `Verdict check(String toolName)` with `allow / deny / requireApproval` | ASI02 tool misuse, ASI05 code execution |
| Tool risk classification | `agents/tools/classification/ToolClassification`, `ToolClassificationDecision` (sealed: `Classified` / `Blocked(UNCLASSIFIED, QUARANTINED)`) | `requiresHumanConfirmation()`, `producesTaintedOutput()`, `isTaintSink()`, `requiresIdempotencyKey()`, `riskLabel()` | ASI01 goal hijack, ASI02, ASI06 memory poisoning |
| "Lethal trifecta" detection | `agents/tools/LethalTrifectaDetector`, `TrifectaEnforcementService` (`warn` / enforce modes) | Blocks an agent that combines private-data read, untrusted input and an exfiltration sink | ASI01, ASI02, ASI07 |
| Guarded tool wrapper | `guardrails/GuardedToolCallback` (wraps Spring AI `ToolCallback`) | Approval gate modes, procedure guard, step limits, Micrometer `agent.tool.calls` / `agent.tool.errors` | ASI02, ASI08 cascading failures |
| Human approval | `approval/HumanApprovalGateService`, `ApprovalRequest`, `ApprovalActionExecutor` (+ registry), `ApprovalDecisionAuthorizer`, `ApprovalReplayGrant`, retry job | Record, approve/reject with four-eyes authorisation, replay envelope, retry on executor failure | ASI09 human-agent trust |
| Run budgets | `agents/tools/RunBudgetToolCallback`, `RunBudget`, `AgentRunBudgetService` | Per-run tool/token/cost budget, delegate usage recording | ASI08 |
| Idempotent effects and resumable steps | `agents/tools/EffectJournal` (+ `DurableStore`), `StepJournal` | `keyFor(runId, stepId, tool, args)`, lookup-before-call, durable-call begin/done | ASI08 |
| Hooks (pre/post tool, webhook, rate-limited, redacted, fail-open/closed) | `agents/hooks/*` (27 files: `HookPoint`, `HookEngine`, `HookMatcher`, `FailMode`, `HookRateLimiter`, `HookRedactor`, `HookWebhookClient`) | The nearest thing in the tree to ACS "middleware hooks" | ACS alignment |
| Content guardrails | `guardrails/ContentGuardrail`, `ContentGuardrailEngine`, `PatternBasedGuardrailService` (re2j), `SemanticGuardrailService`, `toolargs/`, `procedures/` | Input/output/tool-argument checks | ASI01, ASI06 |
| MCP hardening | `agents/tools/McpToolOutputSanitizer`, `McpToolArgumentGuard`, `McpSecretRedactor`, `McpOriginPinning`, `NamespacedMcpToolCallback`, `SanitizedMcpToolCallback`, `EnvIsolatedStdioClientTransport`, `McpClientTimeoutConfig`, `RemoteMcpPreProbe` | Client-side defence against hostile or compromised MCP servers | ASI04 supply chain, ASI07 inter-agent |
| Skills supply chain | `agents/skills/SkillMarkdownParser`, `SkillScanner`, `SkillScanRules`, `SkillIntegrity`, `SkillArchiveReader`, `SkillSupplyChainService` | Parse `SKILL.md`, hash, scan for prompt-injection and dangerous instructions | ASI04 |
| Observability | `chat/LlmMetricsAdvisor` (OTel `gen_ai.*`), `LlmCallLog`, `LlmRetry`, `ChatOptionsCopy`, `ProviderErrorTranslator` | Spring AI advisors | logging/evidence |
| Audit record | `audit/*`, wire shape already published as `contracts/outbound/audit-event.schema.json` | | evidence for EU AI Act / ISO 42001 |
| A2A egress | `agents/a2a/A2aEgressGuard`, `A2aCardVerifier`, `A2aCardSigningService` | Allow-list and signed cards for outbound agent calls | ASI07 |

### 3.2 Coupling that stands in the way

| Module | Main files | JPA entities | Files importing `org.springframework` | Sibling deps |
|---|---:|---:|---:|---|
| agents | 1043 | 49 | 533 | 24 modules |
| guardrails | 95 | 6 | 37 | json, approval, audit, tenancy |
| approval | 23 | 2 | 9 | audit, tenancy, security, rl, eventbus |
| audit | 35 | 4 | 12 | json |
| chat | 124 | 3 | 93 | audit, json, providers, security, tenancy |

Three facts drive the design in section 5:

1. **The governance classes are Spring AI-typed, not Spring-typed.** `agents` references
   `org.springframework.ai.tool.ToolCallback` 133 times and `ToolDefinition` 84 times, against
   46 for `ChatClient`. The wrapper pattern (`GuardedToolCallback`, `RunBudgetToolCallback`,
   `SanitizedMcpToolCallback`, `HookingToolCallback`) is one interface deep. A framework-neutral
   `Tool` abstraction plus one adapter per framework covers it.
2. **Tenancy and persistence are threaded through by concrete types.** `TenantContext` /
   `TenantScope` (`security`), JPA repositories (`ApprovalRequestRepository`,
   `GuardrailRuleRepository`, `StepJournalRepository`), `@Value` properties and Micrometer
   `MeterRegistry` appear inside the policy logic. The SDK must replace those with a
   `RunContext` record and store SPIs; the platform keeps its JPA implementations as adapters.
3. **Platform-only concerns are mixed in.** RL feedback (`approval` depends on `rl`),
   `IssueService` recording, free-tier limits and the `eventbus` publication belong to the
   product, not the library. They become decorators the platform applies around SDK types.

---

## 4. Decision drivers

- **D1. Fill the real gap.** Governance, not orchestration. A Java team that already uses
  Spring AI or LangChain4j must be able to add one dependency and keep their code.
- **D2. Framework-neutral core, framework-specific adapters.** The `api` and `core` artifacts
  depend on the JDK only (plus SLF4J). Spring AI, LangChain4j and Jakarta adapters are
  separate artifacts.
- **D3. Standards over invention.** Reuse the MCP Java SDK, the A2A Java SDK, the Agent Skills
  format, OTel GenAI attributes, OWASP ASI identifiers and ACS hook names. Do not create a
  protocol or a DSL.
- **D4. Corporate adoptability.** Apache-2.0, Maven Central, signed and reproducible, SBOM
  and provenance, semver with a public API gate, no transitive surprises, works under Spring
  Boot AOT and GraalVM native image, Java 17 bytecode for the `api` artifact.
- **D5. Dogfooding is the proof.** `core/` must consume the SDK. A library the platform does
  not use will rot.
- **D6. Small team.** Each phase ships something usable and reversible on its own.

---

## 5. Options considered

| # | Option | Verdict |
|---|---|---|
| A | Publish `core/*` modules as they are to Maven Central | **Rejected.** They drag Spring Boot web, JPA, Postgres, tenancy and Keycloak into every consumer, and the Boot starter parent makes the POMs unpublishable without flattening. |
| B | Build a new orchestration framework (planner, DSL, multi-agent) | **Rejected.** It competes with Spring AI Agents (November), LangChain4j agentic, Embabel, ADK and Koog, and it is not where the platform's advantage is. |
| C | A single fat `vegaduta-agent` jar on Spring AI | **Rejected.** Locks out Quarkus/LangChain4j users (D2) and ties the public API to Spring AI's release train. |
| D | **Layered SDK: framework-free `api` + `core`, per-framework adapters, a Boot starter, a JDBC store, a test kit, a BOM** | **Chosen.** Mirrors how Spring AI and LangChain4j package themselves, and how ADR-0011 already splits `core-spi` from starters. |
| E | Contribute the governance layer upstream into Spring AI / LangChain4j instead | **Deferred.** Pursue after 1.0 for small pieces (for example a `ToolDispatchGate` advisor). Upstream cadence and scope are outside our control, and the framework-neutral core would be lost. |
| F | Separate GitHub repository from day one | **Deferred.** Start as a separate Maven reactor `sdk/` in this repository so that `core/` can dogfood unreleased changes in one PR. Split out once 1.0 ships, if contributors ask. |

---

## 6. Decision: the artifact set

All artifacts share the group `io.vegaduta` (decision, see 7.1), the version line `0.x` until
the API freeze in phase 3, Apache-2.0, and a `vegaduta-agent-bom`.

| Artifact | Depends on | Contents (extracted from) | Java |
|---|---|---|---|
| `vegaduta-agent-api` | JDK, JSpecify | Interfaces, records, enums only: `Tool`, `ToolCall`, `ToolResult`, `RunContext` (tenant, principal, agentId, sessionId, runId, correlation), `ToolDispatchGate` + `Verdict`, `ToolClassification` + `RiskLabel` + `ToolClassificationDecision`, `ApprovalGate` / `ApprovalStore` / `ApprovalDecisionAuthorizer` / `ApprovalActionExecutor`, `Budget` / `BudgetReservation`, `EffectJournal` / `StepJournal` SPIs, `ContentGuardrail` + result types, `HookPoint` / `HookDefinition` / `FailMode`, `AuditEvent` (same shape as `contracts/outbound/audit-event.schema.json`) + `AuditSink`, `GovernanceException` hierarchy, `AsiRisk` enum (ASI01–ASI10) used as metadata on every control | **17** |
| `vegaduta-agent-core` | api, SLF4J; optional Micrometer, optional OTel API, Jackson | Default implementations: `PolicyToolDispatchGate` (allow-list, risk tiers, strict/warn modes), `LethalTrifectaDetector`, `InMemoryApprovalStore` + `HumanApprovalGate` with four-eyes rule, replay envelope and expiry, `AtomicBudget` (reserve-then-commit, safe under concurrent tool calls), `InMemoryEffectJournal` / `StepJournal`, `PatternGuardrail` (re2j), `HookEngine` with matcher, rate limiter, redactor and webhook client, `GovernedToolDecorator` (one wrapper composing gate, approval, budget, journal, hooks, audit, metrics), `GenAiTelemetry` (OTel `gen_ai.*` span and attribute helper, semconv version pinned), `LogMasking` | 21 |
| `vegaduta-agent-spring-ai` | core, `spring-ai-client-chat` (2.0.x; see 7.3) | `GovernedToolCallback` (Spring AI `ToolCallback` adapter), `GovernanceAdvisor` (`CallAdvisor`/`StreamAdvisor` for budgets and content guardrails on `ChatClient`), `ToolCallbackProvider` decorator, `LlmRetry`, `ChatOptionsCopy`, `ProviderErrorTranslator` | 21 |
| `vegaduta-agent-spring-boot-starter` | spring-ai adapter, Boot autoconfigure | `@AutoConfiguration` with `vegaduta.agent.*` properties (mode `strict`/`warn`/`off`, risk defaults, approval expiry, budget defaults, OTel on/off), optional approval REST endpoints under `/vegaduta/approvals` (gated by a property), Actuator health and metrics, Spring Boot AOT hints | 21 |
| `vegaduta-agent-mcp` | core, MCP Java SDK 2.x | `McpToolOutputSanitizer`, `McpToolArgumentGuard`, `McpSecretRedactor`, `McpOriginPinning`, `NamespacedTool`, `EnvIsolatedStdioTransport`, timeout config, `RemotePreProbe`; aligned with MCP 2026-07-28 (stateless, `server/discover`, MRTR `input_required` handling as approval prompts) | 21 |
| `vegaduta-agent-skills` | core | `SkillMarkdownParser`, `SkillDefinition`, `SkillIntegrity`, `SkillScanner` + rules, archive reader; conforms to the Agent Skills spec | 21 |
| `vegaduta-agent-jdbc` | core, JDBC | Durable `ApprovalStore`, `EffectJournal`, `StepJournal`, `AuditSink` on plain JDBC; ships DDL for PostgreSQL and H2 as resources (`db/vegaduta-agent/*.sql`); **no Flyway dependency**, the host application owns migrations | 21 |
| `vegaduta-agent-langchain4j` | core, `langchain4j-core` | `ToolExecutor` and guardrail adapters; agentic-module hooks once that module leaves beta | 21 |
| `vegaduta-agent-testkit` | core, JUnit 5, AssertJ | `FakeModel`, `RecordingAuditSink`, `ScriptedApprover`, `GovernanceAssertions` (`assertThat(run).requiredApprovalFor("send_email")`, `.stayedWithinBudget()`, `.emittedAsiControls(ASI02)`), replay fixtures, an ArchUnit rule set consumers can run ("no `ToolCallback` reaches the model ungoverned") | 21 |
| `vegaduta-agent-compliance` | core | EU AI Act Article 50 disclosure helper (system-prompt and response-metadata flags), `RetentionPolicy` (default 6 months for high-risk), AgBOM generator (JSON, from the tool manifest and classifications), ISO 42001 / NIST AI RMF / ASI mapping as resources | 21 |
| `vegaduta-agent-maven-plugin` | compliance | `verify` goal: fails the build if a `@Tool` is unclassified in strict mode, emits the AgBOM, checks `SKILL.md` integrity | 21 |
| `vegaduta-agent-bom` | | Managed versions for all of the above plus the MCP and A2A SDK versions we test against | |

Out of scope for 1.0: an orchestration DSL, an LLM client, a UI, runtime plugin loading
(ADR-0011 option C rationale applies), AG-UI server support, A2A protocol implementation
(reuse `org.a2aproject.sdk`; only `A2aEgressGuard` moves, into `vegaduta-agent-mcp`'s sibling
`vegaduta-agent-a2a` in phase 3).

### 6.1 The out-of-the-box experience this must deliver

Target: a Spring Boot team goes from zero to a governed tool in under fifteen minutes.

```xml
<dependency>
  <groupId>io.vegaduta</groupId>
  <artifactId>vegaduta-agent-spring-boot-starter</artifactId>
</dependency>
```

```java
@Component
class Payments {
  @Tool(description = "Refund an order")
  @Governed(risk = RiskLabel.HIGH, requiresApproval = true, idempotent = true)
  String refund(String orderId, long cents) { ... }
}
```

With no further configuration, in `warn` mode (the default) the starter wraps every Spring AI
`ToolCallback` and: classifies it (unclassified tools are logged in `warn`, blocked in
`strict`), records an approval request instead of executing a `requiresApproval` tool,
reserves budget before the call, journals the effect key so a retried turn does not refund
twice, emits an `AuditEvent` and a `gen_ai` span, and runs any registered hooks. Approvals are
decided through `ApprovalGate.approve(id, decidedBy)` from the host app's own UI, or through the
optional starter endpoints. Everything persists in memory until `vegaduta-agent-jdbc` is on the
classpath.

### 6.2 How `core/` consumes it (dogfooding)

- `guardrails`, `approval`, `agents/tools`, `agents/hooks`, `agents/skills` keep their REST
  controllers, JPA entities and tenant wiring, and delegate policy to SDK types. Their JPA
  repositories implement the SDK store SPIs.
- `TenantContext` is mapped to `RunContext` at the boundary (`ChatTurnExecutor`,
  `AgentToolGuard`).
- Platform-only decorators (RL feedback, `IssueService`, free-tier limits, `eventbus`) wrap
  the SDK `ApprovalGate` and `AuditSink`.
- The ArchUnit suite in `core/app` gains a rule: no class in `core/*` may implement
  `ToolDispatchGate` or `ApprovalGate` logic directly; it must use the SDK.

---

## 7. Decisions that need settling in phase 0

### 7.1 Group id and namespace

Maven Central namespace verification needs a domain (`io.vegaduta` requires control of
`vegaduta.io`, proven by a DNS TXT record) or falls back to
`io.github.cdandlc`. The latter is unreadable in a POM and hurts
adoption. **Recommendation:** register `vegaduta.io` (or use an existing project domain) and
verify `io.vegaduta` before anything is published. Do not use `com.agenticai` for published
artifacts; it is the retired internal name (AGENTS.md naming rule).

### 7.2 Repository layout

```
sdk/                         first-party SDKs; typescript/ and python/ are the generated REST clients
sdk/java/                    the Agent SDK: separate Maven reactor, own parent (NOT spring-boot-starter-parent)
  pom.xml                    imports spring-ai-bom and spring-boot-dependencies as BOMs only
  agent-api/ agent-core/ agent-spring-ai/ agent-spring-boot-starter/ agent-mcp/
  agent-skills/ agent-jdbc/ agent-langchain4j/ agent-testkit/ agent-compliance/
  agent-maven-plugin/ agent-bom/
examples/sdk/                governed-tools (Spring Boot), langchain4j-quarkus, plain-java
```

`core/pom.xml` depends on `io.vegaduta:*` at the reactor version. CI builds `sdk/java/` first and
installs it (`mvn -f sdk/java/pom.xml install`), then `core/`; the "one Maven build at a time" rule
in AGENTS.md still holds. `sdk/java/` gets its own tag prefix (`sdk-v0.1.0`) and release job.

### 7.3 Spring AI version for the adapter

The platform is on Spring AI 1.1.8 / Boot 3.5.16 (both OSS-EOL; ADR-0012 P2/P3 not started).
Publishing an adapter against 1.1.x would ship a dead line to new users. **Decision:** the
published `vegaduta-agent-spring-ai` targets **Spring AI 2.0.x / Boot 4.x** from the first
release. Until ADR-0012 P3 lands, `core/` dogfoods `api`, `core`, `mcp`, `skills` and `jdbc`
(all framework-free) and keeps its own 1.1.x `GuardedToolCallback` as a thin shim. This makes
ADR-0012 a prerequisite for phase 2's "platform uses the Spring AI adapter" milestone, and is a
reason to schedule it.

### 7.4 Java baseline

**Every artifact compiles to Java 17 bytecode** (decided 2026-10-12, superseding the earlier
"api on 17, rest on 21" split). Boot 4's minimum is 17, Jakarta Agentic AI targets SE 17, and
17 is the floor most enterprises are on. Records and sealed types are available on 17; nothing
in the SDK needs virtual threads or pattern matching for switch. The platform's own `core/`
stays on 21.

### 7.5 Release hygiene checklist (all are phase-0 deliverables)

- Central Portal publishing with `central-publishing-maven-plugin`; GPG signing now, Sigstore
  when Central accepts it.
- Reproducible builds: `project.build.outputTimestamp`, verified with
  `mvn -Dreproducible verify` in CI (artifact rebuilt twice, checksums compared).
- CycloneDX SBOM per artifact (`cyclonedx-maven-plugin`), SLSA provenance via GitHub
  `attest-build-provenance` (the image pipeline in `.github/workflows/release.yml` already does
  this; reuse it).
- `japicmp` gate on `api` and `core`: no breaking change without a major bump after 1.0; a
  `@Experimental` annotation for anything exempt.
- JSpecify `@NullMarked` packages; `module-info.java` in `api` and `core`; GraalVM
  reachability metadata in `core` and the starter; Spring Boot AOT test in CI.
- OpenSSF Scorecard already runs for the repository; add the Best Practices badge application.
- `javadoc` and `sources` jars; a `CHANGELOG.md` section per SDK release under a new
  `## SDK` heading, or a separate `sdk/CHANGELOG.md` (pick one in phase 0).

---

## 8. Phases

Each phase ends with a verification gate. "Done" means the gate passed, not that code exists.

### Phase 0: foundations (about 2 weeks)

- Decide 7.1 to 7.5. Register the namespace. Create `sdk/` with `agent-api`, `agent-bom` and
  the release workflow. Publish `0.1.0-SNAPSHOT` of `agent-api` to Central snapshots.
- Resolve every **verify** in this document from primary sources: LangChain4j agentic API and
  licence, spring-ai-agent-utils scope, ACS hook names and AgBOM schema, OTel semconv status,
  Central publishing limits date, ADK Java 2.0 status, agents-kt licence.
- Gate: a consumer project with only `agent-api` on the classpath compiles on Java 17; japicmp,
  reproducible-build check, SBOM and provenance jobs are green on the snapshot.

### Phase 1: governed tools (about 6 weeks)

- `agent-core`, `agent-spring-ai`, `agent-spring-boot-starter`, `agent-jdbc`, `agent-testkit`
  (minimum: `FakeModel`, `RecordingAuditSink`, `ScriptedApprover`).
- Extract, in this order, with characterisation tests first (see
  `docs/engineering/CHARACTERISATION-TESTS.md`): `ToolDispatchGate` and classification;
  `GuardedToolCallback` into `GovernedToolDecorator`; approvals; budgets (rewrite as
  reserve-then-commit: today's `agents/collab/RunBudget` synchronises each method, but
  `isExceeded()` and `record()` are separate calls and `record` runs after the call, so two
  parallel tool calls can both pass the check and both overspend); effect and step journals; audit
  event; OTel helper.
- `core/guardrails` and `core/approval` switch to the SDK types (dogfood gate for
  framework-free artifacts).
- `examples/sdk/governed-tools` with a README that a new user can follow in 15 minutes; time
  it with someone outside the team.
- Gate: full `core/` reactor green with the SDK in place; example app runs against Ollama with
  no key; approval and budget behaviour covered by `agent-testkit` assertions.

### Phase 2: supply chain, hooks, second framework (about 6 weeks)

- `agent-mcp` (requires MCP Java SDK 2.x, so this is the first module that forces the Spring
  AI 2.0 BOM; the platform keeps its 1.1.x MCP path until ADR-0012 P3), `agent-skills`, hooks
  in `agent-core`, `agent-langchain4j`, `examples/sdk/langchain4j-quarkus`.
- Map `HookPoint` names to ACS once its hook list is confirmed; publish the mapping.
- Gate: an MCP server fixture that returns hostile output (prompt injection in tool results,
  oversized payloads, secrets) is neutralised by `agent-mcp` under test; a `SKILL.md` corpus
  with known-bad skills is flagged; the LangChain4j example passes the same `agent-testkit`
  assertions as the Spring one.

### Phase 3: compliance, A2A guard, 1.0 (about 6 weeks)

- `agent-compliance`, `agent-maven-plugin`, `agent-a2a` (egress guard and card verification on
  top of `org.a2aproject.sdk`).
- Documentation set: quickstart, "governance concepts" guide, ASI01–ASI10 control matrix, EU AI
  Act / ISO 42001 / NIST AI RMF mapping, migration notes for Spring AI Agents (whatever ships in
  November) and LangChain4j agentic GA.
- API freeze: `1.0.0` of `api`, `core`, `spring-ai`, `starter`, `jdbc`, `testkit`; everything
  else stays `0.x` until it has two external users.
- Gate: ADR-0012 P3 complete so that `core/` runs on the published Spring AI adapter; japicmp
  baseline recorded; at least one outside project (a community user or a second internal
  service) on the 1.0 release candidate.

### After 1.0

- Jakarta Agentic AI CDI adapter if the spec reaches a final 1.0 with a TCK.
- Upstream proposals: a `ToolDispatchGate`-style advisor to Spring AI, a tool-classification
  guardrail to LangChain4j.
- Split `sdk/` into its own repository if contributor volume justifies it.

---

## 9. Consequences

**Positive**
- Java teams get, in one dependency, the controls that security and compliance reviewers ask
  for, and keep Spring AI or LangChain4j for the model and the loop.
- The platform's governance code gains a public, versioned, tested API and loses its hidden
  coupling to tenancy and JPA. ADR-0011's `core-spi` extraction and this SDK are the same work
  seen from two sides.
- The project has a story that is distinct from every framework listed in section 2, and is
  complementary to all of them.

**Negative / costs**
- A second reactor, a second release train and a public API contract to maintain.
- The platform must finish ADR-0012 (Boot 4 / Spring AI 2) to dogfood the Spring AI adapter.
- Some duplication during the transition: the 1.1.x shim in `core` and the 2.0 adapter in
  `sdk` coexist for one or two releases.

**Risks**
- *Spring AI Agents (November 2026) ships approvals or budgets.* Mitigation: the SDK is
  framework-neutral and positioned as the governance layer; adapt to their extension points
  instead of competing; evaluate within two weeks of their release and adjust phase 2.
- *OTel GenAI and ACS are still moving.* Mitigation: pin versions, keep names behind our own
  enums, document the mapping, treat renames as minor releases before 1.0.
- *Namespace not obtainable.* Mitigation: decide in phase 0 before any coordinates appear in
  documentation.

---

## 10. Success measures (review at the end of each phase)

| Measure | Target |
|---|---|
| Time from empty Spring Boot project to a governed, approval-gated tool, measured with a newcomer | under 15 minutes |
| Transitive dependencies of `agent-api` / `agent-core` | 1 / under 5 (SLF4J, Jackson, JSpecify, optional Micrometer and OTel API) |
| Classes in `core/guardrails`, `core/approval`, `core/agents/tools` replaced by SDK types | over 60 percent of the rows in table 3.1 by end of phase 2 |
| OWASP ASI items with at least one SDK control and a testkit assertion | 10 of 10 by 1.0 |
| Public API breakage after 1.0 | zero without a major version (japicmp) |
| External adopters on a release candidate | at least one before 1.0 |

---

## 11. What this ADR does not decide

- Pricing, support tiers or a commercial edition. The SDK is Apache-2.0 like the platform.
- Whether the tenant console grows an "SDK" page. Not needed for the library.
- The internal naming of `RunContext` fields beyond what section 6 lists; phase 1 settles it
  with the first consumer.

---

## 12. Sources (read 2026-10-12)

Frameworks and roadmaps
- Spring AI 2.1.0-M1 announcement, with the November 2026 agentic-project statement: <https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/>
- Spring AI 2.0.0 GA: <https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/>; 2.0.1: <https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/>
- Spring AI Agent Skills post: <https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills/>
- Spring I/O 2026 framework comparison (Salm / Ahlgrimm): <https://neatguycoding.com/posts/2026-05-18-spring-io-2026-comparing-agentic-ai-frameworks-for-java-by-timo-salm-sandra-ahlgrimm-sp/>
- LangChain4j agents tutorial: <https://docs.langchain4j.dev/tutorials/agents/>; agentic workflows walkthrough: <https://javapro.io/2026/07/08/langchain4j-agentic-workflows-from-ai-calls-to-multi-agent-systems-in-java/>; Quarkus agentic extension: <https://quarkus.io/extensions/io.quarkiverse.langchain4j/quarkus-langchain4j-agentic/>
- Embabel 1.0 coverage: <https://infoq.com/news/2026/08/embabel-1>; <https://www.besthub.dev/articles/spring-founder-returns-with-embabel-a-production-grade-java-agent-orchestration-framework-d12028024cbe>
- Google ADK for Java 1.0: <https://developers.googleblog.com/announcing-adk-for-java-100-building-the-future-of-ai-agents-in-java/>; releases: <https://github.com/google/adk-java/releases>
- Koog 1.0: <https://blog.jetbrains.com/ai/2026/05/koog-1-0-is-out-stable-core-better-interop-and-multiplatform-observability/>
- Microsoft Agent Framework 1.0 (no Java): <https://techcommunity.microsoft.com/blog/azuredevcommunityblog/the-future-of-agentic-ai-inside-microsoft-agent-framework-1-0/4510698>; Copilot SDK note on Java: <https://github.com/github/copilot-sdk/blob/main/docs/integrations/microsoft-agent-framework.md>
- Jakarta Agentic AI 1.0 draft: <https://jakarta.ee/specifications/agentic-ai/1.0/jakarta-agentic-ai-1.0.0-M1.html>; project announcement: <https://payara.fish/blog/announcing-the-jakarta-agentic-ai-project>
- Other comparisons: <https://codewiz.info/blog/java-ai-agent-frameworks-2026/>, <https://patotski.com/blog/spring-ai-vs-langchain4j/>, <https://dev.to/jamilxt/spring-ai-vs-langchain4j-in-2026-the-choice-got-harder-here-is-how-to-decide-429n>

Governance tooling
- Microsoft Agent Governance Toolkit: <https://opensource.microsoft.com/blog/2026/04/02/introducing-the-agent-governance-toolkit/>
- AgentAdmit SDK (proprietary): <https://central.sonatype.com/artifact/com.agentadmit/agentadmit-sdk>
- agents-kt: <https://mvnrepository.com/artifact/ai.deep-code/agents-kt/versions>
- AgentBudgetGuard: <https://github.com/SarkarRana/AgentBudgetGuard>; budget concurrency note: <https://dev.to/erislandys/the-budget-that-actually-blocks-the-concurrency-bug-an-ai-agents-spend-guardrail-cant-afford-29gj>; Oracle runtime budget guardrails: <https://blogs.oracle.com/ai-and-datascience/runtime-budget-guardrails-agentic-ai>
- LangGraph4j human-in-the-loop: <https://dev.to/bsorrentino/langgraph4j-implementing-human-in-the-loop-at-ease-565h>

Standards
- MCP 2026-07-28 changelog: <https://modelcontextprotocol.io/specification/2026-07-28/changelog>; announcement: <https://blog.modelcontextprotocol.io/posts/2026-07-28/>
- A2A Java SDK launch: <https://quarkus.io/blog/a2a-project-launches-java-sdk>; artifacts: <https://badges.mvnrepository.com/artifact/org.a2aproject.sdk/a2a-java-sdk-spec/used-by>
- AG-UI Java SDK: <https://docs.ag-ui.com/sdk/java/overview>; <https://javapro.io/2026/07/16/solving-spring-ais-ui-challenge-with-ag-uis-java-sdk/>
- Agent Skills adoption: <https://atlan.com/know/ai-agent/ai-agent-skills/what-are-agent-skills/>; <https://www.noqta.tn/en/news/skill-md-open-standard-30-ai-coding-tools-adoption-2026>
- OpenTelemetry GenAI agent spans: <https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-agent-spans>; status discussion: <https://dev.to/azena-ai/opentelemetrys-genai-semantic-conventions-are-not-stable-yet-heres-what-actually-shipped-in-2026-3mke>
- OWASP Top 10 for Agentic Applications 2026: <https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/>; Agent Control Standard: <https://genai.owasp.org/resource/agent-control-standard-acs/>; CSA note: <https://labs.cloudsecurityalliance.org/research/csa-research-note-owasp-genai-top10-2026-agent-control-stand/>; Pillar on ACS v0.1.0: <https://www.pillar.security/blog/standardizing-the-control-plane-for-ai-agents-pillars-role-in-acs-v0-1-0>
- EU AI Act and the AI Omnibus (Regulation (EU) 2026/1744): <https://www.addleshawgoddard.com/en/insights/insights-briefings/2026/technology/eu-ai-act-ai-omnibus-formally-adopted/>; <https://www.mondaq.com/eu-digital-omnibus-on-ai-enters-into-force/1825722>; agents under Article 50: <https://www.netskope.com/?p=76477>; ISO 42001 / NIST AI RMF comparison: <https://www.eccouncil.org/cybersecurity-exchange/responsible-ai-governance/eu-ai-act-nist-ai-rmf-and-iso-iec-42001-a-plain-english-comparison/>
- Maven Central publishing limits and Portal: <https://central.sonatype.org/publish/maven-central-publishing-limits/>; <https://community.sonatype.com/t/update-maven-central-publishing-limits-enforcement-moved-to-october-1/16475>; Sigstore intent: <https://www.sonatype.com/blog/maven-central-and-sigstore>
