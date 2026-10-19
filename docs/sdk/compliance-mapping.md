# VegaDūta Agent SDK: compliance mapping

A control-by-control mapping of the mechanisms in the Java Agent SDK (`sdk/java`, group
`io.vegaduta`) to the frameworks a security review, a model-risk committee or an auditor is
likely to ask about. It is written for the team that has been blocked by a CISO or an internal
audit function and needs to answer "which control covers which obligation, and what evidence
does it leave behind?". The companion document is the
[security review pack](security-review-pack.md) (threat model, questionnaire, test checklist).

Read this first:

- **The SDK is `0.1.0-SNAPSHOT`.** It has not been released to Maven Central and has had no
  third-party security assessment or penetration test. Everything below is a description of
  what the code does, not an attestation. Verify it against the source in your own review.
- **Mapping is not certification.** Nothing here makes an application "EU AI Act compliant",
  "ISO/IEC 42001 certified" or "SOC 2 ready". The SDK produces controls and evidence that a
  deployer can point at while meeting their own obligations. The obligations stay with the
  deployer (and, under the AI Act, the provider).
- **Status column.** `Shipped` means present in the reactor today (`agent-api`, `agent-core`,
  `agent-jdbc`, `agent-testkit`, `agent-spring-ai`, `agent-spring-boot-starter`). `This change`
  means the mechanism is being added by the parallel work this document ships with; its package
  is named so a reviewer can find it, but treat it as unreleased until the module tests pass
  in CI and the names stop carrying `@Experimental`.

## 1. Mechanism inventory

