# EU AI Act: where the platform's controls line up

> **This is a mapping, not a certification.** It is engineering's reading of which platform controls
> are relevant to which Articles of Regulation (EU) 2024/1689, with an honest status for each. It is
> **not legal advice**, does not say that VegaDūta or any deployment is "compliant", and has not been
> reviewed by counsel or by a notified body. Whether any obligation applies depends on what a deployer
> builds with the platform (the risk class of the use case, and whether the deployer is a provider or a
> deployer in the Act's sense). The Articles were **not re-read from the Official Journal for this
> page**; the summaries of Articles 9, 10, 12, 13, 14, 15 and 50 below are from the author's knowledge
> of the text and must be confirmed against it by counsel. Anything not backed by a file or key
> opened for this page is marked **not verified**.

Model: [OWASP mapping](../security/owasp-mapping.md) (same honesty rules) and the sister page
[DPDP obligations](dpdp-obligations.md). Evidence checked on 2026-11-03 in this working tree; nothing was run on a stack.

## How to read the status

| Status | Meaning |
|---|---|
| **Supports** | a control exists in code, with a test I found, and is on by default |
| **Partly** | a control exists but is off or permissive by default, untested, or covers only part of the Article's intent |
| **Gap** | no control found; a neighbouring one may be named |
| **Deployer** | the obligation lands on the organisation using the platform; the platform can only provide tooling |

Defaults referred to are in `core/app-config/src/main/resources/application.yml`. The permissive ones are
collected in [the OWASP page](../security/owasp-mapping.md#defaults-that-are-permissive).

## Scope first

Articles 9 to 15 apply to **high-risk AI systems**. A general-purpose agent platform is not high-risk
by itself; a deployer's agent becomes high-risk if used for an Annex III purpose (hiring, credit,
education assessment and so on). Article 50 (transparency) applies to certain systems regardless of
risk class. The platform's job is to make the Articles *achievable*, and this page records how far
it does. Classification of any given deployment is not attempted here.

## Mapping

| Article | Obligation in one line (to be confirmed by counsel) | Platform controls (evidence) | Status | Gap or caveat |
|---|---|---|---|---|
| 9 Risk management | A continuous, documented risk-management process across the lifecycle, including testing | Threat mapping to OWASP lists: [owasp-mapping.md](../security/owasp-mapping.md). Pre-save lethal-trifecta analysis `core/agents/.../tools/classification/LethalTrifectaDetector.java`, `app.tools.classification.trifecta-enforcement`. Tool classification and confirmation gate `AgentToolGovernance`, `app.tools.classification.*`. Step and USD budgets `app.guardrails.max-tool-calls-per-turn`, `app.guardrails.run-budget.usd`. Eval services `core/knowledge/.../rag/RagEvalService.java`, `core/agents-studio/.../lab/AgentLabRunService.java` (existence verified, assertions not audited) | Partly | The OWASP mapping is a point-in-time review, not a managed process with owners and cadence. The trifecta check defaults to `warn`, taint to `observe`, unclassified tools to `allow`. No residual-risk register. Process documentation is a deployer task |
| 10 Data and data governance | Training, validation and testing data are relevant, representative and examined for bias; governance practices | The platform does not train models; it calls provider models and retrieves from tenant knowledge. Write-time screen on knowledge writes `core/knowledge/.../KnowledgeWriteScreen.java` (regex tripwire). Per-subject memory admission caps `core/agents-turn/.../memory/OwnFactAdmission.java`. Tenant isolation by schema (AGENTS.md rule 8; `core/tenancy`). Personal-data handling: [dpdp-obligations.md](dpdp-obligations.md) | Gap (for training data) / Partly (for retrieval data) | Art. 10 is aimed at training/validation data of the provider of the high-risk system. For RAG and memory the platform has no bias or representativeness check, and uploaded documents are trusted by design (OWASP page, LLM04). No dataset documentation feature was found |
| 12 Record-keeping (logging) | Automatic recording of events over the system's lifetime, enabling traceability and monitoring | Audit log `core/audit/.../AuditLogService.java` (append-only `audit_log_entry`; retention purge deliberately skips it, `application.yml` `app.retention`). Tamper-evident seals `core/audit/.../seal/AuditSealService.java`, sweep `core/platform/.../auditseal/AuditSealSweep.java`, offline verifier `scripts/verify-audit-export.py`; tests `AuditSealServiceTest`, `AuditSealSweepTest`. Tool-call log (`tool_call_log`, `LoggingToolCallback`). Turn journal, hash-chained: [ADR-0020](../architecture/ADR-0020-turn-journal.md) | Partly | **Seals are off by default** (`app.audit.seal.enabled=false`; needs a pinned A2A signing key, else none are created). The turn journal is behind a flag, default `false`. Whether `tool_call_log` is written unconditionally was not traced (see OWASP page, MCP08). Retention periods for logs are not tied to the Act's minimum (six months for deployers, Art. 19/26, to be confirmed). Logs may hold personal data; see DPDP page 4.6 |
| 13 Transparency and information to deployers | Instructions for use, capabilities, limitations, accuracy levels, human-oversight measures | Product documentation under `docs/`; plain-language confirmation text for gated actions `core/guardrails/.../ToolConfirmationDescriber.java`; read-only turn inspector [ADR-0021](../architecture/ADR-0021-turn-inspector.md) (first slice). Config reference [configuration.md](../configuration.md) | Partly | No per-agent "instructions for use" or intended-purpose/limitations sheet generated for a deployer. Accuracy metrics exist only where a deployer runs evals. Provider-model limitations are the model provider's |
| 14 Human oversight | Systems designed so natural persons can oversee, understand, intervene, override or stop | Approval gate `core/approval/.../HumanApprovalGateService.java` (test `HumanApprovalGateServiceTest`), decision authorisation `ApprovalDecisionAuthorizer`, replay grant `ApprovalReplayGrant`, expiry job `ApprovalExpiryJob`. Autonomy tiers `core/agents/.../core/AgentAutonomyTier.java`: `SUPERVISED` gates every tool call, `SEMI_AUTONOMOUS` follows tenant policy, `FULLY_AUTONOMOUS` is not gated by the tier check. Supervised default `app.guardrails.supervised.gate-read-only=true`. Step limits scale by tier `app.guardrails.step-limit.*`. Budget stop `run_budget_exceeded` | Partly | New agents default to `SEMI_AUTONOMOUS` (`AgentAutonomyTier` javadoc, migration V74 backfill), not supervised. Approvals never expire by default (`app.approvals.expiry.ttl=0`). Gate-exempt tools list is non-empty (`confirmation-gate-exempt-tools`). Taint is observe-only by default, so content-driven side effects are not gated. `FULLY_AUTONOMOUS` bypasses the tier gate. No single "stop all agents" switch was looked for or found (not verified). Automation-bias mitigation beyond the describer text is not built |
| 15 Accuracy, robustness, cybersecurity | Appropriate accuracy, resilience to errors and to attempts to alter use or outputs (data poisoning, adversarial input) | Prompt-injection layers: `PatternBasedGuardrailService`, `SemanticGuardrailService` (`app.guardrails.implementation=semantic`, `app.guardrails.fail-mode=closed`), jailbreak detector (`app.agents.default-guardrails=jailbreak`). SSRF guard and egress list `core/security/.../net/SsrfGuard.java`. Tool-arg policies `app.tool-arg-policies.enabled=true`. Skill supply chain `app.skills.supply-chain.enforce=true`. MCP pinning `warn`. Encryption at rest `core/crypto`. Circuit breaker `ToolCircuitBreakerService`. Answer quality `AnswerConsistencyChecker`, `ReflexionService`, `LlmQualityJudgeService` (sampled, 5%). Full control-by-control view: [owasp-mapping.md](../security/owasp-mapping.md) | Partly | The OWASP mapping rates no row Covered (LLM10 was downgraded on 2026-11-03) and LLM07 (system-prompt leakage) Not covered. Injection defences are regex plus a judge, with no structural separation. No published accuracy declaration or robustness test report. Adversarial suite is Phase 0 work in progress, not shipped (`docs/architecture/PHASE0-SAFETY-WORKFLOW-2026-11.md`, on the lead's branch) |
| 50 Transparency obligations | Tell people they are interacting with an AI system unless obvious; mark synthetic content; inform on emotion recognition or biometric categorisation | One-time "You're chatting with an AI assistant, not a human." prefix on the first reply of each channel session: `ChannelSessionService.consumeAiDisclosurePrefix`, `ChannelSession.markAiDisclosureSentIfNeeded`, column `ai_disclosure_sent` (migration `core/app/src/main/resources/db/migration/tenant/V34__channel_session_ai_disclosure.sql`; existing sessions backfilled TRUE). Called from the Twilio, Telegram, Signal, WhatsApp, Meta, Matrix, Slack, Teams, Discord, email, X DM and iMessage inbound paths (call sites found by grep). Test `ChannelSessionTest` | Partly | Fixed English text, not localised and not configurable (constant `AI_DISCLOSURE_PREFIX`). I found no equivalent in the web chat, embed widget or voice path (`web/` and voice were **not searched**; do not assume either way). No machine-readable marking of synthetic text, audio, image or video output (Art. 50(2)); none found in `core` by a grep for synthetic/watermark/c2pa. Whether the disclosure is needed is a deployer judgement ("obvious from context") |

## Gaps, ranked by what a deployer is likeliest to need

1. **Default posture.** The base profile is observe/warn/allow in several places that Art. 9, 14 and 15
   would expect enforced for a high-risk use. The proposed `strict` profile is
   [ADR-0027](../architecture/ADR-0027-strict-security-posture.md); until it exists and is adopted, a
   deployer must set each key by hand.
2. **Logging is optional in practice.** Audit seals and the turn journal are off by default; there is no
   documented retention schedule against Art. 12 / 19 / 26.
3. **Art. 50 coverage.** No disclosure found for web, embed or voice; no synthetic-content marking.
4. **Art. 13 deliverable.** No generated instructions-for-use or limitations document per agent.
5. **Art. 10.** No dataset governance or bias tooling for retrieval data.
6. **Art. 9 process.** The risk assessment is a document, not a lifecycle process with owners; no residual-risk register.
7. **Learning agents.** Memory and skill learning change system behaviour after deployment. If a deployer
   treats that as a "substantial modification", conformity assessment may need repeating (counsel).
   Controls required before any learning feature ships are in the
   [learning-agents threat model](../security/threat-model-learning-agents.md).

## Not verified

- The text of the Regulation (see the box above) and the application dates of each chapter.
- Any control on a running stack; test classes were located, assertion depth was not audited.
- Web chat, embed and voice disclosure; a global stop switch; `tool_call_log` write conditions.
- Deployer-side obligations (Art. 26: use per instructions, input-data relevance, monitoring, informing workers, FRIA under Art. 27 for some deployers). The platform provides none of these as workflow.

See also: [ISO/IEC 42001 and NIST AI RMF mapping](iso-42001-and-nist-ai-rmf-mapping.md).
