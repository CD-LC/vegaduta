# EU AI Act evidence map

Where the controls in this repository line up against selected articles of Regulation (EU)
2024/1689 (the AI Act). Read the [pack index](README.md) first for the status legend and limits.

> **This is not legal advice and not a compliance claim.** VegaDūta is a general-purpose agent
> platform. Whether any system built on it is regulated, and under which risk class, depends on
> what the *deployer* uses it for. The software has not been assessed by a notified body, carries
> no CE marking and no conformity declaration, and no page in this pack says that a deployment is
> "AI Act compliant". Application dates and any later amendments to the Regulation were not
> checked for this pack: confirm which obligations apply on the day you rely on it.

## Operator versus deployer: who owns what

The Act allocates duties by role, and the role follows the use, not the software.

| Role | Who, for this platform | What follows |
|---|---|---|
| **Provider** of an AI system (Art. 3(3)) | The party that develops an AI system, or has it developed, and places it on the market or puts it into service under its own name or trademark. A deployer that builds an agent on VegaDūta and offers it to others under its own name is the provider of *that* system | Provider duties (Arts. 9-17 for high-risk systems, Art. 50(1)-(2)) attach to that party, using the platform as a component |
| **Deployer** (Art. 3(4)) | The organisation that uses an AI system under its authority: the tenant that configures an agent and puts it in front of its staff, customers or the public | Art. 26 duties; Art. 50(3)-(4); Art. 4 AI literacy; Art. 27 where applicable |
| **Platform operator** | Whoever runs the VegaDūta deployment. Often the same organisation as the deployer; for a hosted service it is the host | Operates the infrastructure the controls rest on: keys, retention, backups, enabling the opt-in controls on this page |
| **Software publisher** | The VegaDūta project | Ships code and documentation. Whether the publisher is itself a "provider" of an AI system is a legal question this page does not answer. The core is under the Business Source License 1.1 (`LICENSE`); the SDKs under `sdk/` are Apache-2.0. The Art. 2(12) exemption for free and open-source AI systems is narrow (it does not cover prohibited practices or Art. 50 systems, among other limits) and is **not** assumed here |
| **GPAI model provider** | The vendor of each third-party model the platform calls (the list is `core/providers/.../ProviderName.java`) | Arts. 53 and 55 duties sit with them. The platform does not train or distribute a general-purpose model; `services/rl-trainer` optimises prompts and preferences through an LLM API and has no weight-training path |

**Risk classification belongs to the deployer's use case.** The same platform can host a
customer-FAQ bot (limited risk, Art. 50 transparency), an agent that screens job applicants
(Annex III, high-risk) or something prohibited by Art. 5. Nothing in the product classifies a
deployer's system; the tool classification in `core/agents-tools/.../AgentToolGovernance.java`
classifies *tool capabilities* (risk, side effects, untrusted input), which is a different
question. A deployer must decide the class, record the reasoning and, if high-risk, carry the
provider and deployer duties below. The mapping lists what the platform gives them to do that
with.

## Articles

"Platform feature" rows say what a deployer can lean on. For a high-risk system the duty itself
stays with the party named in the article.

### Art. 9 Risk management system (high-risk systems; provider)

| Requirement (short) | Status | Evidence (path, key, test) | Note |
|---|---|---|---|
| A continuous, iterative risk management process over the lifecycle; identify, estimate, evaluate, treat risks; test | Partly (tooling) / Organisational (the system) | Risk-treatment controls: `core/guardrails/.../GuardedToolCallback.java` (test `GuardedToolCallbackGovernanceTest`), `core/approval/.../HumanApprovalGateService.java` (test `HumanApprovalGateServiceTest`), fail-closed `app.guardrails.fail-mode` (test `ChatGuardrailGateFailModeTest`). Testing: `core/quality/.../gate/EvalGateService.java` (test `EvalGateServiceTest`), key `app.quality.gate.pass-hat-k.enabled`. Threat catalogue: [OWASP mapping](../security/owasp-mapping.md) | The risk assessment, its acceptance criteria and its repetition are the provider's. No risk register in the product. No impact assessment support |