| Mechanism | Where | Status | What it does | Evidence it produces |
|---|---|---|---|---|
| `ToolClassification` / `@Governed` | `api.risk`, `api.annotation` | Shipped | Declares per tool: `risk` (LOW..CRITICAL), `sideEffecting`, `readsPrivateData`, `consumesUntrustedInput`, `exfiltrationSink`, `idempotent`, `requiresApproval`, OWASP `AsiRisk` tags | `agent.toolset` audit event listing offered tools; `risk` and `risks` attributes on every `tool.call` event |
| `PolicyToolDispatchGate` | `core.gate` | Shipped | Deny list, allow list, deny-unclassified (STRICT), `requiresApproval`, risk-at-or-above threshold for side-effecting tools | `tool.call` event with outcome `DENIED` or `PENDING_APPROVAL` and the gate's reason in `detail` |
| `DefaultApprovalGate` + `FourEyesAuthorizer` | `core.approval` | Shipped | Records a request (tool, raw arguments, run context, requester, expiry = `approval.ttl`), refuses decision by the requester, executes the tool on approval and journals the result | `approval.requested`, `approval.decided` events; `vegaduta_approval` row with `requested_by`, `decided_by`, `decision_reason`, `result` |
| `AtomicBudget` | `core.budget` | Shipped | Reserve-then-commit in one critical section; caps per run on tool calls and dollars (tokens recorded, not enforced) | `tool.call` outcome `BUDGET_EXCEEDED`; `ON_BUDGET_EXCEEDED` hook event |
| `EffectJournal` (`InMemoryEffectJournal`, `JdbcEffectJournal`) | `core.journal`, `jdbc` | Shipped | Keyed by run + tool + arguments; replay-safe tools return the first result; `begin`/`record` marks calls that started and never finished | `tool.call` outcome `REPLAYED`; `vegaduta_effect` rows with `status` |
| `PatternGuardrail` / `BuiltInRules` / `GuardrailChain` | `core.guardrail` | Shipped | RE2/J (linear time) rules: secrets redacted in output and blocked from leaving in tool arguments; instruction-override, role-reassignment and system-prompt-probe phrases redacted in tool output | `tool.call` outcome `DENIED` (blocked) or `WARNING` (redacted) naming the rule ids; `ON_GUARDRAIL_VIOLATION` hook event |
| `LethalTrifectaDetector` | `core.classification` | Shipped | Flags a toolset that can read private data, consume untrusted input and send data out; in STRICT every sink is forced to approval, in WARN it is audited | `agent.toolset` event with the three lists |
| `HookEngine` / `WebhookHook` | `core.hook` | Shipped | Nine hook points; a hook can continue, block or replace; `WebhookHook` posts JSON signed with HMAC-SHA256 (`X-Vegaduta-Signature`), fail-open or fail-closed per registration | Hook block reasons land in the `tool.call` `detail`; the remote policy engine keeps its own log |
| `AuditEvent` / `AuditSink` (`Logging`, `InMemory`, `Composite`, `JdbcAuditSink`) | `api.audit`, `core.audit`, `jdbc` | Shipped | One event per action with the eight fields of VegaDūta's published audit contract, the full `RunContext` and ASI tags; sinks never throw into the pipeline | Log lines on `io.vegaduta.agent.audit`; append-only `vegaduta_audit` table |
| `MicrometerToolCallObserver`, `OpenTelemetryToolCallObserver` | `core.observe` | Shipped (optional deps) | Timers, error counters, `execute_tool` spans with `gen_ai.*` attributes | Metrics and traces in your existing stack |
| JDBC durable stores | `jdbc` | Shipped | PostgreSQL and H2 DDL for `vegaduta_approval`, `vegaduta_effect`, `vegaduta_audit` | Rows survive restarts; retention is the deployer's |
| `HashChainedAuditSink` + evidence exporters | `core.evidence` | This change | Each audit event carries the hash of its predecessor; exporters produce a verifiable bundle for an auditor | Chain head hash; exported evidence file whose breaks are detectable |
| `AutonomyLevel` / `AutonomyGate` | `api.autonomy`, `core.autonomy` | This change | A declared ceiling on how much the agent may do without a human, enforced as a gate | Gate denials citing the level |
| `PolicyReplay` / `ShadowGate` | `core.replay` | This change | Re-run recorded calls against a candidate policy; a shadow gate records what it *would* have decided without enforcing | Diff reports between current and candidate policy |
| `Mandate` / `MandateGate` | `api.mandate`, `core.mandate` | This change | A scoped, time-bound authorisation ("this agent may do X for principal Y until T"); calls outside the mandate are denied | Denials naming the mandate; mandate id on audit events |
| `HaltSwitch` / `HaltDrill` | `core.halt` | This change | A stop control that denies every governed call once tripped; a drill exercises it and records that it worked | Halt state changes and drill results in the audit stream |
| `ToolManifest` / `ProvenanceGate` | `core.provenance` | This change | A manifest of the tools the application is supposed to offer, with hashes; the gate denies tools not in the manifest | Manifest file; denials for unlisted or altered tools |
| `CostLedger` / `ShowbackReport` | `core.cost` | This change | Accumulates spend per run/tenant/agent; produces a showback report | Cost reports |
| `GovernedMemoryStore` | `core.memory` | This change | Agent memory writes and reads pass through guardrails and are audited | Memory-related audit events |
| `ReconcilingEffectJournal` | `core.journal` | This change | Finds effects that began and never recorded and reports them for operator reconciliation | Reconciliation report |
| `ApprovalReceipt` / `ApprovalNotifier` | `core.approval` | This change | A receipt for each decision (who, when, what was approved, under which summary) and a notifier that pushes pending requests to reviewers | Receipts |
| Trace record/replay | `testkit.trace` | This change | Record a governed run and replay it in a test | Recorded traces usable as regression fixtures |
| `agent-scanner` | new module | This change | Static scan of the application for unclassified `@Tool` methods and risky combinations before deployment | Scan report |

## 2. OWASP Top 10 for Agentic Applications 2026

The risk ids are OWASP's (`AsiRisk` enum) and every audit event carries the ones the tool was
classified against, so coverage is visible per call, not only on paper.

