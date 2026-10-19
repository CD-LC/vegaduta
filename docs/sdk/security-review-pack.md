# VegaDūta Agent SDK: security review pack

What a security reviewer, CISO or auditor needs to assess an application that embeds the Java
Agent SDK (`sdk/java`, group `io.vegaduta`): the threat model of the governed tool pipeline,
trust boundaries, data handled, key management, deployment modes, a filled vendor security
questionnaire, known limitations and a test checklist. The regulatory mapping is in the
[compliance mapping](compliance-mapping.md); the design record is
[ADR-0013](../architecture/ADR-0013-embeddable-agent-sdk.md).

Status: the SDK is `0.1.0-SNAPSHOT`, unreleased, with no third-party assessment. Where a
mechanism is marked *(this change)* it is being added by parallel work and is `@Experimental`.
Reviewers should read the source; the claims here are descriptions of code, not attestations.

## 1. What the SDK is, in one paragraph

A library that wraps each tool an AI agent may call in a `GovernedTool`. Every call runs
through a fixed pipeline (hooks, journal replay, gate, approval, argument guardrails, budget,
the call, journal record, output guardrails, hooks, audit) and produces exactly one audit event
and one observer result. `agent-api` depends on the JDK and JSpecify only; `agent-core` adds
SLF4J and RE2/J. There is no JSON library, no HTTP server, no reflection on user types outside
the adapters, and no network call unless the deployer configures a webhook. The SDK makes no
outbound connection to VegaDūta or anyone else.

## 2. Trust boundaries

```
                         +------------------------------------------------------+
                         |  Host application (your JVM, your security context)  |
                         |                                                      |
  [End user] --prompt--> |  Framework (Spring AI, plain Java)                   |
                         |     |                                                |
                         |     | RunContext (tenant, principal, agent, run)     |
                         |     v                                                |
  [LLM provider] <-----> |  Model call  ----tool request---->  GovernedTool     |
   untrusted output      |                                      |  pipeline     |
                         |                                      v               |
                         |            +-------------------------+-----------+   |
                         |            | gate | approval | guardrails | budget|   |
                         |            +-------------------------+-----------+   |
                         |                                      |               |
                         |                                      v               |
                         |                            delegate Tool  ---------> | [External system]
                         |                                      ^               |  (DB, API, mail,
                         |                     untrusted output |               |   web page ...)
                         |                                      |               |
                         |   AuditSink --> log / JDBC    EffectJournal / ApprovalStore (memory or JDBC)
                         |      |                                                |
                         |      | WebhookHook (optional, HMAC-signed, outbound)  |
                         +------+-----------------------------------------------+
                                v
                      [External policy engine]          [Human reviewer] --approve/reject--> ApprovalGate
```

Boundaries, from least to most trusted:

| Boundary | Trust level | Why |
|---|---|---|
| Model output (tool requests, arguments) | Untrusted | The model can be hijacked by anything it has read |
| Tool output (web pages, documents, API responses) | Untrusted | Primary prompt-injection vector |
| Human reviewer decisions | Trusted after authentication | The SDK records identity; the host authenticates it |
| `RunContext` | Trusted as given | The SDK scopes everything by it and cannot verify it |
| Host application, configuration, `GovernancePolicy` | Trusted | Same JVM, same deployer |
| Stores (JDBC) | Trusted for integrity unless hash-chained | DB privileges decide who can edit |
| External policy engine (webhook) | Semi-trusted | Signed outbound; its answer is honoured per `FailMode` |

## 3. Pipeline threat model (STRIDE)

Stage numbers match the comments in `GovernedTool.pipeline`.

