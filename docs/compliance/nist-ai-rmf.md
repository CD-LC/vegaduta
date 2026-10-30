# NIST AI RMF evidence map

Where the controls in this repository line up against the NIST AI Risk Management Framework 1.0
(Govern, Map, Measure, Manage) and the Generative AI Profile, NIST AI 600-1. Read the
[pack index](README.md) first for the status legend and the limits. **This is an evidence
mapping. The AI RMF is voluntary and has no certification; nothing here says that the software
or a deployment "conforms to" it.**

The framework describes outcomes for an organisation. A platform contributes to some of them
(measurement tooling, runtime controls, records) and cannot produce others (accountability,
culture, stakeholder engagement). Rows say which.

Paths are `core/<module>/.../Class.java`. Category identifiers (GOVERN 1 to MANAGE 4) and the
twelve Generative AI risks are cited from the published documents' structure; the documents'
full text was not reproduced here.

## Govern

| Category | Outcome (short) | Status | Evidence (path, key, test) | Note |
|---|---|---|---|---|
| GOVERN 1 | Policies, processes, procedures and practices for AI risk are in place | Organisational | Technical enforcement points: guardrails (`core/guardrails/.../GuardedToolCallback.java`, test `GuardedToolCallbackGovernanceTest`), approvals (`core/approval/.../HumanApprovalGateService.java`, test `HumanApprovalGateServiceTest`) | Policy content is the organisation's. Tenant-level guardrail rules are configurable: `core/guardrails/.../TenantGuardrailRuleEvaluator.java`, test `TenantGuardrailRuleEvaluatorTest` |
| GOVERN 2 | Accountability structures and roles | Partly | Roles and granular permissions: `core/security/.../SecurityConfig.java`, `core/admin/.../DefaultPermissionBundles.java`; tests `DefaultPermissionBundlesTest`, `TenantAssignableRolesTest`. Approver identity recorded in the audit row of a decision (`ApprovalController`) | The software records who decided; naming accountable people is organisational |
| GOVERN 3 | Workforce diversity, equity, inclusion | Organisational | none | |
| GOVERN 4 | Culture of risk management, transparency | Organisational | none | Public mapping pages such as this one and the OWASP mapping are inputs, not a culture |
| GOVERN 5 | Engagement with AI actors and affected people | Partly | In-app AI content reports with a review queue: `core/tenancy/.../aireport/AiContentReportService.java`, test `AiContentReportServiceTest`; turn feedback `core/rl/.../FeedbackService.java`, test `FeedbackServiceTest` | A channel to receive input exists; engagement is a process |
| GOVERN 6 | Policies for third-party software, data and supply chain | Partly | Dependency and licence controls: `.github/dependabot.yml`, `scripts/ci/license-allowlist.txt`, `.github/workflows/license-scan.yml`; signed releases ([SOC 2 map](soc2-iso27001-evidence-map.md)); skill and MCP tool pinning (`core/agents-turn/.../skills/SkillSupplyChainService.java`, test `SkillSupplyChainServiceTest`; `core/agents-tools/.../McpToolPinGate.java`, key `app.mcp.tool-pinning.mode` default `warn`, test `McpToolPinServiceTest`) | No model-provider or model-weight provenance control, no subprocessor list |

## Map

| Category | Outcome (short) | Status | Evidence | Note |
|---|---|---|---|---|
| MAP 1 | Context is established and understood | Organisational | Agent definition records model, prompt and tool allow-list: `core/agents/.../version/AgentConfigSnapshot.java` | Intended purpose, users and legal context are the deployer's |
| MAP 2 | AI systems are categorised | Partly | Tool classification (risk, side effects, reads private data, untrusted input, exfiltration sink): `core/agents-tools/.../AgentToolGovernance.java`; dangerous-combination detector `core/agents/.../LethalTrifectaDetector.java`, test `LethalTrifectaDetectorTest`; autonomy tiers `core/agents/.../core/AgentAutonomyTier.java` | Classifies *tools and agents* by capability, not the system's use case. EU AI Act risk class is the deployer's ([EU AI Act page](eu-ai-act.md)) |
| MAP 3 | Capabilities, usage, benefits and costs are understood | Partly | Cost and pricing telemetry: `core/chat/.../LlmCallLog.java`, spend caps `core/agents-budget/.../SpendCapService.java` (test `SpendCapServiceTest`) | Benefits are not measured by the platform |
| MAP 4 | Risks and benefits mapped for all components, including third-party software and data | Partly | Threat views: [OWASP mapping](../security/owasp-mapping.md), `docs/platform-documentation/security/threat-model.html`. Release SBOMs (see SOC 2 map) | No model cards for integrated third-party models |
| MAP 5 | Impacts to individuals, groups, communities, organisations and society are characterised | **Not covered** | none | No impact assessment support in the product |