### Art. 10 Data and data governance (high-risk systems; provider)

| Requirement (short) | Status | Evidence | Note |
|---|---|---|---|
| Governance of training, validation and test data; relevance, representativeness, bias examination | Partly / **Not covered** for bias | The platform does not train foundation models. Retrieval data: `core/knowledge/.../KnowledgeIngestionService.java`, write-time screen `KnowledgeWriteScreen.java` (test `AddToKnowledgeBaseToolCallbackTest`), memory provenance `core/agents-turn/.../memory/MemoryProvenanceService.java` (test `MemoryProvenanceServiceTest`), retrieval quality `RagEvalService` (test `RagEvalServiceTest`) | No bias examination tooling. No data-resource register (owner, source, licence). Knowledge collections have no per-collection access control list; per-collection access control is in progress on a branch, not yet released. Documents a person uploads are trusted by design and are not scanned |

### Art. 12 Record-keeping (high-risk systems; provider) and Art. 26(6) log retention (deployer)

| Requirement (short) | Status | Evidence | Note |
|---|---|---|---|
| Automatic recording of events over the system's lifetime, enabling traceability and post-market monitoring | Partly | Audit log, always on: `core/audit/.../AuditLogService.java` (test `AuditLogServiceTest`); append-only and excluded from retention purges (`app.retention.purge` comment in `application.yml`). Tamper-evident Ed25519 seals: `core/audit/.../seal/AuditSealService.java`, key `app.audit.seal.enabled` (**default `false`**; needs a pinned key `APP_A2A_SIGNING_PRIVATE_KEY`), tests `AuditSealServiceTest`, `AuditSealVerifierTest`; offline verifier `scripts/verify-audit-export.py`. Replayable turn record: `core/agents-turn/.../TurnJournalService.java`, key `app.turn-journal.enabled` (**default `false`**), tests `TurnJournalServiceTest`, `TurnJournalChainTest`, `TurnReplayTest`. Tool effects: `EffectJournal` (test `EffectJournalTest`) | Two of three layers are off by default. **Retention:** the turn journal purges after `app.turn-journal.retention-days` = 90 by default. Art. 26(6) asks deployers of high-risk systems to keep logs under their control for at least six months (unless other law says otherwise); a deployer relying on the turn journal must set 0 (keep) or a longer period |

### Art. 13 Transparency and provision of information to deployers (high-risk systems; provider)

| Requirement (short) | Status | Evidence | Note |
|---|---|---|---|
| Understandable operation; instructions for use with purpose, accuracy metrics, limitations, oversight measures, log access | Partly | Turn inspector `web/app/components/turns/TurnInspector.tsx` ([ADR-0021](../architecture/ADR-0021-turn-inspector.md)); journal API `GET /api/agents/{agentId}/turn-journal` (`core/agents-turn/.../TurnJournalController.java`, test `TurnJournalControllerTest`); docs site | No per-agent "instructions for use" artifact, no declared accuracy metrics, no model cards for integrated third-party models. A provider must write these |

### Art. 14 Human oversight (high-risk systems; provider design, deployer operation)