| Risk | SDK mechanism | Evidence | Honest gap |
|---|---|---|---|
| ASI01 Agent Goal Hijack | `BuiltInRules.promptInjection()` redacts instruction-override, role-reassignment and system-prompt-probe phrases in tool output; `LethalTrifectaDetector`; `ToolManifest`/`ProvenanceGate` (this change) stops injected "new tools" | `WARNING` events `output redacted: injection.*`; `agent.toolset` trifecta report | Pattern matching catches known phrasings only. A paraphrased or multilingual injection passes. Treat the rules as a tripwire, not a filter; add a model-based `ContentGuardrail` for real coverage. Streamed model replies are not inspected in 0.1. |
| ASI02 Tool Misuse and Exploitation | `ToolClassification` + `PolicyToolDispatchGate` (risk threshold, deny list, deny unclassified in STRICT); argument guardrails; `MandateGate` (this change) | `DENIED` / `PENDING_APPROVAL` events with gate reason | WARN mode (the default) runs unclassified tools with the default classification. A reviewer should require STRICT in production. |
| ASI03 Identity and Privilege Abuse | `RunContext` (tenant, principal, agent) scopes every gate, approval, budget and journal key; `FourEyesAuthorizer`; secrets egress rules; `Mandate` (this change) binds actions to a principal and a validity window | `actorId`, `principal`, `tenantId` on every event | The SDK trusts the `RunContext` the host hands it. If the host derives it from an unauthenticated source, every downstream control is scoped to a lie. The optional approvals endpoint has no authentication of its own. |
| ASI04 Agentic Supply Chain Vulnerabilities | `ToolManifest`/`ProvenanceGate` and `agent-scanner` (this change); reproducible jars, CycloneDX SBOM, GPG signatures and SLSA provenance on release | Manifest, scan report, SBOM | No release has been made yet, so no signed artifact exists to verify. The Agent Skills supply-chain scanner and MCP hardening module are ADR-0013 phase 2, not shipped. |
| ASI05 Unexpected Code Execution | Classification of code-running tools as `CRITICAL`/`sideEffecting` and the gate's threshold; `AutonomyLevel` (this change) | Gate events | The SDK does not sandbox anything. It decides whether a call is made; what the tool then does is the tool's problem. Pair with an execution sandbox. |
| ASI06 Memory and Context Poisoning | Output guardrails on `TOOL_OUTPUT`; `GovernedMemoryStore` (this change) routes memory writes through the same chain and audits them | Redaction `WARNING`s; memory audit events | Only content passing through governed tools or the governed store is checked. Memory the framework keeps elsewhere is not. |
| ASI07 Insecure Inter-Agent Communication | `WebhookHook` signs outbound policy calls with HMAC-SHA256; hook `FailMode` per registration | Signature header on every webhook | Signing is one-way (SDK to policy engine) with a shared secret. The SDK does not verify inbound signatures from other agents; the A2A guard is phase 3. |
| ASI08 Cascading Failures | `AtomicBudget` (tool-call and dollar caps per run), `EffectJournal` (no double execution on retry), `HaltSwitch` (this change), `CostLedger` (this change) | `BUDGET_EXCEEDED`, `REPLAYED` events; halt state | Token budgets are recorded but not enforced across model calls in 0.1. Budgets are per run by default; per-tenant or per-day needs a custom `BudgetProvider`. |
| ASI09 Human-Agent Trust Exploitation | `DefaultApprovalGate` with an argument summary (`argumentPreviewChars`), four-eyes, TTL expiry; `ApprovalReceipt` (this change); `Explanations` tell the model to say so rather than pretend | `approval.*` events, `vegaduta_approval` rows, receipts | The summary is truncated at 500 characters by default; a reviewer who approves on the summary alone may not see the whole payload. The raw payload is stored and should be shown. |
| ASI10 Rogue Agents | STRICT mode denies anything unclassified; `HaltSwitch` + `HaltDrill`; `AutonomyGate`; `ShadowGate`/`PolicyReplay` lets you tighten policy and see the effect before enforcing (all this change except STRICT) | Halt and drill events; replay diffs | A halt switch only stops calls that go through `GovernedTool`. Direct calls that bypass the wrapper (possible in plain Java) are invisible. |

## 3. EU AI Act (high-risk obligations)

The AI Act's stand-alone high-risk obligations were deferred to 2027-12-02 by Regulation (EU)
2026/1744; Article 50 transparency has applied since 2026-08-02. The SDK is a component a
deployer or provider may use; it does not change who is the provider or deployer, and it does
not perform a conformity assessment.