| # | Stage | Threat (STRIDE) | Mitigation in the SDK | Residual |
|---|---|---|---|---|
| 0 | Classification lookup | Tampering: an unclassified tool slips through with a lenient default | STRICT denies unclassified; WARN audits a `WARNING`; `agent-scanner` (this change) fails CI | WARN is the default |
| 1 | `BEFORE_TOOL_CALL` hooks | Elevation: a hook `Replace` rewrites arguments; Denial of service: a slow webhook | Hooks are deployer code; `WebhookHook` has a timeout and per-registration `FailMode` | A fail-open webhook that is down silently allows |
| 2 | Journal replay | Spoofing: a forged journal row returns a fake result; Information disclosure: a replayed result crosses runs | Key = run id + tool + arguments, so no cross-run replay; only `replaySafe` tools replay | Journal rows are trusted; protect the table |
| 3 | Gate | Elevation: a HIGH tool is called without approval | Threshold on risk for side-effecting tools, `requiresApproval`, deny list, trifecta forcing; `MandateGate`, `AutonomyGate`, `ProvenanceGate` (this change) compose as further gates | Classification errors are policy errors |
| 3a | Approval | Repudiation: nobody can say who approved; Spoofing: the requester approves themselves; Tampering: approving a different payload than shown | Request stores raw payload, context, requester, expiry; `FourEyesAuthorizer`; execution uses the stored payload, not a resubmitted one; `ApprovalReceipt` (this change) | Summary is truncated to `argumentPreviewChars`; UI must show the full payload; endpoint unauthenticated by default |
| 4 | Argument guardrails | Information disclosure: secrets sent to a tool (egress) | `BuiltInRules.secrets()` `.egress` rules block AWS keys, GitHub tokens, private keys, bearer headers in `TOOL_ARGUMENTS` | Only known shapes; encoded or split secrets pass |
| 5 | Budget | Denial of service / cost: runaway loop, parallel overspend | `AtomicBudget` reserve-then-commit in one critical section; `CostLedger` (this change) | Per run by default; tokens not enforced |
| 6 | The call | All of them, inside the tool | Out of scope: the SDK decides whether, not how; `journal.begin` before and `record` after so a crash leaves a visible half-done row; `ReconcilingEffectJournal` (this change) reports them | Compensation is the tool owner's |
| 7 | Output guardrails | Tampering (goal hijack): injected instructions in tool output; Information disclosure: secrets in output | Injection phrases redacted; secrets redacted; RE2/J linear time | Pattern-based; see limitations |
| 8 | `AFTER_TOOL_CALL` hooks | Same as stage 1, on the output | Same | Same |
| 9 | Audit | Repudiation: events lost or edited; Denial of service: a failing sink stalls the agent | Sinks never throw into the pipeline; `CompositeAuditSink`; `HashChainedAuditSink` (this change) makes edits and gaps detectable | A sink that drops events silently loses evidence; use JDBC or the chain |
| all | Observers | Information disclosure through telemetry | Observers receive the call and the result status, not the arguments, unless you add them | Your tracing backend's access control |

Cross-cutting threats:

| Threat | Mitigation | Residual |
|---|---|---|
| Bypass: the agent calls a tool without the wrapper | Starter wraps every `ToolCallback`, `ToolCallbackProvider` and tools passed to `ChatClient`; plain Java relies on the host calling `governance.govern(tool)` | In plain Java, unwrapped tools are invisible |
| Mode flipped to `OFF` | `GovernancePolicy` is code or configuration under change control | Protect configuration like code |
| Forged `RunContext` | None in the SDK | Derive it from an authenticated security context (`RunContextResolver`) |
| Lethal trifecta | `LethalTrifectaDetector` on every toolset; STRICT forces sinks to approval | WARN only audits |
| Stop the agent | `HaltSwitch` + `HaltDrill` (this change) | Only governed calls stop |

## 4. Data handled

| Data | Where it goes | Retention | Notes |
|---|---|---|---|
| Tool arguments (raw JSON) | `ApprovalRequest.payloadJson` (approval store), journal key (hashed into the effect key), `HookEvent.payload` to hooks and webhooks | Approval: `approval.ttl` (default 24h) then expired but kept; effect: `journal.ttl` (default 1h) | Arguments may contain personal data; the approval store is the main place they persist in clear |
| Argument preview | `summary` on the approval request, truncated at `argumentPreviewChars` (default 500) with an ellipsis | With the approval | Truncation is for display, not redaction |
| Tool output | Returned to the model; replay-safe outputs stored in `vegaduta_effect.result`; `AFTER_TOOL_CALL` hook payload | `journal.ttl` | Secrets redacted by guardrails before storage only if the guardrail runs before `record` (it does not: record happens at stage 6, output guardrails at stage 7, so the journal holds the unredacted output) |
| Audit events | Logger `io.vegaduta.agent.audit` (flat `key=value`), `vegaduta_audit` | Deployer's | Carries `actorId`, tenant, principal, agent, session, run, correlation ids, `callId`, `risk`, `detail`; it does **not** carry the arguments or the output |
| `detail` on audit events | Free text: gate reason, guardrail rule ids, exception `toString()` | With the event | An exception message may leak tool internals; review your tools' exception text |
| Webhook body | JSON of the `HookEvent` (point, context, tool, payload) to the configured URL | Remote | Signed, not encrypted; use HTTPS |
| Telemetry | Tool name, call id, conversation id, agent id, outcome | Your backend | No arguments |
| Approval decisions | `decided_by`, `decided_at`, `decision_reason`, `result` (the tool output) | With the approval | `result` is the tool output in clear |