| Requirement (short) | Status | Evidence | Note |
|---|---|---|---|
| Measures that let natural persons understand, monitor, interpret, override, intervene and stop the system; guard against automation bias | Partly | Approval gate parks a consequential tool call for a person: `core/approval/.../HumanApprovalGateService.java`, `ApprovalController.java` (admin-only decisions on gated tool calls, self-approval blocked for non-admins); tests `ApprovalControllerTest`, `HumanApprovalGateServiceTest`, `ApprovalReplayGrantTest`. Plain-language confirmation text against automation bias: `core/guardrails/.../ToolConfirmationDescriber.java` (test `GuardedToolCallbackConfirmationSummaryTest`). Autonomy tiers: `core/agents/.../core/AgentAutonomyTier.java`. Step cap and run budget (`app.guardrails.run-budget.usd`). Stop: platform and per-tenant kill switch `core/tenancy/.../TenantEmergencyControlService.java` (test `TenantEmergencyControlServiceTest`) | **No distinct-approver option for tenant admins**: an admin can approve a request they raised (single-admin tenants need it; there is no setting to forbid it). The kill switch is platform-admin-only, not a tenant self-service stop. Fully autonomous tiers skip the gate by design. Taint (`app.agents.taint.mode`, default `observe`) only records what *would* have needed approval, outside the `personal` profile. No delegated-authority model: see [ADR-0027](../architecture/ADR-0027-agent-mandates-and-delegated-authority.md) (proposal) |

### Art. 15 Accuracy, robustness and cybersecurity (high-risk systems; provider)

| Requirement (short) | Status | Evidence | Note |
|---|---|---|---|
| Appropriate accuracy, resilience to errors and faults, resilience to attempts to alter use or performance (including data poisoning, adversarial inputs) | Partly | Accuracy measurement: `core/quality/.../impl/FaithfulnessEvaluator.java` (test `FaithfulnessEvaluatorTest`), `EvalReliabilityService` (test `EvalReliabilityServiceTest`). Robustness: circuit breaker `core/agents-tools/.../ToolCircuitBreakerService.java` (test `ToolCircuitBreakerServiceTest`), idempotent replay `EffectJournal`, step journal for resumable job turns (`app.agents.jobs` journal keys; test `AgentJobStepJournalSafetyTest`). Cybersecurity: injection judge `SemanticGuardrailService` (`app.guardrails.implementation` default `semantic`; test `SemanticGuardrailServiceTest`), tool-argument policies (test `ToolArgPolicyEvaluatorTest`), SSRF guard (test `SsrfGuardEgressAllowListTest`), MCP and A2A controls ([conformance page](mcp-a2a-conformance.md)). Full list: [OWASP mapping](../security/owasp-mapping.md) | **Workflow execution is not durable by default**: `app.workflow.execution-engine` = `legacy` (in-process, a crashed run is reaped FAILED); `temporal` is opt-in. Injection defence is regex plus a judge. No declared accuracy levels. Coverage is report-only in CI |

### Art. 26 Obligations of deployers of high-risk systems

| Requirement (short) | Status | Evidence | Note |
|---|---|---|---|
| Use per instructions; assign human oversight to competent, trained persons; ensure input data is relevant; monitor operation; keep logs; inform workers; inform affected persons; DPIA/FRIA inputs | Organisational, with partial tooling | Roles and permissions (`core/security/.../SecurityConfig.java`, `core/admin/.../DefaultPermissionBundles.java`; tests `DefaultPermissionBundlesTest`, `TenantAssignableRolesTest`); approvals as Art. 14; monitoring and audit as Art. 12; audit export for a time range `core/app/.../common/privacy/AuditExportService.java` (test `AuditExportCsvTest`) | Assigning and training the oversight persons, informing workers and affected people, and the FRIA are the deployer's. Log retention default: see Art. 12 |

### Art. 50 Transparency obligations for certain AI systems