| Article | Obligation (summary) | SDK mechanism | Evidence | Honest gap |
|---|---|---|---|---|
| Art. 9 Risk management | Identify, estimate and mitigate risks across the lifecycle | `ToolClassification` is an explicit per-tool risk register; `LethalTrifectaDetector` finds a known composite risk; `agent-scanner` (this change) catches unclassified tools before deployment; `PolicyReplay` tests a policy change against history | `agent.toolset` events; scan reports; replay diffs | The SDK classifies *tools*, not the AI system. Art. 9 wants a system-level process and documentation; the classifications are one input to it. |
| Art. 12 Record-keeping (logging) | Automatic logging of events over the system's lifetime; traceability; tamper-evidence in practice | `AuditEvent` per action with run scope and ASI tags; `JdbcAuditSink` into an append-only table; `HashChainedAuditSink` + evidence exporters (this change) make deletions and edits detectable | `vegaduta_audit` rows; chain head; exported bundle | Append-only is a convention: the DDL does not revoke `UPDATE`/`DELETE`, so database privileges must. Retention (at least six months for high-risk deployers, Art. 26(6)) is not implemented; the deployer sets it. The hash chain detects tampering; it does not prevent it, and without an external anchor (publishing the head hash) a complete rewrite is undetectable. |
| Art. 14 Human oversight, incl. stop/override | Humans can understand, monitor, intervene, override and stop the system | `DefaultApprovalGate` (intervene per action), `FourEyesAuthorizer`, `HaltSwitch` + `HaltDrill` (stop, with proof it works), `AutonomyLevel` (declared ceiling), `Explanations` returned to the model so the end user is told | Approval trail, receipts, halt events, drill records | Approval covers tool calls only; a model reply with no tool call is not gated. The approvals endpoint is unauthenticated out of the box. Oversight "competence" of the human is organisational, not technical. |
| Art. 15 Accuracy, robustness, cybersecurity | Resilience to errors and to attempts to alter use or performance | Secrets egress blocking; injection tripwires; RE2/J linear-time matching (no regex DoS); `AtomicBudget` against runaway loops; `EffectJournal` against retry storms; `ProvenanceGate` against tool substitution | Guardrail and budget events | No adversarial robustness testing of a model is done by the SDK. Pattern rules are bypassable (see ASI01). No formal penetration test has been performed on the SDK. |
| Art. 50 Transparency | Disclose AI interaction / generated content | Not in this reactor | | Planned as `vegaduta-agent-compliance` (ADR-0013 phase 3). Today the deployer adds the disclosure. |

## 4. ISO/IEC 42001 Annex A

ISO/IEC 42001 is a management-system standard. Certification is about the organisation's
processes; a library cannot be "42001 certified" and using one is not a shortcut. The table
shows which Annex A controls the SDK gives technical evidence for.

| Annex A area | Control theme | SDK mechanism | Evidence |
|---|---|---|---|
| A.5 Impact assessment | Assess impacts on individuals and society | `ToolClassification.readsPrivateData`, `exfiltrationSink` make data-impact properties explicit per tool | Classification source of truth (`@Governed` in code, or a `ToolClassificationSource`) |
| A.6 AI system life cycle | Risk-based design, verification and validation, deployment controls | STRICT mode; `agent-scanner` and `ToolManifest` at build/deploy time; `GovernanceTestKit` and trace record/replay in tests (this change for the latter) | Scan reports, manifests, test assertions (`coversRisks`, `hasNoDenials`) |
| A.6.2.8 / A.9 Human oversight and intervention | Human can intervene in and override the system | `DefaultApprovalGate`, `FourEyesAuthorizer`, `HaltSwitch`, `AutonomyLevel` | Approval and halt trail |
| A.6.2.6 Event logging | Record events to enable investigation | `AuditEvent`/`AuditSink`, `HashChainedAuditSink` | Audit table, chain |
| A.7 Data for AI systems | Data quality, provenance, handling | `GovernedMemoryStore` (this change), secrets redaction | Memory audit events |
| A.8 Information for interested parties | Transparency, reporting | `ShowbackReport`, evidence exporters (this change) | Reports |
| A.10 Third-party and customer relationships | Supplier controls | SBOM, provenance on release; `ProvenanceGate` for tools | SBOM per artifact (on release) |

Gap: Annex A controls about policy, roles, competence, awareness and management review have no
technical counterpart here and should not be claimed on the strength of the SDK.

## 5. NIST AI RMF

