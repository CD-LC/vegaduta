# Threat model: agents that learn

Scope: any feature by which an agent's future behaviour changes because of what it saw in the past:
long-term memory (`remember_fact`, graph memory), knowledge-base writes (`add_to_knowledge_base`),
skill drafting and improvement, learned procedures or proposals, and anything that adjusts prompts,
routing or tool choice from feedback. Written 2026-11-03, before any new learning feature ships.

Honesty note, as in [owasp-mapping.md](owasp-mapping.md): paths and keys below were opened on this date;
nothing was run on a stack. Controls marked **planned** belong to Phase 0 work packages that are not in
this branch and are **not verified**. STRIDE is used as a checklist, not as a claim of completeness.

## What exists today (verified in the working tree)

| Learning surface | State | Evidence |
|---|---|---|
| Skill learning loop | **Off in base** (`app.skills.learning.enabled=false`), on in `personal`. Drafts a skill after a successful multi-step turn, stored PENDING for a human to approve, edit or reject; nothing it writes reaches a prompt before review | `core/agents-jobs/.../skills/learning/SkillLearningService.java` javadoc; `application.yml`; `application-personal.yml` |
| Learning proposals | On in `personal` (`app.learning.proposals.enabled`: "the assistant proposes, you approve") | `application-personal.yml` header |
| Skill supply chain | Scan on load, content hash pinned on approval, changed or HIGH-scoring custom skills withheld (`app.skills.supply-chain.enforce=true` in base, `false` in `personal`) | `application.yml` |
| Fact memory | `remember_fact` writes; per-subject admission caps; no injection scan on the write (OWASP issue 05) | `core/agents-turn/.../memory/OwnFactAdmission.java`, `RememberFactToolCallback.java`; OWASP ASI06 |
| Memory provenance | Read-only lookup derived from `tool_call_log`, best-effort, 2-minute window, can fail to match | `MemoryProvenanceService` javadoc |
| KB write screen | Regex tripwire for injection phrases and credentials | `core/knowledge/.../KnowledgeWriteScreen.java` |
| Taint | Records that a turn read untrusted content; `observe` by default, so it gates nothing | `app.agents.taint.mode`, `core/agents-tools/.../AgentTaintPolicy.java` |
| Erasure | Per-contact erase reaches agent memory, shared memory, graph entities and KB chunks written for that contact; does not reach backups, provider copies or logs | [dpdp-obligations.md](../compliance/dpdp-obligations.md) section 4.5 |

Entry-by-entry taint seeding: [taint-entry-points.md](taint-entry-points.md).

Planned, not verified: persisted conversation taint and seeding (package B), memory provenance,
quarantine and write screen (package C), out-of-band control markers (D), the adversarial suite (H).

## Assets and trust boundaries

Assets: the agent's prompt context; durable memory and graph; knowledge collections; skills and
procedures; tool permissions; tenant and subject data. Boundaries: **untrusted content** (web page,
email, file, remote agent reply, MCP tool output) to **model context** to **durable store** to **future
context of the same or another subject**. Learning adds the last arrow; most existing defences guard
only the first two.

## STRIDE for learning

