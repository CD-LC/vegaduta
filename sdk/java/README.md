# VegaDūta Agent SDK

**Governance for AI agents in Java, as a dependency.** Tool risk classification, human
approval, budgets, idempotent effects, content guardrails, policy hooks, audit and telemetry,
wired around the tools you already have. Works with Spring AI out of the box, with plain Java
without any framework, and is built to be the layer that stays when the orchestration
framework underneath changes.

Lives in `sdk/java/` next to the generated TypeScript and Python REST clients (`sdk/typescript/`,
`sdk/python/`), which are a different thing: those call the VegaDūta platform API; this library
runs inside your own application. Apache-2.0. Java 17+. Group `io.github.cd-lc`. Design record:
[ADR-0013](../../docs/architecture/ADR-0013-embeddable-agent-sdk.md).

> Status: `0.1.0`, released as the GitHub Release `sdk-v0.1.0` (jars, sources, javadoc, CycloneDX
> SBOMs, `SHA256SUMS` and SLSA provenance), not yet on Maven Central. Until it is, take the jars from
> that release or build them with `mvn -f sdk/java/pom.xml install` and depend on `0.1.0`. The public
> API is annotated and checked by japicmp from the first release on; anything marked `@Experimental`
> may move.

## What's new

All of it is `@Experimental` and switched on from `Governance.Builder`; nothing changes for an
application that does not call the new methods.

