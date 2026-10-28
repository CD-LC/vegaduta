# OWASP mapping

How the controls in this repository line up against three OWASP lists. Written to be checked,
not to reassure: every "Covered" or "Partly" row names the file, config key and test it rests
on, and anything I could not point to in code is marked **Not covered**.

| List | Edition | Source |
|---|---|---|
| OWASP Top 10 for Agentic Applications (ASI01-ASI10) | 2026, published 2025-12-09 | <https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/> |
| OWASP Top 10 for LLM Applications (LLM01-LLM10) | 2025 | <https://genai.owasp.org/llm-top-10/> |
| OWASP MCP Top 10 (MCP01-MCP10) | 2025, beta | <https://owasp.org/www-project-mcp-top-10/> |

## How to read the status

| Status | Meaning |
|---|---|
| **Covered** | a control exists, is on by default, has a test, and I found no bypass in the code I read |
| **Partly** | a control exists but at least one of: no test, off by default, fails open, regex-only, or a known bypass. A control without a test is always Partly |
| **Not covered** | I found no control. A neighbouring control may help; it is named in the note |

Paths are repo-relative, as of the audit date (2026-10-14, before the ADR-0023
split). `G` = `core/guardrails/src/main/java/com/agenticai/core/guardrails`,
`A` = `core/agents/src/main/java/com/agenticai/core/agents`, `T` = `A/tools`
(most `T` classes now live under `core/agents-tools` or `core/agents-browser`
in the same package).
"Tests" lists test classes that exercise the control; I did not audit assertion depth for each.

## Method and limits

- Evidence comes from reading the code in this working tree on 2026-10-14. Nothing was run on
  a stack; Java tests named here were not all re-run (the ones for the new controls were).
- The Agentic list's entry titles were confirmed from a secondary source (Modulos' framework
  page) because the OWASP resource page serves only a download link; the official PDF was not
  opened. IDs ASI01 and its title were confirmed from several independent summaries. If a
  title differs from the PDF, the ID is the authority.
- The MCP list is a beta project and may be reordered.
- The older handbook pages (`docs/platform-documentation/security/*.html`) make no OWASP-ID
  claims; this page does not rely on them for coverage.
- The MCP tool-pinning work (`McpToolPin*`, `V491__mcp_tool_pinning.sql`) was uncommitted when
  this mapping was first written. It has since been committed (with unit and integration tests and
  the `app.mcp.tool-pinning.mode` key); the rows below were updated on 2026-10-15 from the code in
  the repository, not by re-running the tests. It stays scored Partly.

## Changes made with this mapping

Three controls were added while writing it, each with tests:

| Control | Closes part of | Where | Config |
|---|---|---|---|
| Operator egress allow-list for tenant-configured outbound URLs | ASI02, LLM06, MCP09 | `core/security/.../net/SsrfGuard.java` (`egressPermits`), `SsrfGuardConfiguration.java`; `SsrfGuardEgressAllowListTest` | `app.security.outbound.egress-allow-list` (empty = any public host) |
| Write-time screen on `add_to_knowledge_base` (injection phrases, credentials) | ASI06, LLM04, LLM02 | `core/knowledge/.../KnowledgeWriteScreen.java`, `AddToKnowledgeBaseToolCallback.java`; `AddToKnowledgeBaseToolCallbackTest` | none (always on) |
| Per-turn cap on remote A2A calls | ASI07, ASI08, LLM10 | `A/a2a/A2aRemoteAgentToolCallback.java`, `A2aProperties.Client`; `A2aClientServiceTest` | `app.a2a.client.max-calls-per-turn` (default 5, 0 = off) |

The egress list is **platform-wide, not per agent**. Per-agent egress remains open (issue 01).

### Where an operator sees these

