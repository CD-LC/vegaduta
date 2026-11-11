# ISO/IEC 42001 and NIST AI RMF: a control mapping

> **This is a mapping, not a certification or an attestation.** ISO/IEC 42001 certifies an
> *organisation's AI management system*, not a product; no software can make an organisation
> "42001 compliant". The NIST AI RMF is voluntary guidance with no conformity scheme. This page
> says which platform capabilities can serve as *evidence or tooling* for an organisation that adopts
> either. It was written from the author's knowledge of both documents at **function and clause
> level only**: the standard (paid) and the NIST AI 100-1 text were **not opened** for this page, and
> the Annex A control wording is paraphrased, so confirm clause numbers against your copy.
> Not legal advice. Evidence was checked in this working tree on 2026-11-03; nothing was run on a stack.

Status words as in [eu-ai-act-mapping.md](eu-ai-act-mapping.md): **Supports**, **Partly**, **Gap**, **Org** (an organisational
duty the platform cannot perform). Permissive defaults referred to are tabulated in
[owasp-mapping.md](../security/owasp-mapping.md#defaults-that-are-permissive).

## ISO/IEC 42001:2023

Management-system clauses 4 to 10 are the organisation's work (context, leadership, planning,
support, operation, performance evaluation, improvement). The platform contributes evidence mostly to
clause 8 (operation) and 9 (evaluation). Annex A topic areas follow; control numbers are given at
the A.x level as I recall them.

| Annex A area | What it asks (paraphrase) | Platform evidence | Status | Gap |
|---|---|---|---|---|
| A.2 AI policies | Documented policy aligned with objectives | None in product. [AGENTS.md](../../ENGINEERING-RULES.md) and `docs/security/*` are developer-facing | Org | A tenant-facing AI policy template does not exist |
| A.3 Internal organisation | Roles and responsibilities, reporting of concerns | Permission model [authorization.md](../security/authorization.md) (`PERM_*`); approval roles `ApprovalDecisionAuthorizer` | Partly | No named "AI system owner" role in the product |
| A.4 Resources for AI systems | Document resources, tooling, compute | SBOM and release provenance `.github/workflows/release.yml` (as cited in OWASP ASI04); tool and MCP registry `core/mcp/.../McpRegistryService` | Partly | No AI-resource inventory report per tenant |
| A.5 Assessing impacts | Impact assessment process for individuals and society | None found | Gap | The deployer must run it; no template or workflow (cf. FRIA, EU AI Act Art. 27) |
| A.6 AI system life cycle | Design, verification, validation, deployment, operation, change control | Agent versioning and restore (version history noted in `trifecta` save path, `application.yml`), eval services `RagEvalService`, `AgentLabRunService`, attestation `core/agents/.../attestation/AgentAttestationService.java`, Flyway discipline (AGENTS.md rule 3) | Partly | No enforced pre-deploy eval gate for an agent; attestation has little test coverage (OWASP ASI10) |
| A.7 Data for AI systems | Data quality, provenance, preparation | Memory provenance `MemoryProvenanceService` (derived from `tool_call_log`, best-effort), KB write screen `KnowledgeWriteScreen`, DPDP retention and erasure ([dpdp-obligations.md](dpdp-obligations.md)) | Partly | Provenance is a lookup, not a stored lineage; no data-quality checks; Phase 0 package C changes this |
| A.8 Information for interested parties | Documentation, incident communication, user information | Channel AI disclosure (migration V34, `ChannelSessionService.consumeAiDisclosurePrefix`), confirmation text `ToolConfirmationDescriber`, breach handling in DPDP page 4.7 | Partly | See EU AI Act Art. 50 coverage gaps; no external incident-reporting workflow for AI harms |
| A.9 Use of AI systems | Responsible use, intended use, human oversight | Autonomy tiers `AgentAutonomyTier`, approval gate `HumanApprovalGateService`, per-agent allow-list `Agent.allowedToolNames`, budgets (`app.guardrails.run-budget.usd`, `max-tool-calls-per-turn`) | Partly | Defaults permissive: SEMI_AUTONOMOUS default tier, approval ttl 0, exempt-tool list, taint observe |
| A.10 Third-party and customer relationships | Supplier and customer management, allocating responsibility | Skill supply chain `app.skills.supply-chain.*`, MCP review status `McpReviewStatus`, tool pinning (`warn` by default), A2A signed-card verification (`app.a2a.client.require-signed-card=true`), provider keys encrypted `core/crypto` | Partly | No model-provider due-diligence record or model provenance (OWASP LLM03) |