- **`vegaduta-agent-scanner`**: a risk map of the `@Tool`/`@Governed` methods in a jar, with a
  readiness score and a CI gate, before you integrate anything ([below](#scan-before-you-integrate)).
- **Evidence you can hand to an auditor**: hash-chained, optionally signed audit trail
  (`tamperEvidentAudit`), `EvidenceBundle` with chain verification, EU AI Act, OWASP ASI
  coverage and underwriting exporters.
- **Kill switch** (`haltSwitch`) with a drill that proves it works, **tiered autonomy**
  L1-L4 (`autonomy`) demotable at run time, **delegated mandates** that can only narrow
  (`mandates`), **tool supply-chain pinning** (`provenance`).
- **Policy replay and shadow mode** (`shadow`, `PolicyReplay`): see what a stricter policy
  would have done to last week's calls before enforcing it.
- **Cost attribution and showback** (`costModel`), **interrupted-effect reconciliation**
  (`reconciler`, `unknownEffectPolicy`), **governed long-term memory** (`memory`).
- **Approvals in Slack and Teams** (`approvalNotifier`) and **signed decision receipts**
  (`receipts`).
- **Record and replay tool traces** in the test kit (`io.vegaduta.agent.testkit.trace`).
- Four reviewer-facing documents: [compliance mapping](../../docs/sdk/compliance-mapping.md),
  [security review pack](../../docs/sdk/security-review-pack.md),
  [adoption playbook](../../docs/sdk/adoption-playbook.md) and
  [positioning](../../docs/sdk/positioning.md).

## Why this exists

Spring AI, LangChain4j, Embabel, Google ADK and Koog give Java teams model calls, tool
loops and orchestration. None of them gives you what a security review, a compliance
officer or an incident post-mortem asks for:

- which tools can this agent reach, and what can each one do to the world?
- who approved that refund, and what exactly was approved?
- how much did this run spend, and what stopped it?
- if the turn is retried, does the payment happen twice?
- did a web page the agent read try to re-program it?
- one audit record across model calls, tool calls and approvals, tagged with the OWASP
  agentic risk it relates to.

The SDK answers those questions with one dependency and one annotation, keeps your code
on whatever framework you chose, and does not require a server, a UI or a database to
start (though it supports all three).

## Scan before you integrate

You do not have to change a line of code to see where you stand. The scanner reads the
`@Tool` methods (Spring AI, LangChain4j, or any annotation named `Tool`) and `@Governed`
methods already in your jar and prints a risk map: every tool the model can call, its
classification or `UNCLASSIFIED`, approval coverage, lethal-trifecta combinations, OWASP
Agentic Top 10 exposure, and a readiness score with the deductions that explain it.

```bash
java -cp build/libs/app.jar:vegaduta-agent-scanner-<version>-cli.jar \
    io.vegaduta.agent.scanner.Main --package com.acme.agent --format md
java -jar vegaduta-agent-scanner-<version>-cli.jar --jar build/libs/app.jar --format json
java -jar vegaduta-agent-scanner-<version>-cli.jar --jar app.jar --fail-under 80   # exit 2 below 80
```

The `-cli` jar (a release asset from the first release after 0.1.0, and the `cli` classifier on Maven
Central) bundles the SDK's API and core, SLF4J's API and RE2/J, so `java -jar` runs it with nothing
else on the class path. The plain `vegaduta-agent-scanner-<version>.jar` is the library for
`new RiskMapScanner(policy).scan(classes)` and cannot be run with `java -jar`. SLF4J prints a "no
providers" notice on stderr; it is harmless, and `-Dslf4j.internal.verbosity=ERROR` before `-jar`
silences it.

**0.1.0 shipped only the plain jar.** Run it with every jar it needs on the class path (the `-cp` form,
not `-jar`; the scanner, core and api jars are release assets, `slf4j-api` and `re2j` come from Maven
Central):

```bash
java -cp app.jar:vegaduta-agent-scanner-0.1.0.jar:vegaduta-agent-core-0.1.0.jar:vegaduta-agent-api-0.1.0.jar:slf4j-api-2.0.17.jar:re2j-1.8.jar \
    io.vegaduta.agent.scanner.Main --package com.acme.agent --fail-under 80
```

Pass `--approval-from MEDIUM` or `--mode STRICT` to score against the policy you intend to
run with. From Java: `new RiskMapScanner(policy).scan(classes).toMarkdown()`.

## 60-second start (Spring Boot)

```xml
<dependency>
  <groupId>io.github.cd-lc</groupId>
  <artifactId>vegaduta-agent-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

```java
@Component
class Payments {

  @Tool(description = "Refund an order")
  @Governed(risk = RiskLabel.HIGH, requiresApproval = true, idempotent = true, risks = AsiRisk.ASI02)
  String refund(String orderId, long cents) { ... }

  @Tool(description = "Look up an order")
  @Governed(risk = RiskLabel.LOW, sideEffecting = false, readsPrivateData = true, idempotent = true)
  String orderStatus(String orderId) { ... }
}
```

That is all. The starter now:

1. registers both classifications by scanning `@Governed` on `@Tool` methods;
2. wraps every `ToolCallback` and `ToolCallbackProvider` bean, and every tool passed to a
   `ChatClient` call, in the governance pipeline;
3. adds a `GovernanceAdvisor` to every auto-configured `ChatClient.Builder`;
4. when the model asks for `refund`, records an approval request instead of running it and
   tells the model to say so to the user; `orderStatus` runs, and a repeat of the same call
   in the same run is served from the effect journal;
5. reserves budget before each call (default: 50 tool calls per run), redacts secrets and
   instruction-like text coming back from tools, blocks secrets from being sent to tools;
6. writes one `key=value` audit line per action to the logger `io.vegaduta.agent.audit`,
   records Micrometer timers and OpenTelemetry `execute_tool` spans when those are present.

Decide the approval from your own UI, a chat command, a ticket, or the optional endpoint:

```java
@Autowired ApprovalGate approvals;

approvals.pending(tenantId, 50);          // what is waiting
approvals.approve(id, "reviewer@corp");   // runs the refund, journals the result
approvals.reject(id, "reviewer@corp", "customer already refunded");
```

```properties
vegaduta.agent.approval.endpoint.enabled=true   # GET/POST /vegaduta/approvals (secure it yourself)
```

## 60-second start (plain Java, any framework)

```java
Governance governance = Governance.builder()
    .policy(GovernancePolicy.builder().mode(GovernanceMode.STRICT).build())
    .classification(ToolClassification.builder("refund").risk(RiskLabel.HIGH).build())
    .classification(ToolClassification.readOnly("order_status"))
    .build();

Tool refund = governance.govern(myRefundTool);   // io.vegaduta.agent.api.tool.Tool
ToolResult result = ((GovernedTool) refund).invoke(ToolCall.of("refund", "{\"orderId\":\"42\"}"), context);
switch (result.status()) {
  case OK, REPLAYED -> ...;                 // result.output()
  case PENDING_APPROVAL -> ...;             // result.approvalId()
  case DENIED, BUDGET_EXCEEDED, ERROR -> ...; // result.output() explains it to the model
}
```

`vegaduta-agent-api` and `vegaduta-agent-core` depend on the JDK, SLF4J and RE2/J only. No
JSON library, no framework, no reflection on your types.

## 60-second: the governance you can prove

The same `Governance`, plus a kill switch, tiered autonomy, a shadow policy and a
tamper-evident audit trail, and at the end a bundle an auditor can verify:

```java
InMemoryAuditSink store = new InMemoryAuditSink();          // or the JDBC sink
HmacSha256Signer signer = new HmacSha256Signer(secretKeyBytes);
HaltSwitch halt = new HaltSwitch(store);
AutonomyRegistry autonomy = new AutonomyRegistry(AutonomyLevel.L4_ACT_AUTONOMOUS, store);

Governance governance = Governance.builder()
    .policy(GovernancePolicy.builder().mode(GovernanceMode.WARN).build())
    .classification(ToolClassification.builder("refund").risk(RiskLabel.HIGH).build())
    .haltSwitch(halt)                                        // halt.engage(...) denies every call
    .autonomy(autonomy)                                      // autonomy.demote(...) obeyed on the next call
    .shadow(GovernancePolicy.builder().mode(GovernanceMode.STRICT).build())  // audited, not enforced
    .tamperEvidentAudit(signer)                              // chain.seq / chain.prev / chain.hash / chain.sig
    .auditSink(store)
    .build();

// ... run the agent; at any time:
autonomy.demote("support-bot", AutonomyLevel.L2_RECOMMEND, "ops@corp", "incident 4821");
halt.engage(HaltScope.agent("support-bot"), "ops@corp", "incident 4821");

// Evidence: verify the chain, then export.
EvidenceBundle bundle = EvidenceBundle.builder()
    .events(store.events())
    .classification(ToolClassification.builder("refund").risk(RiskLabel.HIGH).build())
    .verifier(signer.verifier())
    .build();
ChainVerification chain = bundle.chainVerification();       // ok(), signed(), firstBrokenSeq()
String euAiAct = new EuAiActEvidenceExporter(bundle).toMarkdown();
String owasp = new OwaspAsiCoverageReport(bundle).toMarkdown();
String shadow = ShadowReport.from(store.events()).toMarkdown();  // what STRICT would have changed
```

A restarted process continues the chain with `tamperEvidentAudit(signer, lastSeq, lastHash)`
read back from the durable sink. Offline, `PolicyReplay.of(candidate, classifications)` runs a
candidate policy over stored events without a running agent.

## What is in the box

| Artifact | What it gives you | Depends on |
|---|---|---|
| `vegaduta-agent-api` | The contracts: `Tool`, `RunContext`, `ToolClassification`, `ToolDispatchGate`, `ApprovalGate`, `Budget`, `EffectJournal`, `ContentGuardrail`, `Hook`, `AuditEvent`, `ToolCallObserver`, `AsiRisk`, `@Governed`. JPMS module. | JDK 17, JSpecify |
| `vegaduta-agent-core` | Reference implementations and the `Governance` entry point: policy gate, lethal-trifecta detector, approval gate with four-eyes rule, atomic reserve-then-commit budget, effect journal, RE2/J pattern guardrails with built-in secret and prompt-injection rules, hook engine with signed webhooks, logging audit sink, Micrometer and OpenTelemetry observers. Experimental packages: `evidence` (hash-chained audit, signers, `EvidenceBundle`, EU AI Act / OWASP ASI / underwriting exporters), `autonomy` (L1-L4 registry and gate), `replay` (`PolicyReplay`, `ShadowGate`, `ShadowReport`, `PolicyDiff`), `mandate`, `halt` (kill switch, drill, `HaltStore` SPI), `provenance` (manifest pinning, drift), `cost` (model, ledger, showback), `memory` (governed long-term memory, poison probe), `journal` reconciliation (`ReconcilingEffectJournal`, `EffectReconciler`), approval notifier (Slack/Teams) and signed receipts. | api, SLF4J, RE2/J (Micrometer and OTel optional) |
| `vegaduta-agent-spring-ai` | `GovernedToolCallback`, `ToolGovernor`, `@Governed` scanning of `@Tool` methods, `GovernanceAdvisor` for `ChatClient`, `RunContextHolder`. | core, Spring AI 2.0.x |
| `vegaduta-agent-spring-boot-starter` | Auto-configuration driven by `vegaduta.agent.*`, bean post-processor that governs tool beans, optional approvals endpoint, JDBC wiring, telemetry wiring. | spring-ai adapter, Spring Boot 4.x |
| `vegaduta-agent-jdbc` | Durable `ApprovalStore`, `EffectJournal` (with interrupted-call detection) and append-only `AuditSink` on plain JDBC; DDL for PostgreSQL and H2. | core, JDBC |
| `vegaduta-agent-testkit` | `GovernanceTestKit`, `FakeTool`, AssertJ assertions: `assertThat(kit).hasPendingApprovalFor("refund").coversRisks(ASI02)`; `trace` package (`TraceRecorder`, `ReplayTool`, `TraceAssert`, `TraceDiff`) to record a run's tool calls and replay them in a test. | core, JUnit 5, AssertJ |
| `vegaduta-agent-scanner` | Zero-integration risk map: `RiskMapScanner` and a CLI (`Main`) that list every `@Tool`/`@Governed` method, flag unclassified tools, compute approval coverage, detect the lethal trifecta, map OWASP ASI exposure and print a 0-100 readiness score (`--fail-under` gates CI). | api, core, SLF4J |
| `vegaduta-agent-bom` | Aligns the versions above. | |

## Governance add-ons (experimental)

Every add-on below plugs into an existing extension point (`ToolDispatchGate`, `AuditSink`,
`Hook`, `EffectJournal`, `BudgetProvider`) and has a one-call switch on `Governance.Builder`.
Gates compose in a fixed order (halt, mandate, autonomy, provenance, then the policy gate or
your `.gate(...)`): the first DENY wins, then any REQUIRE_APPROVAL, so a permissive policy can
never re-admit a halted or demoted agent.

| Need | Switch | Types |
|---|---|---|
| Kill switch, with a drill that proves it works | `.haltSwitch(halt)` | `HaltSwitch`, `HaltScope`, `HaltGate`, `HaltDrill` |
| Delegated authority that can only narrow | `.mandates(require)` | `Mandate`, `Mandates.attach(ctx, m)`, `MandateGate` |
| Tiered autonomy L1-L4, demotable at run time | `.autonomy(registry)` | `AutonomyLevel`, `AutonomyRegistry`, `AutonomyGate`, `AutonomyPolicyReport` |
| Tool supply-chain pinning (OWASP ASI04) | `.provenance(store, key, mode, manifest)` | `ToolManifest`, `FileManifestPinStore` (`vegaduta-tools.lock`), `ProvenanceGate`, `DriftReport` |
| Try a stricter policy without enforcing it | `.shadow(candidate)`; offline `PolicyReplay` | `ShadowGate`, `ShadowReport`, `PolicyReplay`, `PolicyDiff` |
| Cost attribution and showback | `.costModel(model)` | `CostModel`, `CostLedger`, `CostAttribution.tag`, `ShowbackReport` |
| Never retry an interrupted side effect blindly | `.reconciler(r).unknownEffectPolicy(HOLD)` | `ReconcilingEffectJournal`, `EffectReconciler`, `InterruptedCallTracker` |
| Tamper-evident audit and compliance exports | `.tamperEvidentAudit(signer)` | `HashChainedAuditSink`, `EvidenceBundle`, `EuAiActEvidenceExporter`, `OwaspAsiCoverageReport`, `UnderwritingReport` |
| Approve in Slack/Teams, signed receipts | `.approvalNotifier(...)`, `.receipts(signer)` | `ApprovalNotifier`, `ChatApprovalHandler`, `ReceiptAuditSink`, `ApprovalReceipt` |
| Governed long-term memory | `.memory(Set.of("remember"))` | `GovernedMemoryStore`, `Provenance`, `PoisonProbe` |
| Record and replay tool traces in tests | `GovernanceTestKit...customize(b -> b.observer(recorder))` | `TraceRecorder`, `ReplayTool`, `TraceAssert`, `TraceDiff`, `TraceKit` |

## Concepts

**RunContext.** Who runs what for whom: tenant, principal, agent, session, run id,
correlation id, attributes. Only the run id is mandatory. Every gate, approval, budget,
journal entry, audit event and span is scoped by it. In Spring AI it travels in the tool
context; the advisor puts it there. Pass your own with
`chatClient.prompt(...).advisors(a -> a.param(GovernanceAdvisor.CONTEXT_KEY, ctx))`, bind it
with `RunContextHolder.bind(ctx)`, or declare a `RunContextResolver` bean that derives it from
your security context.

**Classification.** What a tool can do: `risk` (LOW, MEDIUM, HIGH, CRITICAL),
`sideEffecting`, `readsPrivateData`, `consumesUntrustedInput`, `exfiltrationSink`,
`idempotent`, `requiresApproval`, and the OWASP ASI items it is exposed to. Comes from
`@Governed`, from `Governance.builder().classification(...)`, from any
`ToolClassificationSource` bean (a database, a registry), or from the policy default.

**Mode.** `WARN` (default): unclassified tools run with the default classification and a
`WARNING` audit event, so adding the dependency never breaks an application. `STRICT`:
unclassified tools are denied and the lethal-trifecta rule is enforced. `OFF`: bypass.

**Gate.** Before a call: deny list, allow list, unclassified (strict), explicit
`requiresApproval`, or risk at or above `approval.required-from` (default HIGH, side-effecting
tools only). Replace it with your own `ToolDispatchGate` bean.

**Lethal trifecta.** An agent that can read private data, reads untrusted content and can send
data out is one prompt injection away from exfiltration. `Governance.governAll(tools)` (which
the advisor calls for every request) detects the combination; in `STRICT` mode every sink in
the set is forced to require approval, in `WARN` mode it is audited.

**Approval.** A request records the tool, the raw arguments, the run context and the
requester; it expires after `approval.ttl`. The four-eyes rule stops the requester deciding
their own request (`ApprovalDecisionAuthorizer` bean to replace it with role checks). Approving
executes the tool through the registered executor, journals the result and audits it.

**Budget.** Reserve before the call, commit after, in one critical section, so parallel tool
calls cannot jointly overspend. Caps per run on tool calls, tokens and dollars; zero means
unlimited. Replace the `BudgetProvider` to budget per tenant or per day.

**Effect journal.** Keyed by run id, tool and arguments. A replay-safe tool (idempotent or
read-only) called twice with the same arguments in one run returns the first result without
running again. Approved calls are journaled too, so a retried turn never re-executes or
re-asks. The JDBC journal also marks calls that started and never finished.

**Guardrails.** Content checks at `INPUT`, `OUTPUT`, `TOOL_ARGUMENTS` and `TOOL_OUTPUT`.
Built-in: secrets (AWS keys, private keys, GitHub and Slack tokens, bearer headers) redacted
in output and blocked from leaving through tool arguments; instruction-override, role
reassignment and system-prompt-probe phrases redacted from tool output. All patterns are
RE2/J (linear time) so hostile input cannot stall the agent. Add `ContentGuardrail` beans
(for example a call to a moderation model) and they join the chain.

**Hooks.** `BEFORE_TOOL_CALL`, `AFTER_TOOL_CALL`, `ON_TOOL_DENIED`, `ON_APPROVAL_REQUESTED`,
`ON_APPROVAL_DECIDED`, `ON_BUDGET_EXCEEDED`, `ON_GUARDRAIL_VIOLATION`, `BEFORE_MODEL_CALL`,
`AFTER_MODEL_CALL`. A hook can continue, block, or replace the payload. `WebhookHook` posts
signed JSON to an external policy engine and honours its answer, with fail-open or fail-closed
per registration; configure it with `vegaduta.agent.webhooks[0].url=...`.

**Audit.** One `AuditEvent` per action: `tool.call`, `model.call`, `approval.requested`,
`approval.decided`, `agent.toolset`. The first eight fields match VegaDūta's published audit
contract; every event carries the run context and the OWASP ASI tags. Sinks: logger (default),
JDBC, in-memory, your own `AuditSink` bean. Sinks never throw into the pipeline.

**Observers.** `MicrometerToolCallObserver` (`vegaduta.agent.tool.calls` timer,
`vegaduta.agent.tool.errors` counter) and `OpenTelemetryToolCallObserver` (`execute_tool
<name>` spans with `gen_ai.operation.name`, `gen_ai.tool.name`, `gen_ai.tool.call.id`,
`gen_ai.conversation.id`, `gen_ai.agent.id`, plus `vegaduta.*` outcome attributes).

## Configuration reference (`vegaduta.agent.*`)

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch |
| `mode` | `warn` | `strict`, `warn`, `off` |
| `default-risk` | `medium` | Classification for unclassified tools in warn mode |
| `argument-preview-chars` | `500` | Arguments shown in approval summaries |
| `approval.required-from` | `high` | Side-effecting tools at or above this risk need a human |
| `approval.ttl` | `24h` | How long a request stays decidable |
| `approval.four-eyes` | `true` | Requester may not decide their own request |
| `approval.endpoint.enabled` | `false` | Expose `GET/POST /vegaduta/approvals` |
| `approval.endpoint.path` | `/vegaduta/approvals` | Base path |
| `budget.max-tool-calls-per-run` | `50` | 0 = unlimited |
| `budget.max-tokens-per-run` | `0` | 0 = unlimited (recorded on `model.call` audit events) |
| `budget.max-usd-per-run` | `0` | 0 = unlimited |
| `budget.estimated-usd-per-tool-call` | `0` | Reserved per call when the cost is unknown |
| `journal.ttl` | `1h` | Retention of replayable results |
| `guardrails.built-in` | `true` | Register the built-in rules |
| `audit.log` | `true` | Audit to the `io.vegaduta.agent.audit` logger |
| `telemetry.micrometer` | `true` | Metrics when a `MeterRegistry` exists |
| `telemetry.opentelemetry` | `true` | Spans when an `OpenTelemetry` bean exists |
| `store.type` | `memory` | `jdbc` needs `vegaduta-agent-jdbc` and a `DataSource` |
| `store.initialize-schema` | `false` | Create the tables at startup (development only) |
| `store.jdbc-audit` | `true` | Also write audit events to `vegaduta_audit` |
| `webhooks[n].name/url/secret/points/tool-pattern/fail-mode/timeout` | | HTTP policy hooks |

Every collaborator is a `@ConditionalOnMissingBean`: declare your own `ApprovalStore`,
`ApprovalDecisionAuthorizer`, `BudgetProvider`, `EffectJournal`, `ToolDispatchGate`,
`GuardrailChain`, `AuditSink`, `ToolCallObserver`, `HookRegistration`,
`ToolClassificationSource` or `RunContextResolver` bean and the starter uses it.

## Durable stores

```properties
vegaduta.agent.store.type=jdbc
vegaduta.agent.store.initialize-schema=true   # development; copy the DDL into Flyway/Liquibase for production
```

DDL: `io/vegaduta/agent/jdbc/schema-postgresql.sql`, `schema-h2.sql`. Tables:
`vegaduta_approval`, `vegaduta_effect`, `vegaduta_audit`. The audit table is append-only;
retention is yours (the EU AI Act asks high-risk deployers for at least six months).

## Testing

```java
GovernanceTestKit kit = GovernanceTestKit.strict()
    .classification(ToolClassification.builder("refund").risk(RiskLabel.HIGH).build())
    .build();

assertThat(kit.invoke(FakeTool.echo("refund"), "{\"order\":1}", ctx)).isPendingApproval();
kit.approveAll("reviewer");
assertThat(kit).hasSucceeded("refund").coversRisks(AsiRisk.ASI02, AsiRisk.ASI09).hasNoDenials();
```

## Standards and compliance mapping

| Control in the SDK | OWASP Top 10 for Agentic Applications 2026 | Other |
|---|---|---|
| Classification + gate (deny unclassified, allow/deny lists, risk threshold) | ASI02 Tool Misuse, ASI05 Unexpected Code Execution, ASI10 Rogue Agents | ACS policy enforcement point |
| Lethal-trifecta detector | ASI01 Goal Hijack, ASI06 Memory/Context Poisoning | |
| Human approval with four-eyes, expiry, replay envelope | ASI09 Human-Agent Trust | EU AI Act human oversight evidence |
| Atomic budgets | ASI08 Cascading Failures | |
| Effect journal | ASI08 | |
| Secret guardrails (redact / block egress) | ASI02, ASI03 Identity and Privilege Abuse | |
| Prompt-injection guardrails on tool output | ASI01, ASI06 | |
| Hooks and signed webhooks | ASI07 Insecure Inter-Agent Communication | ACS "guardian" hook model |
| Audit events tagged with ASI ids and run context | all | EU AI Act Article 12/26 logging, ISO/IEC 42001 evidence |
| OpenTelemetry `gen_ai.*` spans | | OTel GenAI semantic conventions (Development status; attribute names pinned here, kept behind our enums) |

MCP: the 2026-07-28 revision moves server-initiated requests to the Multi Round-Trip
pattern (`resultType: "input_required"`). A `PENDING_APPROVAL` result maps onto that pattern
one to one; the forthcoming `vegaduta-agent-mcp` module hardens MCP tool arguments and
outputs and surfaces approvals as `input_required`.

For reviewers: the [compliance mapping](../../docs/sdk/compliance-mapping.md) walks each control to its class and audit event, and the [security review pack](../../docs/sdk/security-review-pack.md) answers the questionnaire. Adopters start with the [adoption playbook](../../docs/sdk/adoption-playbook.md).

## Built to last

Dependencies that stay for a decade share a few properties. The SDK is built to them:

- **The contract is the product.** `vegaduta-agent-api` is interfaces, records and enums on
  the JDK only. Frameworks come and go underneath (Spring AI Agents ships in November 2026,
  LangChain4j's agentic module is in beta, Jakarta Agentic AI is a draft); each is an adapter,
  never a dependency of the core.
- **Nothing to migrate when a JSON or HTTP library changes.** Core carries no JSON library;
  the webhook uses the JDK HTTP client.
- **Semantic versioning, enforced.** japicmp breaks the build on an incompatible change of
  `api` or `core` from 1.0 on. `@Experimental` is the only escape hatch and it is visible.
- **Java 17 bytecode, JPMS-ready, AOT-friendly.** No reflection on user types outside the
  adapters, no runtime class generation, so GraalVM native image and Spring AOT keep working.
- **Standards, not inventions.** Risk ids are OWASP's, span attributes are OpenTelemetry's,
  hook points track the Agent Control Standard, the audit shape is a published contract,
  approvals map onto MCP's `input_required`. When a standard moves, the mapping moves; your
  code does not.
- **Every store is an SPI.** Memory today, JDBC today, your Kafka topic or event store
  tomorrow, with the same `Governance`.
- **Reproducible, signed, attested builds.** Reproducible jars, CycloneDX SBOM per artifact,
  GPG signatures and SLSA provenance on release, Maven Central through the Central Portal.
- **Dogfooded.** The VegaDūta platform consumes these artifacts (ADR-0013 phase 1), so the
  library is exercised by a multi-tenant production system, not only by its tests.

## Compatibility

| | Supported |
|---|---|
| Java | 17 and later (built and tested on 17) |
| Spring AI (adapter, starter) | 2.0.x |
| Spring Boot (starter) | 4.x |
| Databases (jdbc) | PostgreSQL, H2 (DDL provided; others run the equivalent DDL) |
| No framework | `api` + `core` on any JVM runtime |

## Known limitations (0.1)

- Streamed `ChatClient` replies are not inspected by output guardrails (inputs and tools are).
- Token budgets are recorded on `model.call` audit events but not yet enforced across model
  calls; tool-call and dollar caps are enforced.
- Approvals endpoint has no authentication of its own.
- Not yet shipped: LangChain4j adapter, MCP hardening module, Agent Skills supply-chain
  scanner, compliance pack (Article 50 disclosure helper, AgBOM generator), Maven plugin that
  fails CI on unclassified tools. See ADR-0013 phases 2 and 3.

## Build and release

```bash
mvn -f sdk/java/pom.xml verify            # build, test, javadoc, sources, SBOM
mvn -f sdk/java/pom.xml spotless:apply    # google-java-format (needs a JDK 21 javac; see CI)
mvn -f sdk/java/pom.xml -Prelease deploy  # sign and upload to the Central Portal (maintainers)
```

Release prerequisites: a verified `io.github.cd-lc` namespace on the Central Portal, a GPG key,
`CENTRAL_USERNAME` / `CENTRAL_PASSWORD` user token, a tag `sdk-v<version>`, and
`japicmp.skip=false` with the previous release as baseline.

## Licence

Apache-2.0, like the rest of VegaDūta.