| Function | What the SDK contributes | Evidence | Gap |
|---|---|---|---|
| Govern | A declarative policy (`GovernancePolicy`: mode, thresholds, TTLs, budgets) that is code-reviewable; `AutonomyLevel` and `Mandate` state the intended scope in code | Policy in version control | Governance structures, accountability and culture are organisational |
| Map | Per-tool classification maps capabilities and OWASP risks; `LethalTrifectaDetector` maps a composite risk; `ToolManifest` inventories what is offered | `agent.toolset` events, manifest | System context, intended use and impacted parties are documented by the team, not the SDK |
| Measure | Micrometer metrics, OTel spans, `CostLedger`, `ShadowGate` (measure a candidate policy without enforcing), testkit assertions | Dashboards, replay diffs, test reports | No model-quality or bias measurement; the SDK measures agent behaviour around tools only |
| Manage | Gate, approval, budget, halt, journal, guardrails; `PolicyReplay` to validate a tightening before rollout | Audit stream | Incident response and risk acceptance are processes the team owns |

## 6. SOC 2 (CC6 logical access, CC7 system operations)

The SDK is a library in the customer's system; it is in scope for the customer's SOC 2, not for
one of its own. The rows below are the control statements an auditor typically wants mapped.

| Criterion | Typical control statement | SDK mechanism | Evidence | Gap |
|---|---|---|---|---|
| CC6.1 Logical access | Access to perform privileged actions is restricted and authorised | Gate threshold, allow/deny lists, `requiresApproval`, `MandateGate` | `DENIED`/`PENDING_APPROVAL` events | Authentication of the humans and of the `RunContext` is the host's |
| CC6.2 / CC6.3 Authorisation and segregation | Privileged actions require independent approval | `FourEyesAuthorizer`; `ApprovalDecisionAuthorizer` SPI for role checks | `decided_by != requested_by` on every approval row | Default authorizer checks identity only, not role |
| CC6.6 / CC6.7 Boundary protection, data in transit | Prevent unauthorised data leaving; protect transmissions | Secrets egress blocking in `TOOL_ARGUMENTS`; HMAC-signed webhooks | Guardrail events; signature header | Only known secret shapes are matched; webhooks are signed, not encrypted (use TLS) |
| CC7.1 Detection | Detect anomalous or unauthorised activity | Every denial, violation and budget breach is an audit event and a hook; observers feed alerting | Alerts from your metrics stack | No built-in anomaly detection |
| CC7.2 Monitoring | Monitor system components for anomalies | Micrometer/OTel observers; `CostLedger` | Dashboards | |
| CC7.3 / CC7.4 Incident evaluation and response | Evaluate and respond to security events | Audit trail reconstructs the chain (run id, call id, approval id); `HaltSwitch` as the response control; `ReconcilingEffectJournal` finds half-done effects | Audit query by `run_id`; halt events | Response runbooks are yours |
| CC7.5 Recovery | Recover from incidents | `EffectJournal` makes retries safe; `ReconcilingEffectJournal` lists what to reconcile | Journal rows | The SDK does not undo an effect; compensation is the tool owner's |
| CC8.1 Change management | Changes are authorised, tested, approved | `PolicyReplay`/`ShadowGate` for policy changes; `GovernanceTestKit` for tests; `agent-scanner` in CI | Replay diffs, CI output | |

## 7. US financial services guidance

### FINRA 2026 report on AI agents

FINRA's 2026 report flags, among other things, agents **acting beyond their intended scope** and
the difficulty of **reconstructing multi-step chains** when something goes wrong.

| Concern | SDK mechanism | Evidence | Gap |
|---|---|---|---|
| Acting beyond scope | `ToolClassification` + gate; `Mandate`/`MandateGate` (explicit scope, principal and validity); `AutonomyLevel`; `ToolManifest`/`ProvenanceGate` (no unexpected tools) | Denials citing the mandate or manifest | Scope is defined by what you classify; an unclassified tool in WARN mode runs |
| Reconstructing a multi-step chain | Every event carries `runId`, `sessionId`, `correlationId`, `callId`; approvals link by `approvalId`; `HashChainedAuditSink` proves the sequence was not edited; trace record/replay re-runs it | `SELECT ... FROM vegaduta_audit WHERE run_id = ? ORDER BY created_at` | Model reasoning between tool calls is not captured by the SDK (only `model.call` summaries); pair with OTel traces |
| Supervision and books-and-records | Append-only audit, approval receipts, retention is configurable at the database | Audit table, receipts | Retention and WORM storage are the firm's; the SDK does not implement a records schedule |

