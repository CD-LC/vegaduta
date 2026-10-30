# ISO/IEC 42001 evidence map

Where the controls in this repository line up against the AI management system (AIMS) standard
ISO/IEC 42001:2023, clauses 4-10 and Annex A. Read the [pack index](README.md) first: the status
legend, the method and the limits apply here, and **this is not a claim of conformity or
certification**. The project holds no ISO/IEC 42001 certificate and has had no audit.

ISO/IEC 42001 certifies an *organisation's management system*. Most of it is policy, roles,
reviews and records that a software product cannot contain. The honest result of mapping a
platform against it is therefore a short list of **technical controls that leave evidence an
AIMS can use** and a long list marked **Organisational**.

Paths are `core/<module>/.../Class.java`. Clause and control identifiers below are the published
structure of the standard; the standard is paid and its text was not consulted (see
[Method and limits](README.md#method-and-limits)).

## What the software cannot provide

These are the things an AIMS needs that no code in this repository supplies. They belong to the
organisation that runs the platform (**operator**) or configures agents on it (**deployer**).

- The AI policy, its approval by top management and its periodic review (5.2, A.2.2, A.2.4).
- Assigned roles, competence, awareness and training of people (5.3, 7.2, 7.3, A.3.2, A.4.6).
- The AI risk assessment and AI *system impact* assessment as methods, criteria and records
  (6.1.2, 6.1.4, 8.2, 8.4, A.5.2-A.5.5). The platform has no impact-assessment template or
  workflow. The [OWASP mapping](../security/owasp-mapping.md) is a threat view of the platform, not
  an impact assessment of a deployer's use case.
- Internal audit programme and management review (9.2, 9.3).
- Supplier assessment, contracts and customer obligations (A.10.2-A.10.4). No subprocessor list,
  DPA or model card ships with the project.
- The decision on intended use and prohibited use of each deployed agent (A.9.3, A.9.4), beyond
  recording the configuration.

## Clauses 4-10

| Clause | Requirement (short) | Status | Software evidence (path, key, test) | Note |
|---|---|---|---|---|
| 4.1-4.4 | Context, interested parties, scope, the AIMS itself | Organisational | none | The scope statement is the operator's. This pack and the [compliance posture page](../platform-documentation/security/compliance-posture.html) help scope what the *software* provides |
| 5.1 | Leadership and commitment | Organisational | none | |
| 5.2 | AI policy | Organisational | none | |
| 5.3 | Roles, responsibilities, authorities | Partly | Role and permission model: `core/security/.../SecurityConfig.java` (`PERM_*` matchers), `core/admin/.../DefaultPermissionBundles.java`; [authorization model](../security/authorization.md). Tests: `DefaultPermissionBundlesTest`, `TenantAssignableRolesTest`, `GranularPermissionConverterTest` | The software can enforce who may do what; it cannot assign accountable people. Config: roles live in Keycloak (`app.auth.mode`, default `keycloak`) |
| 6.1.1-6.1.3 | Actions for risk; AI risk assessment; AI risk treatment | Partly | Treatment controls exist per risk: see the OWASP mapping. Save-time risk check for dangerous tool combinations: `core/agents/.../LethalTrifectaDetector.java`; gate `app.tools.classification.confirmation-gate` (default `true`). Test: `LethalTrifectaDetectorTest` | The assessment itself (method, criteria, owner, acceptance) is organisational |
| 6.1.4 | AI system impact assessment | **Not covered** | none | No template, workflow or record in the product |
| 6.2 | AI objectives and planning to achieve them | Organisational | none | |
| 6.3 | Planning of changes | Partly | Versioned agent configuration with labelled promotion: `core/agents/.../version/AgentVersionService.java`, `AgentConfigSnapshot.java`. Tests: `AgentVersionPromotionGateTest`, `AgentConfigSnapshotCodecTest`, `VersionHashingTest`. Platform changes: [change management in the SOC 2 map](soc2-iso27001-evidence-map.md#cc8-change-management) | No change-approval workflow for the platform's own configuration (`application*.yml` is an operator edit) |
| 7.1 | Resources | Organisational | none | |
| 7.2, 7.3 | Competence, awareness | Organisational | none | Documentation helps (7.5) but is not training |
| 7.4 | Communication | Partly | Vulnerability intake and response targets: `SECURITY.md`. In-app issue intake: `core/audit/.../PlatformIssueService.java`; test `PlatformIssueServiceTest` | Internal and external communication plans are the operator's |
| 7.5 | Documented information | Partly | Documentation is versioned in git and built strictly: `.github/workflows/docs.yml` (`mkdocs build --strict`), `scripts/ci/docs-link-check.sh` (part of `make gates`) | Control of *the operator's* AIMS records is out of scope. Test: the link checker itself has no unit test; it is a CI gate |
| 8.1 | Operational planning and control | Partly | Runtime controls: guardrails (`core/guardrails/.../GuardedToolCallback.java`; test `GuardedToolCallbackGovernanceTest`), approvals (`core/approval/.../HumanApprovalGateService.java`; test `HumanApprovalGateServiceTest`), budgets (`app.guardrails.run-budget.usd`; test `AgentRunBudgetServiceTest`) | |
| 8.2 | AI risk assessment (operational) | Organisational | none | Repeat assessments at planned intervals are the operator's |
| 8.3 | AI risk treatment (operational) | Partly | As 6.1.3; plus fail-closed defaults: `app.guardrails.fail-mode` (default `closed`; test `ChatGuardrailGateFailModeTest`) | Taint and tool-pin controls default to advisory ([Known gaps](#known-gaps)) |
| 8.4 | AI system impact assessment (operational) | **Not covered** | none | As 6.1.4 |
| 9.1 | Monitoring, measurement, analysis, evaluation | Partly | Evaluation harness with datasets, judges and reliability statistics: `core/quality/.../EvalRunService.java`, `EvalReliabilityService.java`. Tests: `EvalRunServiceTest`, `EvalReliabilityServiceTest`. Operational metrics: [observability](../platform-documentation/operations/observability.html) | Evals are opt-in per agent; there is no platform-wide quality target |
| 9.2 | Internal audit | Organisational | none | CI gates are continuous *checks* (see the SOC 2 map), not an internal audit programme |
| 9.3 | Management review | Organisational | none | |
| 10.1 | Continual improvement | Partly | Feedback capture: `core/rl/.../FeedbackService.java`, `core/agents-turn/.../TurnFeedbackAccessGate.java`. Tests: `FeedbackServiceTest`, `TurnFeedbackAccessGateTest` | Whether feedback leads to improvement is a process |
| 10.2 | Nonconformity and corrective action | Partly | Defect and vulnerability intake (`SECURITY.md`, `PlatformIssueService`); regression gates in CI (see SOC 2 map); `CHANGELOG.md` | No corrective-action register |

## Annex A controls

Control ids are the published ones (A.2 to A.10). "Organisational" rows still list any evidence
the software can leave behind.

### A.2 Policies related to AI

| Control | Status | Evidence | Note |
|---|---|---|---|
| A.2.2 AI policy | Organisational | none | |
| A.2.3 Alignment with other policies | Organisational | none | |
| A.2.4 Review of the AI policy | Organisational | none | |

### A.3 Internal organisation

| Control | Status | Evidence | Note |
|---|---|---|---|
| A.3.2 AI roles and responsibilities | Organisational | Role model as clause 5.3 | |
| A.3.3 Reporting of concerns | Partly | Users can report AI content: `core/tenancy/.../aireport/AiContentReportService.java` (30 reports per user per hour, review queue for platform admins). Test: `AiContentReportServiceTest`. Vulnerability reports: `SECURITY.md` | Built for an app-store content policy, not as a general whistleblowing channel. Always on |

### A.4 Resources for AI systems

| Control | Status | Evidence | Note |
|---|---|---|---|
| A.4.2 Resource documentation | Partly | Generated configuration reference: `docs/engineering/CONFIG-REFERENCE.generated.md`; module graph `docs/architecture/core-module-graph.md` (freshness gated in `static-gates.yml`: "Module graph is current"); [deployment shapes](../deploy/DEPLOYMENT-SHAPES.md) | Documents the platform; an inventory of a deployer's agents is the console's agent list, not a controlled register |
| A.4.3 Data resources | Partly | Knowledge collections and documents: `core/knowledge/.../KnowledgeCollection.java`, `KnowledgeDocument.java`; test `KnowledgeCollectionServiceTest` | No data-resource register with owner, source and licence per collection. See A.7 |
| A.4.4 Tooling resources | Partly | Tool classification and registry: `core/agents-tools/.../AgentToolGovernance.java` (`decide`); MCP registry `core/mcp/.../McpRegistryService.java`; tests `McpRegistryServiceTest`, `McpToolPinServiceTest` | |
| A.4.5 System and computing resources | Partly | Spend and capacity controls: `core/agents-budget/.../SpendCapService.java` (test `SpendCapServiceTest`), `PerUserBudgetService.java` (test `PerUserBudgetServiceTest`) | Capacity planning is the operator's |
| A.4.6 Human resources | Organisational | none | |

### A.5 Assessing impacts of AI systems

| Control | Status | Evidence | Note |
|---|---|---|---|
| A.5.2 Impact assessment process | **Not covered** | none | |
| A.5.3 Documentation of impact assessments | **Not covered** | none | |
| A.5.4 Impact on individuals or groups | **Not covered** | none | Moderation policy packs (`core/guardrails/.../moderation/ModerationPolicyService.java`, test `ModerationPolicyServiceTest`) reduce some harms; they are not an assessment |
| A.5.5 Societal impacts | **Not covered** | none | |

### A.6 AI system lifecycle

| Control | Status | Evidence | Note |
|---|---|---|---|
| A.6.1.2 Objectives for responsible development | Organisational | none | |
| A.6.1.3 Processes for responsible design and development | Partly | Contribution rules and gates: `AGENTS.md`, `CONTRIBUTING.md`, `scripts/ci/*` run by `make gates`; architecture baselines `core/app/src/test/resources/archunit_store` (tests `StructureRulesTest`, `ModuleBoundaryRulesTest`) | Covers the platform's own development. A deployer's agent-building process is theirs |
| A.6.2.2 Requirements and specification | Organisational | Agent definition (system prompt, model, tool allow-list) is recorded: `core/agents/.../version/AgentConfigSnapshot.java` | The intended purpose a specification states is the deployer's |
| A.6.2.3 Design and development documentation | Partly | ADRs in `docs/architecture/ADR-*.md`; `docs/architecture/STRUCTURAL-AUDIT-2026-10-14.md` | |
| A.6.2.4 Verification and validation | Partly | Eval gate that blocks promotion to PRODUCTION without a qualifying passing run: `core/quality/.../gate/EvalGateService.java`, bridged in `AgentVersionService#setLabel`; keys `app.quality.gate.pass-hat-k.enabled` (default `true`), `app.quality.gate.pass-hat-k.min-lower-bound` (default `0.5`). Tests: `EvalGateServiceTest`, `AgentVersionPromotionGateTest`. RAG metrics: `core/knowledge/.../rag/RagEvalService.java`, tests `RagEvalServiceTest`, `RagEvalMetricsTest`. Memory: `MemoryEvalHarnessTest` | The gate is per-agent and opt-in (`AgentEvalGate.enabled`). Nothing forces a deployer to define a dataset |
| A.6.2.5 Deployment | Partly | Promotion labels (`AgentVersionService`), release gating on `CI OK` and `CodeQL OK` before a platform release: `.github/workflows/release.yml` (job `verify`) | |
| A.6.2.6 Operation and monitoring | Partly | Run budgets, step caps, circuit breaker, spend caps: `core/agents-tools/.../ToolCircuitBreakerService.java` (test `ToolCircuitBreakerServiceTest`), `core/agents/.../runbudget/AgentRunBudgetService.java`; kill switches: `core/tenancy/.../TenantEmergencyControlService.java` (platform and per-tenant; test `TenantEmergencyControlServiceTest`), read by `TenantStatusGuardImpl` (test `TenantStatusGuardImplTest`) | No behavioural anomaly detection on agents |
| A.6.2.7 Technical documentation | Partly | `docs/` (built by mkdocs), `docs/platform-documentation/`, OpenAPI `docs/api/openapi.json` (freshness test `OpenApiSpecFreshnessTest` in `core/app`) | |
| A.6.2.8 Recording of event logs | Partly | Audit log (always on): `core/audit/.../AuditLogService.java`, test `AuditLogServiceTest`. Tamper-evident seals: `core/audit/.../seal/AuditSealService.java`, `app.audit.seal.enabled` (**default false**), tests `AuditSealServiceTest`, `AuditSealVerifierTest`, offline verifier `scripts/verify-audit-export.py`. Replayable turn record: `core/agents-turn/.../TurnJournalService.java`, `app.turn-journal.enabled` (**default false**), tests `TurnJournalServiceTest`, `TurnJournalChainTest` | Two of the three log layers are off until an operator enables them and, for seals, pins a key |

### A.7 Data for AI systems

| Control | Status | Evidence | Note |
|---|---|---|---|
| A.7.2 Data for development and enhancement | Partly | Feedback capture (thumbs, edits, resolution) with reward weights: `core/rl/.../feedback/FeedbackService.java`, keys `app.feedback.*`; test `FeedbackServiceTest`. The platform does not train foundation models | Whether and how a deployer uses feedback for optimisation (`core/rl`, `services/rl-trainer`) was not traced for gating. Training data for the integrated third-party models is the model provider's |
| A.7.3 Acquisition of data | Partly | Ingestion paths: `core/knowledge/.../KnowledgeIngestionService.java`, `KnowledgeUrlIngester` (test `KnowledgeUrlIngesterTest`); outbound URL guard `core/security/.../net/SsrfGuard.java`, `app.security.outbound.egress-allow-list` (test `SsrfGuardEgressAllowListTest`) | No record of lawful basis or licence for ingested documents |
| A.7.4 Quality of data | Partly | Chunking and extraction tests: `ChunkerTest`, `InProcessTextExtractorTest`; duplicate detection `KnowledgeDuplicateDocumentException`; retrieval quality: `KnowledgeSearchRagQualityTest` | No data-quality thresholds or reports |
| A.7.5 Data provenance | Partly | Memory provenance: `core/agents-turn/.../memory/MemoryProvenanceService.java` (test `MemoryProvenanceServiceTest`); answer citations with spans: `core/knowledge/.../CitationSpans.java` (test `KnowledgeSearchSpanCitationsTest`) | No provenance for documents before ingestion |
| A.7.6 Data preparation | Partly | Write-time screen on agent-written knowledge: `core/knowledge/.../KnowledgeWriteScreen.java` (always on; test `AddToKnowledgeBaseToolCallbackTest`); admission caps `core/agents-turn/.../memory/OwnFactAdmission.java` (test `OwnFactAdmissionTest`) | Documents uploaded by a human are trusted by design and not scanned. Knowledge collections have no per-collection access control list ([Known gaps](#known-gaps)) |

### A.8 Information for interested parties

| Control | Status | Evidence | Note |
|---|---|---|---|
| A.8.2 System documentation and information for users | Partly | Public docs site (`mkdocs.yml`, `.github/workflows/docs.yml`); in-product plain-language confirmation text for gated actions: `core/guardrails/.../ToolConfirmationDescriber.java`, test `GuardedToolCallbackConfirmationSummaryTest`; turn inspector `web/app/components/turns/TurnInspector.tsx` (design: [ADR-0021](../architecture/ADR-0021-turn-inspector.md)) | No model cards and no limitations statement per agent |
| A.8.3 External reporting | Partly | `SECURITY.md` (private vulnerability reporting, response targets) | Regulatory reporting is the operator's |
| A.8.4 Communication of incidents | Partly | [Breach runbook](../operations/breach-runbook.md); signed audit export for a time range: `core/app/.../common/privacy/AuditExportService.java` (test `AuditExportCsvTest`) | The runbook is a template; notification decisions are the operator's |
| A.8.5 Information for interested parties | Partly | One-time AI disclosure on inbound messaging channels: `core/omnichannel/.../session/ChannelSessionService.java` (`consumeAiDisclosurePrefix`), test `ChannelSessionServiceTest` | Channels only. Not found on the web console or widget |

### A.9 Use of AI systems

| Control | Status | Evidence | Note |
|---|---|---|---|
| A.9.2 Processes for responsible use | Partly | Human approval gate, with self-approval blocked for non-admins and admin-only decisions on gated tool calls: `core/approval/.../ApprovalController.java`, `HumanApprovalGateService.java`; tests `ApprovalControllerTest`, `HumanApprovalGateServiceTest`, `ApprovalReplayGrantTest`. Autonomy tiers: `core/agents/.../core/AgentAutonomyTier.java` | **No distinct-approver option for tenant admins** ([Known gaps](#known-gaps)). Fully autonomous tiers skip the gate by design |
| A.9.3 Objectives for responsible use | Organisational | none | |
| A.9.4 Intended use | Partly | Per-agent tool allow-list `Agent.allowedToolNames` (an empty list means unrestricted, documented in `AgentIntegrationToolResolver`); guardrail selection `app.agents.default-guardrails` (default `jailbreak`); test `AgentChatServiceContentGuardrailTest` | The product records configuration; it does not record or enforce an *intended purpose* statement |

### A.10 Third-party and customer relationships

| Control | Status | Evidence | Note |
|---|---|---|---|
| A.10.2 Allocating responsibilities | Organisational | The [role split in the pack index](README.md#who-is-responsible-for-what) | |
| A.10.3 Suppliers | Partly | Dependency and licence controls: `.github/dependabot.yml`, `scripts/ci/license-allowlist.txt`, `scripts/ci/license-drift-check.py`, `.github/workflows/license-scan.yml`, job `dependency-review` in `ci.yml`. Provider list: `core/providers/.../ProviderName.java`. Release SBOM: see the SOC 2 map | No supplier assessment, subprocessor list, model card or DPA. No model-weight provenance control |
| A.10.4 Customers | Organisational | none | |

## Known gaps

Repeated from the [pack index](README.md#known-gaps), restricted to those this page relies on.

- No distinct-approver option for tenant admins (A.9.2).
- Taint tracking is advisory in the base profile (`app.agents.taint.mode` = `observe`); the
  `personal` profile enforces (8.3).
- Knowledge collections had no per-collection access control list. Per-collection access control is
  in progress on a branch, not yet released (A.7.6).
- Audit seals and the turn journal are off by default (A.6.2.8).
- No AI system impact assessment support (6.1.4, 8.4, A.5.x).
- No model cards, subprocessor list or DPAs (A.8.2, A.10.3).
- No delegated-authority model for agents in general: [ADR-0027](../architecture/ADR-0027-agent-mandates-and-delegated-authority.md) is a proposal (A.9.2, 8.1).
- Coverage is report-only in CI (A.6.2.4).

## Not verified

- No control was exercised on a running stack.
- Annex A control titles are from the published structure of the standard, not from its text.
- Whether a given deployment has enabled the opt-in controls is outside what the repository shows.