Redaction: the built-in rules redact secrets in `TOOL_OUTPUT` and block them in
`TOOL_ARGUMENTS`. Nothing else is redacted by default. Add `ContentGuardrail` beans for PII, or
scrub at the `AuditSink` and `ApprovalStore` if your retention policy needs it.

## 5. Key management

| Key | Used by | Algorithm | Where it lives | Rotation |
|---|---|---|---|---|
| Webhook secret | `WebhookHook` | HMAC-SHA256 over the request body, hex digest in `X-Vegaduta-Signature` | `vegaduta.agent.webhooks[n].secret` (configuration; use your secret store) | Change the property and the receiver; no overlap window built in, so run two registrations during rotation |
| Audit chain key *(this change)* | `HashChainedAuditSink` | SHA-256 chain; optional HMAC with a key so a party without the key cannot re-chain | Configuration | Record the key id in the chain so verification knows which key applied |
| Evidence export signature *(this change)* | Evidence exporters | HMAC-SHA256 or Ed25519 (`java.security` `Ed25519`, JDK 15+), configurable | HMAC key or Ed25519 private key from your store | Ed25519 preferred when the verifier is a different party |

HMAC versus Ed25519, for the reviewer who has to pick:

| | HMAC-SHA256 | Ed25519 |
|---|---|---|
| Who can verify | Anyone holding the secret, who can also forge | Anyone holding the public key; only the private key signs |
| Fit | SDK and policy engine operated by the same team; audit chain where the verifier is also the operator | Evidence handed to an auditor, a regulator or another organisation |
| JDK support | Every JDK | JDK 15+ (`KeyPairGenerator.getInstance("Ed25519")`) |
| Key distribution | Shared secret through a secret store | Public key published; private key in an HSM or KMS if available |
| Non-repudiation | No (symmetric) | Yes, if the private key is protected |

The SDK never generates long-lived keys for you and never stores a key in a database table.
Keys are configuration; treat configuration as a secret-bearing asset.

## 6. Deployment modes

| Mode | Stores | Network | Fits |
|---|---|---|---|
| In-process | `InMemoryApprovalStore`, `InMemoryEffectJournal`, `InMemoryAuditSink` or logger | None | Development, tests, single-instance tools where the log is the record |
| JDBC-durable | `JdbcApprovalStore`, `JdbcEffectJournal`, `JdbcAuditSink` on PostgreSQL or H2 | Database only | Production; approvals survive restarts and span instances; audit is queryable |
| Air-gapped / sovereign / on-prem | Either of the above | None required. No call to VegaDūta, no telemetry phone-home, no licence check, no update check | Regulated and sovereign environments; the only outbound traffic is the webhook you configure and the model provider you choose |
| With external policy engine | Any | Outbound HTTPS to your engine via `WebhookHook` | Teams with a central policy service (OPA, custom) |

Operational notes:

- Run `store.initialize-schema=true` only in development; in production copy the DDL into your
  migration tool and grant the application role `INSERT` and `SELECT` on `vegaduta_audit` but
  not `UPDATE` or `DELETE`.
- Multiple instances share approvals and the journal only in JDBC mode; in-memory stores are
  per JVM.
- `GovernanceMode.OFF` bypasses the pipeline entirely; alert on it in production configuration.

## 7. Vendor security questionnaire (filled)