### Federal Reserve SR 26-2 (replacing SR 11-7)

SR 26-2 replaces SR 11-7 as model risk management guidance and **excludes generative and agentic
AI from its scope**, which leaves banks to set their own controls for agents. The SDK does not
fill a regulatory gap; it gives a bank concrete controls it can write into its own agent policy.

| Bank-defined control area | SDK mechanism | Evidence |
|---|---|---|
| Inventory of agent capabilities | `ToolManifest`, `agent.toolset` events, `agent-scanner` | Manifest and scan output |
| Pre-use limits and approval thresholds | `GovernancePolicy` thresholds, `AutonomyLevel`, `Mandate` | Policy in version control |
| Independent review of high-impact actions | `DefaultApprovalGate`, four-eyes | Approval trail |
| Ongoing monitoring and kill switch | Observers, `CostLedger`, `HaltSwitch` + `HaltDrill` | Metrics, halt and drill events |
| Change control for policy | `PolicyReplay`, `ShadowGate` | Replay diffs |
| Evidence integrity | `HashChainedAuditSink`, evidence exporters | Verifiable bundle |

## 8. Cross-reference by mechanism

| Mechanism | OWASP | EU AI Act | ISO 42001 | NIST | SOC 2 | FINRA / SR 26-2 |
|---|---|---|---|---|---|---|
| Classification + gate | ASI02, 05, 10 | 9, 14 | A.6 | Map, Manage | CC6.1 | scope |
| Approval + four-eyes | ASI09 | 14 | A.9 | Manage | CC6.2/3 | independent review |
| Budget / cost ledger | ASI08 | 15 | A.6 | Measure | CC7.2 | monitoring |
| Effect journal / reconciling | ASI08 | 15 | A.6 | Manage | CC7.5 | chain reconstruction |
| Guardrails | ASI01, 02, 06 | 15 | A.7 | Manage | CC6.6/7 | |
| Trifecta detector | ASI01, 06 | 9 | A.5 | Map | | scope |
| Hooks / webhooks | ASI07 | 14 | A.6 | Manage | CC6.7 | |
| Audit + hash chain + exporters | all | 12 | A.6.2.6 | Measure | CC7.1/3 | books and records |
| Autonomy / mandate | ASI03, 10 | 14 | A.9 | Govern | CC6.1 | scope |
| Halt switch / drill | ASI10 | 14 | A.9 | Manage | CC7.3 | kill switch |
| Manifest / provenance / scanner | ASI04 | 9 | A.10 | Map | CC8.1 | inventory |
| Policy replay / shadow gate | ASI10 | 9 | A.6 | Measure | CC8.1 | change control |
| Governed memory | ASI06 | 15 | A.7 | Manage | | |
| Trace record/replay | | 15 | A.6 | Measure | CC8.1 | chain reconstruction |

## 9. Honest gaps, collected

1. **Pattern-based injection detection is bypassable.** It is a tripwire that catches the
   obvious; it will not catch paraphrase, other languages, encodings or indirect instructions.
   Use a model-based guardrail and, above all, the trifecta rule and approvals for the tools
   that matter.
2. **Default mode is WARN.** Unclassified tools run. Production reviews should insist on STRICT.
3. **The SDK trusts its inputs.** `RunContext` (who is acting, for whom, in which tenant) comes
   from the host. The approvals endpoint is unauthenticated by default.
4. **Append-only and retention are database policies**, not SDK enforcement. The hash chain
   (this change) detects, but does not prevent, tampering, and needs an external anchor to
   detect wholesale replacement.
5. **Scope of control is the `GovernedTool` wrapper.** Anything the agent does without going
   through it (direct calls in plain Java, streamed replies in 0.1, memory the framework keeps
   itself) is not governed.
6. **No model-level controls.** Accuracy, bias, hallucination and adversarial robustness of the
   model are out of scope.
7. **Token budgets are recorded, not enforced**, in 0.1.
8. **Standards are not shortcuts.** ISO/IEC 42001 certification, SOC 2 reports and EU AI Act
   conformity are about your organisation and system; the SDK supplies evidence and controls.
9. **0.1.0-SNAPSHOT, no third-party audit.** No release, no signed artifact to verify yet, no
   external penetration test. Several mechanisms in this document are being added in the same
   change and are `@Experimental`.