| Paragraph | Requirement (short) | Status | Evidence | Note |
|---|---|---|---|---|
| 50(1) | Persons interacting with an AI system are informed, unless obvious (provider) | Partly | One-time notice "You're chatting with an AI assistant, not a human." on inbound messaging channels: `core/omnichannel/.../session/ChannelSessionService.java` (`consumeAiDisclosurePrefix`), called by each channel adapter, test `ChannelSessionServiceTest`. Always on; text is fixed English | **No equivalent found on the web console or embeddable widget.** Not configurable or localised |
| 50(2) | Synthetic audio, image, video and text outputs are marked in a machine-readable format (provider) | **Not covered** | none | No watermarking, no provenance metadata (such as C2PA) on generated output |
| 50(3) | Deployers of emotion-recognition or biometric categorisation systems inform exposed persons | Organisational | none | Not a platform feature; the deployer's use case |
| 50(4) | Deployers of deepfakes, and of AI-generated text published to inform the public on matters of public interest, disclose it | Organisational | none | |
| 50(5) | Information given clearly at first interaction or exposure | Partly | As 50(1): at first reply per channel session | |

### General-purpose AI models (Chapter V; Arts. 51-56)

The platform integrates third-party models; it is a *downstream* party. What it gives an operator
or deployer:

| Topic | Status | Evidence | Note |
|---|---|---|---|
| Know which model ran | Partly | Model and provider are part of each agent version (`core/agents/.../version/AgentConfigSnapshot.java`, test `AgentConfigSnapshotCodecTest`); per-call record `core/chat/.../LlmCallLog.java` | |
| Receive the GPAI provider's information (Art. 53(1)(b)) | **Not covered** | none | No model cards, no provider documentation, no record of which provider documentation a deployer obtained. Operator task |
| Copyright policy and training-data summary (Arts. 53(1)(c)-(d)) | Not applicable to the platform | none | Duties of the model provider. The platform does not train or distribute a general-purpose model |
| Systemic-risk obligations (Art. 55) | Not applicable to the platform | none | Duties of the provider of a systemic-risk model |
| Becoming a GPAI provider by modifying a model | Not applicable today | `services/rl-trainer` has no weight-training dependency | A deployer that fine-tunes or distributes a modified model outside the platform may take on provider duties. Check with counsel |
| Choosing and switching providers | Partly | `core/providers/.../ProviderName.java`; fallback providers with effect-journal-aware replay (`app.chat.resilience.replay-after-journaled-effects` default `true`); local models: [local models](../local-models.md) | |

### Other provisions that touch a deployer of this platform

| Provision | Status | Note |
|---|---|---|
| Art. 4 AI literacy | Organisational | Documentation helps; training is the organisation's |
| Art. 5 Prohibited practices | Organisational | No platform control screens a deployer's use case for prohibited practices. Moderation packs (`ModerationPolicyService`, test `ModerationPolicyServiceTest`) address content, not purpose |
| Art. 27 Fundamental rights impact assessment | **Not covered** | No FRIA template or workflow |
| Arts. 72-73 Post-market monitoring, serious-incident reporting | Organisational, with partial tooling | Audit export and the [breach runbook](../operations/breach-runbook.md) leave evidence; reporting is the provider's and deployer's |

## Known gaps

- No distinct-approver option for tenant admins (Art. 14).
- Taint tracking is advisory (`observe`) in the base profile; only the `personal` profile enforces (Arts. 14, 15).
- Audit seals and the turn journal are off by default; the turn journal's default retention (90 days) is below the six months in Art. 26(6) (Art. 12).
- Knowledge collections had no per-collection access control list; per-collection access control is in progress on a branch, not yet released (Art. 10).
- A2A had no message signing; message signing is in progress on a branch, not yet released.
- Workflow execution is not durable by default (Art. 15).
- No AI disclosure on the web console or widget; no machine-readable marking of synthetic output (Art. 50).
- No model cards or GPAI provider documentation, no FRIA or impact assessment support, no bias examination tooling.
- Coverage is report-only in CI.

## Not verified

- No control was exercised on a running stack.
- The Regulation's text was not reproduced; article numbers and paragraph headings are cited from
  its published structure. Application dates, delegated acts, guidelines and harmonised standards
  were not checked.
- Whether any particular deployment is high-risk, limited-risk or out of scope.
- Whether the software publisher is a provider under the Act, and the reach of Art. 2(12) to a
  Business Source License work.