## Measure

| Category | Outcome (short) | Status | Evidence | Note |
|---|---|---|---|---|
| MEASURE 1 | Appropriate methods and metrics are identified and applied | Partly | Eval datasets, judges, trajectory evals and reliability statistics (pass^k, Wilson intervals): `core/quality/.../EvalRunService.java`, `stats/EvalReliabilityService.java`; tests `EvalRunServiceTest`, `EvalReliabilityServiceTest`, `WilsonIntervalTest`. Key `app.evals.free-plan-enabled` | Opt-in per agent. No platform-wide metric set |
| MEASURE 2 | Systems are evaluated for trustworthy characteristics | Partly | Valid and reliable: `EvalGateService` (`core/quality/.../gate/`), key `app.quality.gate.pass-hat-k.enabled`, test `EvalGateServiceTest`. Safe: guardrails and tool-call verifier (`core/guardrails/.../ChatClientToolCallVerifierService.java`, key `app.guardrails.tool-verifier.*`, test `ChatClientToolCallVerifierServiceTest`). Secure: see the OWASP mapping. Privacy-enhanced: PII guardrail `core/guardrails/.../impl/PiiGuardrail.java`. Explainable: turn inspector and journal (`core/agents-turn/.../TurnJournalService.java`, `app.turn-journal.enabled` default **false**, test `TurnJournalServiceTest`; [ADR-0020](../architecture/ADR-0020-turn-journal.md)). Fair with harmful bias managed: **not covered** | Bias and fairness evaluation is not built. Groundedness: `core/quality/.../impl/FaithfulnessEvaluator.java`, test `FaithfulnessEvaluatorTest` |
| MEASURE 3 | Mechanisms for tracking identified risks over time | Partly | Violations logged: `core/guardrails/.../GuardrailViolationLogger.java`, test `GuardrailViolationLoggerTest`; audit log `core/audit/.../AuditLogService.java` (test `AuditLogServiceTest`); taint events counted in `observe` mode (`core/agents-tools/.../AgentTaintPolicy.java`, `app.agents.taint.mode`, test `AgentTaintPolicyTest`); SLO targets in `application.yml` (`app.slo`) | Taint is advisory by default. No behavioural anomaly detection |
| MEASURE 4 | Feedback on the efficacy of measurement is gathered | Partly | Eval run history compared across versions; user feedback (`FeedbackService`) | Whether measurement is itself reviewed is a process |

## Manage

| Category | Outcome (short) | Status | Evidence | Note |
|---|---|---|---|---|
| MANAGE 1 | Risks are prioritised, responded to and managed | Partly | Runtime responses: block, redact, park for approval (`GuardedToolCallback`), step caps, circuit breaker (`core/agents-tools/.../ToolCircuitBreakerService.java`, test `ToolCircuitBreakerServiceTest`), run budget (`app.guardrails.run-budget.usd` default 2.0, test `AgentRunBudgetServiceTest`); fail-closed defaults `app.guardrails.fail-mode` (test `ChatGuardrailGateFailModeTest`) | Prioritisation is the organisation's |
| MANAGE 2 | Strategies to maximise benefit and minimise harm are planned and documented | Partly | Autonomy tiers scale step limits (`app.guardrails.step-limit.*`); approval gate; spend caps | |
| MANAGE 3 | Risks from third-party entities are managed | Partly | As GOVERN 6; egress allow-list `app.security.outbound.egress-allow-list` (`core/security/.../net/SsrfGuard.java`, test `SsrfGuardEgressAllowListTest`); A2A egress and signed-card checks (`core/agents-interop/.../A2aEgressGuard.java`, `A2aCardVerifier.java`; tests `A2aCardVerifierTest`, `A2aClientServiceTest`) | |
| MANAGE 4 | Risk treatments, incident response and recovery are documented and monitored | Partly | Kill switches (platform and per tenant): `core/tenancy/.../TenantEmergencyControlService.java`, test `TenantEmergencyControlServiceTest`; [breach runbook](../operations/breach-runbook.md); [backup and restore](../platform-documentation/operations/backup-restore.html); `SECURITY.md` | Incident communication and post-incident review are organisational. No tested disaster-recovery drill ships |

## Generative AI Profile (NIST AI 600-1): the twelve risks