10. **Names of external standards move.** OWASP ASI titles, OTel GenAI attributes and the ACS
    hook names are pinned behind SDK enums; when the standard moves, the mapping moves.

## 10. Producing the evidence

What to hand an auditor, and where it comes from. Column names are those of
`schema-postgresql.sql` in `agent-jdbc`; the H2 DDL has the same columns.

| Ask | Source | How |
|---|---|---|
| "Show me every action of run X, in order" | `vegaduta_audit` | `SELECT created_at, actor_id, action_type, target_id, outcome, detail, risks, attributes FROM vegaduta_audit WHERE run_id = ? ORDER BY created_at` |
| "Which tools could this agent reach on date D?" | `agent.toolset` events | `... WHERE action_type = 'agent.toolset' AND agent_id = ? AND created_at BETWEEN ? AND ?`; the `detail`/`attributes` carry the tool list and the trifecta report |
| "Who approved refund R, what exactly did they approve, and when?" | `vegaduta_approval` | `SELECT requested_by, decided_by, decided_at, decision_reason, summary, payload, result FROM vegaduta_approval WHERE id = ?`; check `decided_by <> requested_by` |
| "Were any approvals decided by their requester?" | `vegaduta_approval` | `SELECT count(*) FROM vegaduta_approval WHERE decided_by = requested_by` (expected 0 with four-eyes on) |
| "What was denied last quarter, and why?" | `vegaduta_audit` | `... WHERE outcome = 'DENIED' AND created_at >= ?`; group by `detail` prefix (`gate`, `guardrail`, `hook:`) |
| "How often did a guardrail fire, by rule?" | `vegaduta_audit` | `... WHERE detail LIKE 'guardrail:%' OR detail LIKE 'output redacted:%'`; rule ids such as `secret.aws_access_key.egress` are in `detail` |
| "Did any run hit its budget?" | `vegaduta_audit` | `... WHERE outcome = 'BUDGET_EXCEEDED'` |
| "Any half-finished side effects?" | `vegaduta_effect` | `SELECT effect_key, created_at FROM vegaduta_effect WHERE status = 'STARTED'` (`JdbcEffectJournal` writes `STARTED` on `begin` and `DONE` on `record`); `ReconcilingEffectJournal` (this change) produces this as a report |
| "Coverage of OWASP ASI items in the last 30 days" | `vegaduta_audit.risks` | Split the comma-separated `risks` column and count per id; `GovernanceTestKit.coversRisks(...)` gives the same per test suite |
| "Prove the log was not edited" | `HashChainedAuditSink` (this change) | Run the verifier over the table or the exported bundle; compare the head hash with the one you anchored externally (ticket, signed email, timestamping service) |
| "Prove the kill switch works" | `HaltDrill` (this change) | The drill's audit event, with date and outcome |
| "Show the policy in force on date D" | Version control | `GovernancePolicy` is code or configuration; use the commit or the configuration snapshot of that deployment |
| "Show the policy change was tested" | `PolicyReplay` / `ShadowGate` (this change), CI | Replay diff attached to the change request; `GovernanceTestKit` results in CI |

Retention reminder: `vegaduta_audit` grows forever by design. The deployer's records schedule
decides when to archive; for EU AI Act high-risk deployers the floor is six months (Art. 26(6)).
Archive by exporting (the evidence exporters produce a verifiable bundle) rather than by
`DELETE`, which would break the hash chain.

## 11. Suggested review workflow

1. Read `GovernancePolicy` and the deployed `vegaduta.agent.*` properties. Confirm `mode=strict`,
   a sensible `approval.required-from`, non-zero budgets, `store.type=jdbc`.
2. List the tools (an `agent.toolset` event, or `agent-scanner` output) and check every one has
   a classification you agree with. Pay attention to `exfiltrationSink` and
   `consumesUntrustedInput`; those two drive the trifecta rule.
3. Run the checklist in the [security review pack](security-review-pack.md#9-what-a-reviewer-should-test)
   against a test deployment.
4. Pick three real runs from the audit table and reconstruct them end to end with the queries
   above. If you cannot, the evidence is not good enough yet.
5. Record the gaps from section 9 that apply to you as accepted risks or as actions, and attach
   this document and the pack to the review.