| # | Question | Answer |
|---|---|---|
| 1 | What does the component do? | Wraps AI-agent tool calls in a governance pipeline: classification, gate, human approval, budgets, guardrails, idempotent effects, hooks, audit. Library only. |
| 2 | Does it make outbound network connections? | Only to webhook URLs the deployer configures (`WebhookHook`, JDK HTTP client) and to the database the deployer configures. Nothing to VegaDūta. |
| 3 | Does it send telemetry or usage data to the vendor? | No. Micrometer and OpenTelemetry integrations publish to the deployer's own backends and are optional. |
| 4 | Does it require internet access to function? | No. It runs air-gapped. |
| 5 | What are the third-party dependencies? | `agent-api`: JSpecify (annotations). `agent-core`: SLF4J, RE2/J; Micrometer and OpenTelemetry API optional. `agent-jdbc`: JDBC only. Adapters depend on Spring AI / Spring Boot. A CycloneDX SBOM is generated per artifact at build time. |
| 6 | Is the build reproducible and signed? | The build is configured for reproducible jars, SBOM, GPG signing and SLSA provenance on release. No release has been published yet, so no signed artifact exists to verify today. |
| 7 | Where is the source and under which licence? | Apache-2.0, in the VegaDūta repository under `sdk/java`. |
| 8 | Has it had a third-party security assessment? | No. Internal tests only (`mvn verify`). |
| 9 | How does it authenticate users? | It does not. The host supplies a `RunContext` (tenant, principal, agent) and authenticates humans who decide approvals. The optional approvals endpoint is unauthenticated and must be secured by the host. |
| 10 | How does it authorise actions? | Per-tool `ToolClassification` evaluated by `PolicyToolDispatchGate`; high-risk side-effecting calls need human approval; the four-eyes rule prevents self-approval. Mandate, autonomy and provenance gates are being added. |
| 11 | What is logged? | One audit event per action: actor, action, target, outcome, detail, run scope, OWASP tags, call id. Arguments and outputs are not in audit events; they are in the approval store and effect journal. |
| 12 | Are logs tamper-evident? | The JDBC audit table is append-only by convention (grant privileges accordingly). A hash-chained sink and signed evidence exporters are being added in this change. |
| 13 | How long is data retained? | Approvals expire after `approval.ttl` (24h default) but rows are kept; journal entries expire after `journal.ttl` (1h default); audit retention is the deployer's. |
| 14 | Does it store secrets? | No keys are stored by the SDK. Webhook secrets and signing keys come from configuration. Tool arguments and outputs that contain secrets may land in the approval store or journal; the guardrails redact known secret shapes in output and block them in arguments. |
| 15 | Does it encrypt data at rest? | No. It writes to your database; use its encryption. |
| 16 | Does it encrypt data in transit? | Webhooks use the JDK HTTP client; use HTTPS URLs. Database transport is the driver's. |
| 17 | How does it handle personal data? | It passes through tool arguments and outputs. Persistence points are the approval store and journal. Redaction beyond secrets is the deployer's (`ContentGuardrail` SPI). |
| 18 | Multi-tenancy? | Every store key and audit event carries `tenantId` from `RunContext`; `ApprovalGate.pending(tenantId, ...)` filters by it. Isolation depends on the host supplying the right tenant. |
| 19 | Can it be turned off or bypassed? | `mode=off` bypasses the pipeline. Tools not wrapped by `govern(...)` are not governed. The Spring Boot starter wraps all tool beans automatically. |
| 20 | Is there a kill switch? | `HaltSwitch` with a `HaltDrill` to prove it works is being added in this change. Today: set a deny-all gate or `mode` and redeploy. |
| 21 | How are policy changes tested? | `GovernanceTestKit` for unit tests; `PolicyReplay` / `ShadowGate` (this change) to evaluate a candidate policy against recorded calls without enforcing it. |
| 22 | Does it protect against prompt injection? | Partially. Built-in RE2/J rules redact known instruction-override phrases in tool output; the lethal-trifecta rule forces approval on exfiltration sinks. Pattern rules are bypassable; a model-based guardrail can be added through the SPI. |
| 23 | Does it protect against denial of service? | Regex rules are RE2/J (linear time). Budgets cap tool calls and spend per run. Webhooks have timeouts. Audit sinks never throw into the pipeline. |
| 24 | What happens when a dependency (DB, webhook) is down? | JDBC store failures surface as exceptions to the host (fail closed for approvals and journal). Webhook failure follows the registration's `FailMode` (open or closed). Audit sink failure is swallowed and logged. |
| 25 | Does it run untrusted code? | No. It does not execute tool code itself beyond calling the delegate the host registered. |
| 26 | Which standards does it map to? | OWASP Top 10 for Agentic Applications 2026 (ids in code), EU AI Act Art. 9/12/14/15, ISO/IEC 42001 Annex A, NIST AI RMF, SOC 2 CC6/CC7, FINRA 2026 agent guidance, SR 26-2 context. See the compliance mapping. Mapping, not certification. |
| 27 | Versioning and breaking changes? | Semantic versioning; japicmp enforced from 1.0. `@Experimental` marks API that may move. Currently 0.1.0-SNAPSHOT: expect change. |
| 28 | How are vulnerabilities reported? | Through the VegaDūta repository's security policy (`SECURITY.md`). |

## 8. Known limitations

1. Pattern-based injection and secret detection is bypassable by paraphrase, encoding, splitting
   or another language. It is a tripwire.
2. WARN is the default mode and runs unclassified tools.
3. The journal records tool output before output guardrails run, so the effect journal holds
   unredacted output for `journal.ttl`.