| Threat (STRIDE) | Scenario | Existing control | Gap | Required before ship |
|---|---|---|---|---|
| **Tampering: memory poisoning** | A web page or email tells the agent to "remember" a false fact or standing instruction; it is trusted in every later turn | Admission caps; KB screen (KB path only) | No injection scan on `remember_fact` or graph writes; taint is observe-only, so a tainted turn may still write | Writes from a tainted turn go to quarantine, not live memory; human or policy promotes. Out-of-band provenance on every item (source, taint flag, turn id) |
| **Tampering: skill poisoning** | Injected content causes a drafted skill to contain exfiltration or approval-evasion steps; once approved it runs as a trusted procedure | Skill scanner; hash pin; human approval; supply-chain `enforce` | Scanner is static and "reduces risk, does not prove safety" (`application.yml`); approver sees a diff only if the UI shows it (not verified); `personal` approves its own drafts | Drafts derived from a tainted turn flagged and not auto-approvable; re-scan on every edit; approver sees the source turn |
| **Elevation of privilege** | A learned skill or fact widens what the agent does (new tool, new target, "always send to X") | Tool allow-list, classification gate, arg policies, trifecta check | A learned item is text, not a grant, but a model follows it; gate-exempt tools and `unclassified=allow` weaken the backstop | Learned items can never add tool permissions; gated and egress tools stay gated regardless of learned instructions; run under the `strict` posture ([ADR-0027](../architecture/ADR-0027-strict-security-posture.md)) |
| **Spoofing: forged provenance or control markers** | Content imitates a system or approval marker so a write looks human-approved | Plain-language confirmation text; approvals tied to request ids | Markers travelling in-band in tool output can be forged (package D addresses; **planned**) | Provenance and approval state held out of band (database columns), never in model-visible text |
| **Repudiation** | After a bad outcome nobody can say which input taught the agent | Audit log; seals off by default; provenance best-effort | Seal and turn journal off by default; provenance can fail to match | Every learned item carries a durable source id and turn id; audit event on create, promote, edit, delete; seals on |
| **Information disclosure: privacy and erasure** | Learned memory contains personal data or secrets; one subject's fact leaks to another via shared memory or a shared skill; erasure misses derived artefacts | Per-subject session scoping `CallerSessionIds`; erasure endpoint; secret redaction on tool output | Skills and distilled summaries derived from personal data are not traced by the erasure path (not verified); backups and provider copies out of reach | A learned item records its subject(s); erasure and export enumerate and remove derived items (summaries, skills, embeddings); shared stores refuse subject-identifying content unless consented |
| **Denial of service: memory bloat and drift** | Poisoned or noisy writes degrade retrieval; behaviour drifts as memory accumulates, silently lowering quality | Admission caps; tool-selection cap | No drift or quality baseline; no memory size alarm | Per-agent store size and write-rate limits with alerts; periodic eval against a frozen set; a drift threshold that pauses learning |
| **Reward hacking** (not STRIDE; added) | A learning loop that optimises a proxy (user thumbs-up, effectiveness score, judge score) learns to satisfy the proxy: flatter, skip verification, game the sampled judge (5% sample, `app.llm.judge.sample-rate`) | Skill effectiveness score from "did the turn go well" (`SkillLearningService`) | The signal is model-judged and sampled; the learner and the judge share a model family | Never optimise on a signal the learned artefact can influence; held-out human-labelled eval gate; separate judge model; randomised audits |
| **Drift and feedback loops** (added) | Learned output feeds later learning (model-written memory trains model-written skills); errors compound | None | None found | Learned items marked as model-generated and excluded from learning inputs unless re-confirmed; cap on generation depth |

## Controls required before any learning feature ships

A learning feature is not shippable until each of these has a test that fails on the old behaviour:

1. **Human approval is the default.** A learned item (memory above a sensitivity class, skill, procedure,
   routing rule) is created PENDING and takes effect only on a human decision. Auto-promotion is an
   explicit, per-agent, audited opt-in, off in base and in `strict`, and never allowed for an item
   derived from a tainted turn.
2. **Rollback.** Every learned item is versioned and individually revertible, and a single operation
   reverts everything learned since a point in time (agent-wide). Reverting is audited and immediate:
   it must not wait for a cache TTL beyond the documented bound.
3. **Eval gate.** A frozen, human-labelled eval set runs before promotion and on a schedule after;
   a regression beyond a stated threshold blocks promotion or pauses learning. The eval set and the
   judge are outside what the learner can write to.
4. **Provenance.** Each item stores, out of band: source (turn id, session, tool or URL), taint state at
   write time, the writer (agent, human, import), subject ids, creation and approval events. "Cannot
   trace" is a visible state, not a silent default.
5. **Taint binding.** Tainted-turn writes are quarantined (not retrievable as trusted context) until
   promoted. This depends on taint being at least persisted per conversation (package B) and the
   quarantine (package C); both are **planned, not verified**.
6. **Erasure and export coverage.** The erasure path in [dpdp-obligations.md](../compliance/dpdp-obligations.md)
   is extended to enumerate every derived artefact, with a test that a learned item derived from an erased
   subject is gone.
7. **Bounds.** Per-agent and per-tenant limits on item count, size and write rate, with alerts.
8. **Kill switch.** One setting turns learning off for an agent, a tenant and the platform, and the
   off state is the default for any feature that has not passed this list.
9. **Posture.** The feature is exercised in CI under the `strict` profile and the adversarial suite
   (package H): injected content must not reach long-term memory, drive a side-effecting tool without a
   human, or forge a control marker.

## Residual risk and non-goals

Model-judged defences and regex screens will not stop a careful attacker; this list reduces blast radius
and makes damage detectable and reversible, it does not make learning safe. Regulatory implications
(for example whether post-deployment learning is a substantial modification) are in
[eu-ai-act-mapping.md](../compliance/eu-ai-act-mapping.md) and need counsel.

## Not verified

Anything marked planned; whether the skill-approval UI shows the source turn; whether erasure reaches
skills and summaries; the exact behaviour of `app.learning.proposals.*` (only its mention in
`application-personal.yml` was read); any runtime behaviour.