| Risk | Status | Evidence | Note |
|---|---|---|---|
| CBRN information or capabilities | **Not covered** | Content moderation policy packs may refuse some prompts: `core/guardrails/.../moderation/ModerationPolicyService.java`, test `ModerationPolicyServiceTest` | Relies on the model provider's safeguards. No dedicated control |
| Confabulation | Partly | Tool-call verifier (`ChatClientToolCallVerifierService`, `app.guardrails.tool-verifier.*`), citations with spans (`core/knowledge/.../CitationSpans.java`, test `KnowledgeSearchSpanCitationsTest`), groundedness eval (`FaithfulnessEvaluator`, test `FaithfulnessEvaluatorTest`), swarm result verifier (`app.swarm.verifier.enabled`, default false) | Reduces and measures; cannot prevent. No runtime fact-check of every answer |
| Dangerous, violent or hateful content | Partly | `core/guardrails/.../moderation/ModerationService.java` (test `ModerationServiceTest`); `core/guardrails/.../impl/ToxicityGuardrail.java` (key `app.guardrails.toxicity.block-score`, default 60; test `ToxicityGuardrailTest`) | Which guardrails an agent runs is per-agent: default `app.agents.default-guardrails` is `jailbreak` only |
| Data privacy | Partly | PII guardrail `PiiGuardrail`; output and log redaction `core/secrets/.../SecretRedactor.java` (test `SecretRedactorTest`), `core/security/.../LogMasking.java` (test `LogMaskingTest`); encryption at rest `core/crypto/.../EncryptionService.java` (test `EncryptionServiceTest`); retention and erasure, see [DPDP page](dpdp-obligations.md); tenant erasure `core/tenancy/.../TenantDataExportService.java`, test `TenantErasureCompletenessTest` | Prompts sent to a third-party model leave the platform. Erasure does not reach provider copies |
| Environmental impacts | **Not covered** | none | Token cost is tracked; energy and emissions are not |
| Human-AI configuration | Partly | Approval gate with plain-language summaries (`core/guardrails/.../ToolConfirmationDescriber.java`, test `GuardedToolCallbackConfirmationSummaryTest`), autonomy tiers, AI disclosure on messaging channels (`ChannelSessionService#consumeAiDisclosurePrefix`, test `ChannelSessionServiceTest`) | No disclosure found on the web console or widget. No distinct-approver option for tenant admins |
| Information integrity | Partly | Citations and provenance (`CitationSpans`, `core/agents-turn/.../memory/MemoryProvenanceService.java`, test `MemoryProvenanceServiceTest`); write-time screen `core/knowledge/.../KnowledgeWriteScreen.java` (test `AddToKnowledgeBaseToolCallbackTest`) | No content-provenance marking (watermark, C2PA) on generated output |
| Information security | Partly | Injection judge `core/guardrails/.../SemanticGuardrailService.java` (`app.guardrails.implementation`, default `semantic`; test `SemanticGuardrailServiceTest`), taint layer (default `observe`), tool-argument policies `core/guardrails/.../toolargs/` (test `ToolArgPolicyEvaluatorTest`), SSRF guard, MCP origin validation (`core/mcpserver/.../McpOriginValidationFilter.java`, key `app.mcpserver.origin.mode` default `enforce`, test `McpOriginValidationFilterTest`). Full list: [OWASP mapping](../security/owasp-mapping.md) | Regex plus a judge; no structural separation of instructions and data |
| Intellectual property | **Not covered** | none | No copyright or licence screening of generated or ingested content. The licence scans cover software dependencies only |
| Obscene, degrading or abusive content | Partly | As dangerous content: `ModerationService`, `ToxicityGuardrail` | |
| Toxicity, bias and homogenisation | Partly | `ToxicityGuardrail` for toxicity | No bias or homogenisation measurement |
| Value chain and component integration | Partly | Signed images and SBOMs per release (SOC 2 map); `SkillSupplyChainService`; `McpToolPinGate`; A2A card verification | No model provenance; MCP tool pinning defaults to `warn` |

## Known gaps

- No impact assessment support (MAP 5).
- No bias, fairness or homogenisation evaluation (MEASURE 2).
- Taint tracking is advisory (`observe`) in the base profile; only the `personal` profile enforces.
- The turn journal and audit seals are off by default.
- No distinct-approver option for tenant admins.
- Knowledge collections had no per-collection access control list; per-collection access control
  is in progress on a branch, not yet released.
- A2A had no message signing; message signing is in progress on a branch, not yet released.
- No delegated-authority model for agents in general ([ADR-0027](../architecture/ADR-0027-agent-mandates-and-delegated-authority.md) is a proposal).
- Environmental impact, IP screening and CBRN-specific controls are not built.

## Not verified

- No control was exercised on a running stack; Covered/Partly mean code, configuration and unit tests.
- The AI RMF Playbook's suggested actions were not walked line by line; categories only.
- Whether a deployment turns on the opt-in controls is outside what the repository shows.