4. Approval summaries are truncated at `argumentPreviewChars`; the full payload is in the store
   and a reviewer UI must show it.
5. The approvals endpoint has no authentication of its own.
6. Streamed `ChatClient` replies are not inspected by output guardrails in 0.1.
7. Token budgets are recorded, not enforced.
8. Audit `detail` may contain exception text from tools.
9. Webhook signing is one-way HMAC with a shared secret and has no rotation overlap.
10. The hash-chained audit sink (this change) detects tampering but cannot prevent it, and needs
    an external anchor for the head hash to detect full replacement.
11. The SDK cannot verify the `RunContext` it is given.
12. In plain Java, nothing stops a developer calling an unwrapped tool.
13. No third-party assessment, no published release, no signed artifact yet.

## 9. What a reviewer should test

Verify the claims rather than accept them. Each item names the shipped mechanism to exercise;
`GovernanceTestKit` (`agent-testkit`) covers most without a model or a database.

Policy and gate
- [ ] In STRICT, an unclassified tool returns `DENIED` and one `tool.call` event with outcome `DENIED`.
- [ ] In WARN, the same call runs and emits a `WARNING` event with `detail` "unclassified tool".
- [ ] A `HIGH`, side-effecting tool returns `PENDING_APPROVAL` and an `approval.requested` event; a `HIGH` read-only tool runs.
- [ ] A tool on the deny list is denied even when classified `LOW`.
- [ ] A toolset with a private reader, an untrusted consumer and a sink triggers the trifecta report; in STRICT the sink then requires approval.

Approval
- [ ] The requester cannot approve their own request (`ApprovalNotAuthorizedException`).
- [ ] A second decision on the same request fails (`ApprovalAlreadyDecidedException`).
- [ ] A request older than `approval.ttl` reads back as expired and cannot be approved.
- [ ] Approval executes the stored payload, not a payload supplied at decision time.
- [ ] After approval and execution, re-invoking the same call in the same run returns `REPLAYED`, not a second execution or a second request.

Guardrails
- [ ] An AWS key, GitHub token and private key in tool arguments are blocked (`DENIED`, rule id `secret.*.egress`).
- [ ] The same in tool output are redacted and a `WARNING` event names the rule.
- [ ] "Ignore all previous instructions" in tool output is redacted; a paraphrase of it is not (document this as the known gap).
- [ ] A pathological input (long runs of repeated characters) does not stall the chain (RE2/J).

Budget and journal
- [ ] With `max-tool-calls-per-run=3`, the fourth call returns `BUDGET_EXCEEDED` and fires `ON_BUDGET_EXCEEDED`.
- [ ] Concurrent calls from several threads cannot jointly exceed the cap.
- [ ] A tool that throws after `journal.begin` leaves a row that the JDBC journal reports as started-but-unfinished.
- [ ] Two runs with identical tool and arguments do not share a journal entry.

Hooks and webhooks
- [ ] A `BEFORE_TOOL_CALL` hook returning `Block` produces `DENIED` with `hook:` in the reason.
- [ ] The webhook body's HMAC-SHA256 over the exact bytes equals `X-Vegaduta-Signature`.
- [ ] With the webhook endpoint down, a fail-closed registration denies and a fail-open one allows; both are audited.

Audit and telemetry
- [ ] Every exit path of the pipeline (success, replayed, denied, pending, budget, failure, output blocked) produces exactly one `tool.call` event.
- [ ] Audit events carry `runId`, `callId`, `risk` and the `risks` ids; none carries raw arguments or output.
- [ ] A sink that throws does not fail the tool call.
- [ ] The application role has no `UPDATE`/`DELETE` on `vegaduta_audit`.

Configuration and bypass
- [ ] `mode=off` is not set in production configuration (check the deployed properties).
- [ ] Every tool the framework exposes is a `GovernedTool` (in Spring: inspect the beans; in plain Java: review call sites).
- [ ] `RunContext` is derived from the authenticated security context, not from request input.
- [ ] The approvals endpoint, if enabled, sits behind authentication and authorisation.

This change (when the modules land)
- [ ] Editing or deleting one `vegaduta_audit` row breaks the hash chain verification.
- [ ] Tripping the `HaltSwitch` denies the next governed call; the `HaltDrill` records a pass.
- [ ] A call outside a `Mandate`'s scope or validity is denied.
- [ ] A tool not in the `ToolManifest` is denied by `ProvenanceGate`.
- [ ] `ShadowGate` records its verdict without changing the outcome; `PolicyReplay` reports the diff.