| Control | UI |
|---|---|
| Egress allow-list, private-host exceptions, A2A client and server settings, per-turn A2A cap | Platform Admin -> Safety & Moderation -> "Outbound allow-list" and "Agent-to-agent (A2A)" panels (`web/app/platform-admin/components/sections/outbound-policy.tsx`), read-only, backed by `GET /api/platform/security/outbound-policy` (`OutboundPolicyController`). Read-only on purpose: these are environment settings, and a stolen admin session must not be able to widen egress |
| A2A REST binding | Listed in both Agent Cards' `supportedInterfaces` and in the console's API reference page |
| Knowledge-base write refusal | No settings UI (always on). The refusal text is the tool result, so it appears in the agent's activity trace and playground |
| Per-agent egress list | Not built (issue 01 includes the agent-editor field) |

## OWASP Agentic Top 10 (2026)

| ID | Item | Status | Controls | Tests | Gap |
|---|---|---|---|---|---|
| ASI01 | Agent Goal Hijack | Partly | Hard-block phrases and secret redaction on tool output: `G/PatternBasedGuardrailService`. LLM injection judge: `G/SemanticGuardrailService` (`app.guardrails.implementation=semantic`). Jailbreak detector: `G/impl/JailbreakDetectorGuardrail` (`app.agents.default-guardrails`). Input moderation: `A/ChatGuardrailGate`, `G/moderation/ModerationService`. Lethal-trifecta detector: `A/tools/LethalTrifectaDetector` | `PatternBasedGuardrailServiceTest`, `SemanticGuardrailServiceTest`, `JailbreakDetectorGuardrail*Test`, `ModerationServiceTest`, `LethalTrifectaDetectorTest` | The semantic judge fails closed by default on error or a blank answer (`app.guardrails.fail-mode`; the output is withheld, `open` falls back to pattern-only); a missing client is pattern-only. The pattern layer is regex-only. No structural separation of tool output from instructions. `app.tools.classification.trifecta-enforcement` defaults to `warn`. `ChatGuardrailGate` has no direct test |
| ASI02 | Tool Misuse and Exploitation | Partly | Per-turn step cap, approval gate, verifier, output size cap: `G/GuardedToolCallback`. Dispatch gate: `G/ToolDispatchGate` implemented by `A/tools/AgentToolGovernance`. Argument policies: `G/toolargs/*`, `A/tools/McpToolArgumentGuard`. Confirmation gate `app.tools.classification.confirmation-gate`. **New:** egress allow-list (platform-wide) | `GuardedToolCallbackGovernanceTest`, `ToolArgPolicy*Test`, `McpToolArgumentGuardTest`, `SsrfGuardEgressAllowListTest` | Tool-arg policies are on in the base profile since 2026-10-23 (`app.tool-arg-policies.enabled`; inert until a policy is saved; off in `personal`). The dispatch gate fails closed on a lookup error by default (`GuardedToolCallback.checkDispatchGate`, `app.guardrails.fail-mode`; `open` restores the old fail-open). The RIGOROUS tool-call verifier follows the same mode by default (`app.guardrails.tool-verifier.fail-mode=inherit`), is shown the last `app.guardrails.tool-verifier.context-messages` (default 6) messages of the conversation as context for the current user message (`0` restores judging the message alone), and runs only for RIGOROUS agents. A tool-arg policy **lookup** that throws now follows `app.guardrails.fail-mode` too (refused when `closed`, the default; it used to let the call through whatever the mode; an evaluation that throws on a matching policy refuses it in both modes; `ToolArgPolicyCallback`, `ToolArgPolicyCallbackTest`). `McpToolArgumentGuard` calls itself a coarse tripwire. No per-agent egress |
| ASI03 | Identity and Privilege Abuse | Partly | Delegation tokens: `core/security/.../DelegationTokenAuthFilter`, `A/trust/DelegationTokenService`. Permission matchers: `core/security/.../SecurityConfig` (`PERM_*`, see [authorization.md](authorization.md)). Secrets bound to hosts: `core/secrets/.../SecretHostPolicy`. Autonomy tiers: `A/AgentAutonomyTier` | `DelegationTokenAuthFilterTest`, `DelegationTokenServiceTest`, `SecretHostPolicyTest`, `HttpRequestExecutorSecurityTest` | Per-tool scope narrowing of delegated credentials was not traced. `A/trust/CapabilityManifestService` has no test |
| ASI04 | Agentic Supply Chain Vulnerabilities | Partly | Skill scanning and content-hash pinning: `A/skills` (`SkillScanner`, `SkillSupplyChainService`, `app.agents.skills.supply-chain.*`). MCP tool name/description clamping: `A/tools/McpToolOutputSanitizer`, `NamespacedMcpToolCallback`. MCP tool pinning: `core/mcp/.../McpToolPin*`, `T/McpToolPinGate` (`app.mcp.tool-pinning.mode`). Release signing, SBOM, provenance: `.github/workflows/release.yml`. Scanners: `trivy.yml`, `license-scan.yml`, `scorecard.yml`, `codeql.yml`, `.github/dependabot.yml` | `SkillSupplyChainServiceTest`, `SkillScannerTest`, `SkillIntegrityTest`, `NamespacedMcpToolCallbackTest`, `McpToolOutputSanitizerTest`, `McpToolPinServiceTest`, `McpToolPinIntegrationTest` | Tool pinning defaults to `warn` (record and report; `enforce` withholds a changed server's tools until approved). Workflow behaviour not exercised here |
| ASI05 | Unexpected Code Execution | Partly | Launcher allow-list and metacharacter block: `core/mcp/.../McpStdioCommandGuard`. Env isolation: `A/tools/EnvIsolatedStdioClientTransport`. Sidecar with seccomp profile (Compose files only; Helm uses `RuntimeDefault` plus a required VM-isolating RuntimeClass) and isolated network: `services/mcp-stdio-runner`. Code sandbox: `services/tool-sandbox`. Design: `docs/architecture/MCP-STDIO-SANDBOXING-DESIGN.md` | `McpStdioCommandGuardTest`, `McpStdioParamsBuilderTest`, `services/mcp-stdio-runner/server.test.js`, `services/tool-sandbox/tests` | The stdio sidecar is **off by default** (`mcp-stdio-sidecar.enabled`, env `MCP_STDIO_SIDECAR_ENABLED`). Without it `npx <package>` runs package code in the core container; the repo's own threat model says so. Since the image split the release `core` image is built with `MCP_TOOLCHAIN=none` (no Node/uv in core, so STDIO servers need the runner); only `core-full` carries the in-core toolchain (`core/Dockerfile`, `release.yml`) |
| ASI06 | Memory and Context Poisoning | Partly | Per-subject admission caps: `A/memory/OwnFactAdmission`. Session ids scoped by tenant, agent, subject: `A/memory/CallerSessionIds`. Provenance: `A/memory/MemoryProvenanceService`. Write arbitration: `core/graphmemory/.../MemoryWriteArbitrationService`. **New:** KB write screen `KnowledgeWriteScreen` | `OwnFactAdmissionTest`, `MemoryProvenanceServiceTest`, `MemoryWriteArbitrationServiceTest`, `AddToKnowledgeBaseToolCallbackTest` | `remember_fact` and graph-memory writes have no injection scan (issue 05). The KB screen is a regex tripwire; a paraphrased directive passes. `RememberFactToolCallback`: no test found |
| ASI07 | Insecure Inter-Agent Communication | Partly | Outbound: signed-card verification (`A/a2a/A2aCardVerifier`, `require-signed-card=true`), host allow-list (`A/a2a/A2aEgressGuard`), untrusted-data framing (`A2aClientService.sanitizeRemoteText`), **new** per-turn call cap. Inbound: bearer JWT + RUNNER check (`A2aJsonRpcController`, `A2aRestController`); cards signed (`A2aCardSigningService`) | `A2aCardVerifierTest`, `A2aCardVerifierSsrfTest`, `A2aClientServiceTest`, `A2aJsonRpcControllerTest`, `A2aRestControllerTest`, `A2aInboundGuardTest`, `A2aNonceCacheTest`, `A2aHopRegistryTest`, `A2aTwoHopTest` | The outbound client is off by default and per-agent opt-in. **New:** detached Ed25519 request signatures (`A/a2a/A2aMessageSigner`, `A2aInboundGuard`; same key as card signing) with a 5-minute timestamp window and a per-tenant nonce replay cache, and a hop counter (`app.a2a.max-hops`, default 4) propagated from an inbound request to the outbound calls made while serving it, with the trace id. Limits: signatures are verified only when sent, and unsigned legacy peers are accepted until `app.a2a.server.require-signed-messages=true` (default false for one release); the key is resolved from the caller's own signed card (origin-bound, not independent attestation; pin with `trusted-card-hosts`); the signature does not bind the receiving host; the nonce cache is per replica unless `app.a2a.task-store=redis`; a caller can lie about its hop (it only shortens its own chain's budget at the next hop that counts correctly); only the JSON-RPC client path sends signatures. Tested in-process, not against an external A2A peer |
| ASI08 | Cascading Failures | Partly | Circuit breaker: `A/tools/ToolCircuitBreakerService`. USD run budget: `A/tools/RunBudgetToolCallback`, `A/collab/RunBudget` (`app.guardrails.run-budget.usd`). Idempotent replay: `A/tools/EffectJournal`. Spend caps: `A/spendcap/SpendCapService`. Step cap, delegation depth 1, 8 team branches (see [Limits](#existing-limits-on-depth-and-fan-out)) | `ToolCircuitBreakerServiceTest`, `RunBudgetTest`, `StepJournalTest`, `GuardedToolCallbackGovernanceTest`, `DelegateToTeamToolCallbackTest` | The USD budget covers delegation tools only. A sub-agent gets its own step counter. The depth-1 delegation cap has no direct test |
| ASI09 | Human-Agent Trust Exploitation | Partly | Approval gate: `core/approval/.../HumanApprovalGateService`, `ApprovalDecisionAuthorizer`, `ApprovalReplayGrant`. Plain-language confirmation text: `G/ToolConfirmationDescriber` | `HumanApprovalGateServiceTest`, `ApprovalControllerTest`, `ApprovalReplayGrantTest`, `GuardedToolCallbackConfirmationSummaryTest` | Fully-autonomous agents skip the classification gate except for certain irreversible send tools. Nothing checks that the approval summary matches what will execute beyond the describer |
| ASI10 | Rogue Agents | Partly | Quarantined/unclassified tools denied: `AgentToolGovernance.decide`. Attestation: `A/attestation/AgentAttestationService`. Tamper-evident audit: `core/audit/.../seal/AuditSealService`. Per-run budget | `AuditSealSweepTest`, `AuditSealA2aSignerTest` | No behavioural anomaly detection. Attestation has little direct test coverage |

## OWASP LLM Top 10 (2025)

| ID | Item | Status | Controls | Tests | Gap |
|---|---|---|---|---|---|
| LLM01 | Prompt Injection | Partly | As ASI01; plus control-character stripping (`A/tools/McpToolOutputSanitizer`) and the untrusted-data wrapper on remote A2A replies | as ASI01 | Regex plus a judge that fails closed by default; no structural separation |
| LLM02 | Sensitive Information Disclosure | Partly | PII guardrail `G/impl/PiiGuardrail`; secret/PII redaction of tool output (`PatternBasedGuardrailService`, `AgentToolGovernance.outputRedactorFor`); `core/secrets/.../SecretRedactor`; `A/tools/McpSecretRedactor`; AES-256-GCM at rest `core/crypto/.../EncryptionService`; log masking `core/security/.../LogMasking`; **new** KB write screen refuses credentials | `ContentGuardrailsImplementationTest`, `SecretRedactorTest`, `EncryptionServiceTest`, `LogMaskingTest`, `AddToKnowledgeBaseToolCallbackTest` | Redaction patterns are a fixed regex list |
| LLM03 | Supply Chain | Partly | As ASI04; licence allow-list and drift check `scripts/ci/license-allowlist.txt`, `license-drift-check.py`; gitleaks `scripts/ci/secret-scan.sh` | none for the CI scripts | No model-weight or model-provider provenance control |
| LLM04 | Data and Model Poisoning | Partly | Skill scanner (`A/skills`); memory admission caps; **new** KB write screen | skill tests, `AddToKnowledgeBaseToolCallbackTest` | No scan of documents uploaded to knowledge collections (human upload is trusted by design); no embedding-poisoning control |
| LLM05 | Improper Output Handling | Partly | Tool-output size bound: a result over `app.tools.output.max-chars` (20,000, per-tool overrides) is truncated with a marker that says how much was cut (`G/ToolOutputProperties`; the former JSON-well-formedness check and "withhold the result" behaviour were removed, `G/ToolCallOutputValidator` now only holds the log marker); content guardrail engine `G/ContentGuardrailEngine` | `ContentGuardrailEngineTest` | Web rendering of model output (CSP, escaping) not examined |
| LLM06 | Excessive Agency | Partly | As ASI02 and ASI09; per-agent tool allow-list `Agent.allowedToolNames`; autonomy tiers; **new** egress allow-list | as ASI02 | An empty tool allow-list means unrestricted (documented in `AgentIntegrationToolResolver`) |
| LLM07 | System Prompt Leakage | **Not covered** (output side) | Only an input-side tripwire for "reveal your system prompt" in `G/PatternBasedGuardrailService` and `JailbreakDetectorGuardrail` | `JailbreakDetector*Test` | No filter on model output for system-prompt content; no canary. Prompts are not treated as secrets |
| LLM08 | Vector and Embedding Weaknesses | Partly | Schema-per-tenant isolation (`core/tenancy`, `SchemaNameSanitizer`); Cypher literal escaping `core/graphmemory/.../CypherLiteralEscaper` | `CypherLiteralEscaperTest`, `GraphMemoryServiceTest` | Knowledge isolation tests not audited; no embedding-inversion control |
| LLM09 | Misinformation | Partly | `A/AnswerConsistencyChecker`, `A/LlmQualityJudgeService`, `A/ReflexionService`, `A/ragquality` | `AnswerConsistencyCheckerTest` | Quality gates are sampled/optional |
| LLM10 | Unbounded Consumption | **Covered** | Tool-step cap `app.guardrails.max-tool-calls-per-turn` (15, scaled by autonomy tier); USD run budget; `app.agents.default-max-tokens`; per-user budget `A/PerUserBudgetService`; spend caps; HTTP rate limits `core/security/.../ratelimit`; body size limit `RequestBodySizeLimitFilter`; MCP server rate limit `McpServerRateLimiter`; **new** A2A call cap | `GuardedToolCallbackGovernanceTest`, `PerUserBudgetServiceTest`, `RequestBodySizeLimitFilterTest`, `McpServerRateLimiterTest`, `A2aClientServiceTest` | The step cap has no per-tenant tier; a sub-agent has its own counter (bounded: depth 1 x 8 branches) |

## OWASP MCP Top 10 (2025, beta)

| ID | Item | Status | Controls | Tests | Gap |
|---|---|---|---|---|---|
| MCP01 | Token Mismanagement and Secret Exposure | Partly | Env-isolated stdio spawn `A/tools/EnvIsolatedStdioClientTransport`; `A/tools/McpSecretRedactor`; `A/tools/McpRemoteHeaders`; vault `core/secrets/.../SecretVaultService`; envelope encryption `core/crypto/.../EnvelopeEncryptionService`; hashed MCP keys `core/mcpserver/.../McpApiKeyHasher` | `McpRemoteHeaders*Test`, `SecretVaultServiceTest`, `EnvelopeEncryptionServiceTest` | `EnvIsolatedStdioClientTransport` has no dedicated test that I found |
| MCP02 | Privilege Escalation via Scope Creep | Partly | Per-key grants `core/mcpserver/.../McpServerAccessGrant`; `PERM_MANAGE_INTEGRATIONS` | `McpApiKeyAuthFilterExposureTest`, `McpServerStreamableHttpContractTest` | Per-tool scoping for the inbound MCP server not traced |
| MCP03 | Tool Poisoning | Partly | Name/description clamp (`A/tools/SanitizedMcpToolCallback`, `McpToolOutputSanitizer`); server namespacing (`NamespacedMcpToolCallback`); tool pinning `McpToolPin*` (committed; `warn` by default) | `SanitizedMcpToolCallbackTest`, `McpToolOutputSanitizerTest`, `NamespacedMcpToolCallbackTest`, `McpToolPinServiceTest`, `McpToolPinTest`, `McpToolPinControllerTest`, `McpToolPinIntegrationTest` | Config key `app.mcp.tool-pinning.mode` (`off`, `warn`, `enforce`; default `warn`, see [mcp-spec-alignment.md](../architecture/mcp-spec-alignment.md)). Default `warn` only records and reports; `enforce` is opt-in. Annotations are not pinned, and static YAML-wired servers were not checked (issue 06) |
| MCP04 | Software Supply Chain and Dependency Tampering | Partly | As ASI04 | as ASI04 | as ASI04 |
| MCP05 | Command Injection and Execution | Partly | `McpStdioCommandGuard`; stdio sidecar | `McpStdioCommandGuardTest` | Sidecar off by default (issue 04) |
| MCP06 | Prompt Injection via Contextual Payloads | Partly | As LLM01 | as ASI01 | as LLM01 |
| MCP07 | Insufficient Authentication and Authorization | Partly | Inbound: `core/mcpserver/.../McpApiKeyAuthFilter`. Outbound: origin pinning `A/tools/McpOriginPinning`, URL guard `core/mcp/.../McpRemoteUrlGuard`, pre-probe `A/tools/RemoteMcpPreProbe` | `McpApiKeyAuthFilterTest`, `McpOriginPinningTest`, `McpRemoteUrlGuardTest`, `RemoteMcpPreProbeTest`, `McpGuardedSocketTest`, `GuardedHttpClientBuilderTest` | The remote pre-probe now goes through the address-pinned HTTP client (see the CHANGELOG, Security). The SDK's own connection no longer has a DNS-rebinding window: the transports are built on the address-pinned client (`GuardedHttpClientBuilder`), so each connect resolves through `SsrfGuard` and goes to the validated address (`McpGuardedSocketTest`). Open: the pre-buffer response-size cap (issue 07). Not re-verified on a live stack |
| MCP08 | Lack of Audit and Telemetry | Partly | `core/audit/.../AuditLogService`, `audit/seal/*`; tool-call logging `A/tools/LoggingToolCallback`; metrics `agent.tool.calls`, `agent.tool.errors` in `GuardedToolCallback` | `AuditSealSweepTest`, `LoggingToolCallback*Test` | `application.yml` contains `tool-call-log.enabled: false` at a path I did not trace to the live default; check before relying on persisted tool logs |
| MCP09 | Shadow MCP Servers | Partly | Registration with review status `core/mcp/.../McpReviewStatus`, `McpRegistryService`; exposure policy `A/tools/StaticMcpExposurePolicy`; `PERM_MANAGE_INTEGRATIONS` for registering; **new** egress allow-list | `McpRegistryReviewServiceTest`, `StaticMcpExposurePolicyTest`, `McpRegistryServiceTest`, `SsrfGuardEgressAllowListTest` | Egress list is platform-wide and off by default; no discovery of unregistered servers |
| MCP10 | Context Injection and Over-Sharing | Partly | Delegates see only the question plus a short summary `A/ChatDelegateCall`; tenant context propagation `TenantContextPropagatingToolCallback`; tool selection cap `ToolRelevanceSelector` (`app.tools.auto-selection.max-tools-per-turn`) | `ToolRelevanceSelectorTest` | No MCP-specific context-minimisation test |

### Existing limits on depth and fan-out

| Limit | Where | Value | Test |
|---|---|---|---|
| Tool calls per turn | `G/GuardedToolCallback.guardedCallBody`: calls over the ceiling are not run and get the `tool_budget_exhausted` result, `app.guardrails.step-limit.grace-calls` later it throws `ToolStepLimitExceededException`; `app.guardrails.max-tool-calls-per-turn` | 15; /2 (min 3) supervised, x2 fully autonomous, both configurable (`AgentToolGuard.effectiveMaxStepsFor`, `app.guardrails.step-limit.*`) | `GuardedToolCallbackStepLimitTest`, `GuardedToolCallbackGovernanceTest` |
| Delegation depth | `AgentToolProvider.resolveToolsForDelegatedCall` passes `allowDelegation=false` | 1 | none direct |
| Crew handoff depth | `A/crew/HandoffChain.MAX_DEPTH` + cycle check | 3 | `HandoffChainTest` |
| Team fan-out | `A/collab/DelegateToTeamToolCallback.MAX_BRANCHES` | 8 branches, per-branch timeout, 4,000-char answers | `DelegateToTeamToolCallbackTest` |
| Remote A2A calls | `A2aRemoteAgentToolCallback` | 5 per turn (new) | `A2aClientServiceTest` |

## Not verified

- No control was exercised on a running stack; "Covered" means code plus unit tests.
- The test-class names come from searching for classes that reference each control. A class
  existing is not proof it asserts the property in the "Controls" cell.
- Items I did not trace: per-tool scope narrowing for delegated tokens (ASI03), inbound MCP
  per-tool scoping (MCP02), web output encoding (LLM05), knowledge isolation tests (LLM08), the
  live default of tool-call persistence (MCP08).
- The Agentic Top 10 official PDF was not opened (see Method and limits).

## Pending work (kept open on purpose)

Tracked as draft issues in [.github/launch-issues/](../../.github/launch-issues/). Nothing below is done.

| # | Item | Items | Rated |
|---|---|---|---|
| 01 | Per-agent egress allow-list (column, resolver wiring, editor field) | ASI02, LLM06, MCP09 | High |
| 02 | Injection judge fail-open is fixed (fail-mode `closed` by default); no uniform untrusted-data envelope | ASI01, LLM01, MCP06 | High |
| 03 | Dispatch gate lookup error now fails closed by default; tool-arg policies off by default | ASI02, LLM06 | High |
| 04 | Stdio MCP sidecar off by default | ASI05, MCP05 | High |
| 05 | Screen `remember_fact` and graph-memory writes (extract the shared screen) | ASI06, LLM04, LLM02 | High |
| 06 | MCP tool pinning: remaining hardening (annotations, static servers, default mode); landing, tests and the config key are done | MCP03, ASI04 | High |
| 07 | DNS-rebinding window on outbound MCP connections (pinning done; the pre-buffer response-size cap is open) | MCP07 | High |
| - | Output-side system-prompt leakage filter / canary | LLM07 | Medium, no issue yet |
| - | One step/budget total across delegated sub-agents (today bounded by depth 1 x 8 branches) | ASI08 | Medium |
| - | Direct test for the depth-1 delegation cap; test for `EnvIsolatedStdioClientTransport`, `RememberFactToolCallback`, `ChatGuardrailGate` | ASI08, MCP01, ASI06, ASI01 | Medium |
| - | A2A: ListTasks, SubscribeToTask, card signing alignment, ETag, gRPC (decision) | see [a2a-conformance.md](../architecture/a2a-conformance.md) | - |

## Follow-up issues

Draft issues for the High gaps are in [.github/launch-issues/](../../.github/launch-issues/):
01 per-agent egress, 02 untrusted-data envelope (judge fail-open fixed), 03 tool-arg policies default (dispatch-gate fail-open fixed), 04 stdio
sidecar default, 05 memory-write screening, 06 MCP tool-pin hardening, 07 MCP DNS rebinding.

See also the console guide: [Administration pages](../console/README.md) (egress and A2A panels, MCP
tool-pin review card, DPDP controls at `/admin/data-privacy`, see the
[DPDP page](../compliance/dpdp-obligations.md#7-console)).