Clauses with product evidence worth collecting for an auditor:

| Clause | Evidence an operator can export |
|---|---|
| 8.1 Operational planning and control | `application.yml` effective configuration, [configuration.md](../configuration.md), the permissive-defaults table |
| 9.1 Monitoring and measurement | Metrics `agent.tool.calls`, `agent.tool.errors`, `agent.tool.classification.*` (`AgentToolGovernance`, `GuardedToolCallback`); sampled quality judge `app.llm.judge.sample-rate` |
| 9.2 Internal audit | Audit log `AuditLogService`; sealed audit exports (off until `app.audit.seal.enabled=true` and a key is pinned) with `scripts/verify-audit-export.py` |
| 10.2 Nonconformity and corrective action | Platform issues `PlatformIssueService`; MCP change events (`mcp.tools.changed`) |

## NIST AI RMF 1.0 (AI 100-1)

| Function | Category-level topic | Platform evidence | Status | Gap |
|---|---|---|---|---|
| GOVERN | Policies, accountability, culture, third-party risk | Permission model, approval roles, supplier controls as in A.10 above; [owasp-mapping.md](../security/owasp-mapping.md) as a risk statement | Partly | Policy, accountability and culture are organisational; no in-product risk-tolerance setting beyond the config keys |
| MAP | Context established, categorisation, risks and impacts identified | Lethal-trifecta analysis `LethalTrifectaDetector` (private data, untrusted content, egress), tool classification registry `ToolClassificationRegistry`, OWASP lists mapping; this folder's [learning-agents threat model](../security/threat-model-learning-agents.md) | Partly | No impact assessment workflow; trifecta check is `warn` by default and does not cover Auto-select agents by default (`trifecta-analyse-auto-select=false`) |
| MEASURE | Metrics, testing, evaluation, tracking | Eval services (`RagEvalService`, `AgentLabRunService`), answer consistency and reflexion (`AnswerConsistencyChecker`, `ReflexionService`), judges `app.guardrails.judge.*`, guardrail unit tests listed in the OWASP page, simulations (`application.yml` near `attack-report`) | Partly | Measurement is opt-in and sampled; no baseline or drift metric; adversarial suite is Phase 0 work not yet shipped |
| MANAGE | Risks prioritised, treated, monitored; incidents responded to | Guardrails with `fail-mode=closed`, circuit breaker, budgets, step limits, human approval (parks the turn), MCP pin `enforce`, taint `enforce` (opt-in), audit trail | Partly | Treatments exist, but several ship in observe or warn mode; no incident-response runbook specific to AI harms was looked for (not verified) |

## Gaps, in one list

1. No impact-assessment workflow (ISO A.5, NIST MAP).
2. Permissive defaults mean the *capability* exists but is not the shipped *posture*
   ([ADR-0027](../architecture/ADR-0027-strict-security-posture.md)).
3. Evidence production is manual: audit seals and the turn journal are off by default; no one-click
   evidence pack.
4. No pre-deployment eval gate and no drift measurement (ISO A.6, NIST MEASURE).
5. Data lineage is best-effort (ISO A.7).
6. No model/provider provenance record (ISO A.10, OWASP LLM03).

## Not verified

The standard and NIST text; clause numbers; control wording; every runtime behaviour; whether an
incident runbook for AI harms exists elsewhere in `docs/`.
