# Phase 1 / 03: Memory decay, nightly consolidation, and privacy

Status: design proposal (2026-11-04). Nothing here is implemented. Scope: long-term memory only (the
per-agent and shared-tier fact stores, the graph memory derived from them, and the summaries derived from
either). Read with `docs/compliance/dpdp-obligations.md` section 4.5, `docs/compliance/eu-ai-act-mapping.md`,
and `docs/architecture/PHASE0-SAFETY-WORKFLOW-2026-11.md` (the QUARANTINED/TRUSTED gate this design must not weaken).

## 1. What exists today (the starting point)
| Piece | Where | Relevant behaviour |
|---|---|---|
| Fact stores | `core/agents-turn/.../memory/AgentMemoryService`, `TenantMemoryService` | Rows carry `created_at`, `last_used_at`, `use_count`, `trust`, `source_taint`. Subject-scoped `updateForSubject` / `forgetForSubject` and `forgetAllForSubject`. |
| Trust | `MemoryTrust`, `MemoryProvenance`, `MemoryWriteGate` | Two values. Parsing fails closed (unknown becomes QUARANTINED). A tainted turn writes QUARANTINED until a person approves (`AgentMemoryController` `/approve`, `/reject`). |
| Daily review | `core/agents-jobs/.../learning/review/MemoryReviewSweep`, `MemoryReviewService`, `MemoryReviewDetector` | Hourly tick, claims `(subject, run_date)` in `learning_review_runs` (V643), per tenant via `TenantSchemaIterator` plus `PgAdvisoryJobLock`. No model call: word-overlap duplicates, contradictions, stale facts (`app.learning.review.stale-days=60`). Files CONSOLIDATION proposals only; changes nothing. |
| Applier | `MemoryConsolidationApplier` | Runs after a person approves. Hash-checks every named fact (stale proposal refused whole), rewrites keep-fact, forgets the rest through the same `*ForSubject` methods the Memory page uses, then best-effort graph edge invalidation (`app.graph-memory.user-forget-sync.enabled`). |
| User surface | `UserMemoryController` `/api/memory`, `UserMemoryProvenanceController` `/api/memory/facts` | List, edit, forget, forget-all for the caller's own facts. |
| Privacy | `app/.../common/privacy`: `ContactPrivacyService` (export/erase one channel contact), `RetentionPolicyService` + `RetentionPurgeJob` + `RetentionPurger` (table `privacy_retention_policy`, default 365 days, max 3650), `PrivacyController` `/api/tenant/privacy/*` | Erasure continues past a failed step and reports `complete=false`, `failures`, and a verbatim `NOT_REACHED` list. `TenantDataErasureService` erases a whole tenant. |
| Budgets | `core/agents-budget`: `SpendCapService` (tenant monthly USD cap, `assertWithinCap`, `onHostedSpend`), `PerUserBudgetService` (per-user USD window) | Both gate hosted-LLM spend. |

Gaps: review only proposes and "stale" is one 60-day cliff; no deletion record; erasure says nothing about summaries;
retention classes do not name memory kinds; graph memory is not purged by age (dpdp doc 4.4).

## 2. Design principles

Consolidation only reduces privilege: it may delete, merge, summarise and lower salience, never raise trust, widen
visibility or change the owner. Person-authored and pinned facts are never auto-forgotten. Everything runs in
`core-jobs`, off the chat path (AGENTS.md rule 6). Erasure covers derived data, so lineage is recorded at write time.

## 3. Decay function

A per-fact **salience** `S` in `[0,1]`, computed on demand from columns that already exist (`created_at`,
`last_used_at`, `use_count`) plus two new ones (`importance`, `pinned`). Nothing is stored except `importance`
and `pinned`; `S` is recomputed by the job and when ranking, so changing a default needs no backfill.

```
age_d   = days since max(last_used_at, created_at)            (recency clock; reads reset it)
R       = 0.5 ^ (age_d / H)                                   recency, half-life H days
F       = min(1, ln(1 + use_count) / ln(1 + F_SAT))           frequency, saturating at F_SAT uses
I       = importance                                           0..1, assigned at write time (below)
S       = clamp( wI*I + wR*R + wF*F , 0, 1 )                   if pinned: S = 1
S_eff   = S * trust_factor                                     trust_factor = 1.0 TRUSTED, 0.5 QUARANTINED
```

Defaults (all `app.memory.decay.*`, per tenant overridable in the retention policy table, section 10):
| Key | Default | Note |
|---|---|---|
| `half-life-days` (H) | 45 | A fact untouched for 45 days has R = 0.5. Per kind override: `preference` 120, `episodic` 21, `task-context` 7. |
| `frequency-saturation` (F_SAT) | 20 | 20 recalls count as "fully used". |
| `weights` (wI, wR, wF) | 0.45, 0.35, 0.20 | Sum to 1. |
| `forget-threshold` | 0.12 | Below this a fact is a forget candidate. |
| `summarise-threshold` | 0.30 | Between forget and this, a fact is folded into a summary. |
| `min-age-days` | 14 | Nothing younger than this is touched, whatever its score. |
| `importance` defaults | 0.5 | `remember_fact` tool call 0.6; user-typed on Memory page 0.8; imported 0.5; derived summary inherits the max of its sources minus 0.1. |

Worked check: an unpinned TRUSTED preference (importance 0.5, never used, idle 200 days, H = 120) has
R = 0.31 and `S = 0.225 + 0.109 = 0.33`: kept. Never-used importance 0.3 idle 300 days at H = 45 gives `S = 0.14`,
still above 0.12, so importance acts as a floor; forgetting needs importance below about 0.25 plus long idleness.
The function is a pure static method (`MemorySalience.score(...)`), unit-tested without a database.

Recall ranking may use `S_eff` as a tiebreaker only; it must not change who is eligible for recall (quarantined
rows are already excluded from prompts regardless of score).

## 4. Nightly consolidation job

New `MemoryConsolidationSweep` beside `MemoryReviewSweep` in `core/agents-jobs/.../learning/review`, same shape:
`TenantSchemaIterator` outer loop, `PgAdvisoryJobLock` per tenant, hourly tick that claims work so a sleeping
machine catches up. It reuses the `learning_review_runs` claim idea with a new table `memory_consolidation_runs`
`(subject, run_date, mode, merged, summarised, forgotten, cost_usd, status)`. One run per subject per day.
Conditional on `app.memory.consolidation.enabled` (default false in phase 1; opt-in per tenant).

Per tenant, per subject (subjects processed in least-recently-consolidated order, `max-subjects-per-tick` 200 as today):

1. **Score** all of the subject's rows in the agent tier and shared tier (pure SQL + Java, no model).
2. **Forget candidates** (`S_eff < forget-threshold`, age >= `min-age-days`, not pinned, trust TRUSTED or
   QUARANTINED, not person-authored when `protect-person-authored=true`, the default). Quarantined rows that were never
   approved and are older than `quarantine-ttl-days` (default 30) are always forget candidates: unreviewed
   attacker-influenced data should not live forever.
3. **Merge**: near-duplicates found by the existing `MemoryReviewDetector` (word overlap, no model). Keep the
   higher-trust row, then the more recently used; the loser is tombstoned with `reason=MERGED_INTO:<keepId>`.
   Merge never rewrites the kept text automatically (text change stays a human decision via a proposal) unless the
   subject enabled `auto-apply-merges` (default false).
4. **Summarise** (the only model call): rows in the summarise band that share an agent and a kind are batched
   (<= 12 rows, <= 2,000 input tokens) into one summary fact with `kind=SUMMARY`, `derived_from=[ids]`,
   `importance=max(sources)-0.1`, **trust = min(trust of all sources)** (section 5). Sources are tombstoned with
   `reason=SUMMARISED_INTO:<summaryId>` only after the summary row is committed (single transaction).
5. **Graph sync**: call `GraphMemoryService.forgetFactText` for every tombstoned text, as `MemoryConsolidationApplier.syncGraph` does.
   Best effort, failure recorded on the run row and retried next night.
6. **Dry-run first.** For the first `dry-run-days` (default 7) after enabling, the job computes everything and files
   it as ordinary CONSOLIDATION proposals (today's path, person approves). After that, only operations marked
   `auto` by the thresholds above run unattended; everything else still goes through proposals. This keeps
   the existing approval flow as the safe default and automation as an earned mode.

Caps per run (all configurable): `max-forget-per-subject-per-night` 25, `max-summaries-per-subject` 3,
`max-fraction-forgotten` 0.30 (never delete more than 30% of a subject's rows in one night; trips a circuit
breaker and files proposals instead, protecting against a bad threshold or clock skew).

### Cost budgets (reuse, do not rebuild)

Only step 4 costs money. It is metered as hosted spend so existing counters see it:

* Before each model call: `SpendCapService.assertWithinCap(schema)`. On `SpendCapReachedException` the tenant's
  remaining work for the night is skipped (steps 1 to 3 and 5 still run, they are free) and the run row is marked `BUDGET_SKIPPED`.
* Attribute spend to the subject: reserve and record through `PerUserBudgetService` using the subject's user id
  where the subject maps to a platform user; for pure channel contacts (no platform user) attribute to a
  synthetic `system:memory-consolidation` principal that is exempt from per-user caps but still counted
  by the tenant cap. Calls go through the normal provider path so `llm_call_log` and `onHostedSpend` fire.
* New job-level ceiling `app.memory.consolidation.max-usd-per-tenant-per-night` (default 0.50) and
  `max-usd-per-run` (default 5.00 across all tenants) enforced in the sweep before `assertWithinCap`.
* Model choice: the cheapest configured model for the tenant (`app.memory.consolidation.model`, default unset =
  tenant default small model). BYO-key tenants spend their own key; the caps still apply as a runaway guard.

## 5. Invariant: consolidation never raises QUARANTINED to TRUSTED

Rule (normative): **for any output row O produced from input rows I1..In, `trust(O) = TRUSTED` only if every
`trust(Ii) = TRUSTED`.** Equivalently `trust(O) = min` over the inputs under the order QUARANTINED < TRUSTED, and
`source_taint(O) = union(source_taint(Ii))`.

Enforcement, defence in depth:

1. **Type level.** `MemoryTrust.meet(Collection<MemoryTrust>)` (empty collection returns QUARANTINED) is the only way the job
   obtains the trust of a derived row. There is no `setTrust` on the consolidation path; the insert statement for derived rows takes a
   `MemoryProvenance` that can only be built by `MemoryProvenance.derive(List<AgentMemoryEntry>)`.
2. **The model never sees trust and never decides it.** The summariser prompt returns text only. Trust, taint and
   lineage are attached in Java after the call. A model that outputs "trusted" has no channel to say so.
3. **Quarantined inputs are summarised in a separate batch from trusted ones** by default, so one poisoned row cannot
   downgrade a whole batch of good facts (availability) and cannot hide inside a trusted summary (integrity). If
   `mix-trust-batches=true`, the output is QUARANTINED. Quarantined summaries are never recalled into prompts (same rule as rows).
4. **No auto-approve.** The only transition QUARANTINED to TRUSTED is `AgentMemoryController` `/approve` by an
   authenticated person (existing). The job holds no credential for that endpoint and `approve(...)` is not
   injectable into the consolidation package (ArchUnit rule below).
5. **Merge never launders.** When a TRUSTED and a QUARANTINED duplicate merge, the TRUSTED row survives unchanged and the
   quarantined text is dropped; the kept row is never rewritten, so nothing quarantined can acquire trusted status.
6. **Edits are not approval.** `updateForSubject` must preserve `trust` (add a test); only `/approve` clears quarantine.

## 6. Tombstones and audit trail

New tenant-schema table (Flyway: claim the next free number in the shared namespace, check `scripts/ci/flyway-lint.sh`;
Phase 0 reserves up to 674):

```
memory_tombstone(
  id UUID PK, subject_key VARCHAR(255), memory_kind VARCHAR(16),   -- AGENT | SHARED | SUMMARY | GRAPH
  agent_id UUID NULL, original_id UUID, content_sha256 CHAR(64),   -- hash, never the text
  reason VARCHAR(48),        -- DECAY | MERGED_INTO:<id> | SUMMARISED_INTO:<id> | USER_DELETE | USER_EDIT
                             --  | QUARANTINE_TTL | RETENTION | ERASURE_ART17
  actor VARCHAR(64),         -- user id, 'system:memory-consolidation', or 'dsar:<requestId>'
  score_at_delete NUMERIC(4,3) NULL, trust_at_delete VARCHAR(16),
  derived_from UUID[] NULL,  -- lineage
  deleted_at TIMESTAMPTZ, restorable_until TIMESTAMPTZ NULL,
  restore_blob BYTEA NULL    -- encrypted text; present ONLY for decay/merge reasons, until restorable_until
)
```

* **Soft-delete window.** For machine-initiated reasons (DECAY, MERGED, SUMMARISED, QUARANTINE_TTL) the text is kept
  encrypted (key as `APP_ENCRYPTION_KEY`, AGENTS.md rule 5) in `restore_blob` for `restore-window-days` (default 14) so the user can undo.
  A purge job nulls `restore_blob` then; the hash row remains as the tombstone. For USER_DELETE, ERASURE_ART17 and
  RETENTION **no blob is ever written**; the tombstone is a hash and metadata only, so a tombstone is never a back door.
* **Tombstone purpose limits.** Hash-only tombstones exist so the nightly job and imports do not resurrect a deleted
  fact (the importer and `remember` check `content_sha256` against tombstones for the subject; a match from an
  automated source is dropped, from the person is allowed and clears the tombstone). For an Art. 17 erasure the
  tombstone `subject_key` is itself replaced by a keyed HMAC of the subject (key per tenant) so the tombstone table holds no
  directly identifying value; lookups HMAC the incoming subject.
* **Audit.** Every job run and every individual deletion emits an `AuditLogService` event (`MEMORY_CONSOLIDATION_RUN`
  with counts, cost, status; `MEMORY_FACT_TOMBSTONED` with ids, reason, actor, never the text). The run summary also
  goes to the user-visible activity log (section 7). The audit log stays append-only and holds masked identifiers
  only, consistent with `ContactPrivacyService.NOT_REACHED`.

## 7. Per-user visibility, edit and delete (UX and API)

Extends the existing Memory page and `/api/memory` (no new UI framework; existing tokens/components).

UX, in the Memory page:

* Each fact shows: text, which agent/tier, source (typed by you, learned in a chat, imported, summary of N notes),
  trust badge (Needs your approval / Trusted), "last used" and "used N times", and a **Keep** pin.
* A fading indicator when `S_eff` is in the summarise or forget band ("may be tidied up in about N days"), computed
  server-side so the number matches the job.
* A **Tidy-up activity** tab: last 90 days of consolidation events in plain words ("Merged 2 notes about your
  address", "Forgot 3 notes you hadn't used since March", "Summarised 8 older notes"), each with **Undo** while
  `restorable_until` is in the future, and **Why?** showing score, reason and lineage. Never shows deleted text
  after the window.
* Controls: auto-tidy On / Ask me first (phase 1 default) / Off; Keep pin; Export; Erase everything.

API (all under the caller's own subject, derived from the principal; no `subject` parameter, so no IDOR):
| Method and path | Purpose |
|---|---|
| `GET /api/memory` | Existing; add `importance`, `pinned`, `kind`, `salience`, `tidyBand` (`KEEP`/`SUMMARISE`/`FORGET`), `derivedFrom`. |
| `PATCH /api/memory/facts/{id}` | Edit text and/or `pinned`/`importance`. Never changes `trust`. |
| `DELETE /api/memory/facts/{id}` | Existing forget; now writes a USER_DELETE tombstone, cascades to summaries (section 8). |
| `GET /api/memory/activity?from=&limit=` | Tidy-up activity from `memory_tombstone` + run rows. |
| `POST /api/memory/activity/{tombstoneId}/restore` | Undo within window; restored row keeps its original trust (never higher). 410 after the window. |
| `GET`/`PUT /api/memory/settings` | `autoTidy: ON/ASK/OFF`. |
| `GET /api/memory/export` | JSON of facts, summaries with lineage, tombstone metadata (Art. 15 and 20). |
| `POST /api/memory/erase` | Art. 17 self-service: returns an `ErasureReport` shaped like `ContactPrivacyService.ErasureReport`. |

Operator/DPO equivalents stay in `PrivacyController` (`/api/tenant/privacy/...`). New controller methods must keep
controllers off repositories (`StructureRulesTest`); go through a `UserMemoryFactService` method. Regenerate
`docs/api/openapi.json` and the SDKs (`OpenApiSpecGeneratorTest -Dopenapi.write=true`, `node sdk/generate.mjs`).

## 8. GDPR Art. 15 and Art. 17 propagation across all memory kinds

"Memory kinds" in scope, each with who reaches it today and the added work:
| Kind | Store | Today | Needed |
|---|---|---|---|
| Agent facts | agent memory table (per agent, per subject) | `forgetAllForSubject` across all agents | keep; add tombstone |
| Shared tenant facts and memory blocks | `TenantMemoryService` | erased by `ContactPrivacyService` | keep; add tombstone |
| Graph memory | `GraphMemoryService` / AGE | entity erase by subject; edge sync best effort and OFF by default | make erasure sync **mandatory** for Art. 17 (not behind `user-forget-sync.enabled`); add age-based purge (graph has no creation time: add `valid_from`, section 10) |
| Session window | Redis `SessionMemoryService` | erased | keep |
| **Summaries (new)** | agent/shared tables, `kind=SUMMARY` | n/a | cascade, below |
| **Session summaries** | `SessionSummaryService` / `SessionSummaryFolder` | not named in the erase path | include: delete the folded summary of every session owned by the subject (verify in code before build; the folder may be keyed by session id, not subject) |
| Knowledge chunks the agent wrote | knowledge store, matched by session id in `source` | partial (stated in `NOT_REACHED`) | keep; add subject stamp to the chunk source so match no longer depends on session id |
| Embeddings and caches | vector column on each row | deleted with the row | confirm no separate embedding copy; caches and `llm_call_log` stay in `NOT_REACHED`, consolidation prompts bypass the response cache |

**Summary cascade algorithm** (the new part). When subject X's fact F is erased (user delete or Art. 17):

1. Find summaries S where `F.id IN S.derived_from` (indexed GIN on `derived_from`).
2. If every source of S belongs to X: delete S (tombstone `ERASURE_ART17`).
3. If S has sources from other subjects (only possible in shared tier): do **not** keep S as is, because it may paraphrase F. Delete S and re-queue the remaining sources of the other subjects for re-summarisation without F. Prefer a lost summary over a leaked fact.
4. Recurse: a summary of summaries is found through the same lineage index.
5. Because lineage is recorded at write time, and a summary is only created in the same transaction that records lineage, a crash cannot produce an unlinked summary.
6. Pre-existing derived data that predates lineage (the CONSOLIDATION "merge" rewrite of a kept fact, which may hold text from a dropped fact): mark kept facts produced by a merge with `derived_from` going forward; for old ones, the erasure falls back to a text-similarity sweep of the subject's rows (reuse `MemoryReviewDetector` overlap) and lists "possibly derived rows removed: N" in the report.

**Erasure endpoint behaviour.** Art. 17 runs synchronously for the stores above and returns the existing
report shape (`erased` counts per kind, `failures`, `complete`, `notReached`). It must also: (a) set a
per-subject **erasure fence** (`memory_erasure_fence(subject_hmac, until)`) for `fence-minutes` (default 15) so the
nightly job and in-flight turns cannot re-learn a fact from a conversation that was mid-flight; (b) cancel pending
CONSOLIDATION proposals for the subject (their payload holds fact ids and hashes); (c) write ERASURE_ART17
tombstones (hash and HMAC only); (d) when `complete=false`, schedule a retry (the erase continues past failures
today, so the missing part is the retry, ledger row `privacy_erasure_retry`). Statutory deadline: one month (Art.
12(3)); the retry must succeed or escalate to the DPO queue inside that window.

**Art. 15 access.** `GET /api/memory/export` plus the contact export. Contents: every fact with text, source and
trust; summaries with their lineage; what the system inferred (importance, salience); the tombstone metadata (not
text); the recipients list (LLM provider, channel provider), which already appears in `NOT_REACHED`. Export is
rate-limited and audit-logged (`MEMORY_EXPORT`).

**Backups.** Unchanged and stated honestly; on restore, replay the `ERASURE_ART17` tombstones, so those are exempt from retention purge.

## 9. Retention policy configuration

Extend the existing `privacy_retention_policy (channel, data_class, enabled, retention_days, ...)` and
`PrivacyDataClass`, instead of a new table. New data classes: `MEMORY_FACT`, `MEMORY_SUMMARY`, `MEMORY_QUARANTINED`,
`MEMORY_TOMBSTONE_BLOB`, `GRAPH_MEMORY`. Semantics: `retention_days` is a **hard ceiling on age since last use** that
overrides salience (a fact is deleted at the ceiling even if pinned, reason `RETENTION`, because a legal
ceiling beats a UX preference); decay is the soft path below the ceiling.
| Key (YAML, with matching per-tenant row) | Default |
|---|---|
| `app.memory.decay.*` | section 3 |
| `app.memory.consolidation.enabled` | `false` |
| `app.memory.consolidation.auto-apply` | `false` (propose-only until dry-run ends) |
| `app.memory.consolidation.dry-run-days` | 7 |
| `app.memory.consolidation.quarantine-ttl-days` | 30 |
| `app.memory.consolidation.restore-window-days` | 14 |
| `app.memory.consolidation.max-usd-per-tenant-per-night` | 0.50 |
| `app.memory.retention.fact-days` | unset (no ceiling) in personal editions; tenant policy otherwise |
| `app.memory.retention.summary-days` | same as `fact-days` |
| `app.memory.retention.tombstone-days` | 730 (hash-only, for resurrection guard and erasure replay) |
| `app.memory.erasure.fence-minutes` | 15 |

Validation reuses `RetentionPolicyService` (reject non-positive, cap 3650). Two compliance interactions must be
decided by the owner and documented: (1) DPDP rule 8(3) one-year log floor versus a shorter memory ceiling: memory
is not a "log", but the audit trail of the erasure (tombstone metadata, audit event) is kept at least that long;
(2) a GDPR tenant that sets a retention ceiling shorter than a contractual need keeps the contractual copy
elsewhere; this system does not hold it. Add the keys to `docs/configuration.md`, `deploy/compose/.env.example`
only if Compose sets them, `CONFIG-REFERENCE.generated.md`, and a CHANGELOG `[Unreleased]` line.

`RetentionPurger` today deletes by `created_at` (`purgeCreatedBefore`). Change the memory purge to use
`COALESCE(last_used_at, created_at)` for the class `MEMORY_FACT`, and make the purge write tombstones through the same
service so retention deletes are visible in the Tidy-up activity.

## 10. EU AI Act implications

The platform is not itself a high-risk system; whether a deployer's agent is high-risk depends on use
(`docs/compliance/eu-ai-act-mapping.md`). What this design changes for a deployer:

* **Art. 12 (record-keeping) and Art. 19/26(6) (log retention):** the audit events and run rows are the automatic
  logs of what memory the agent had and changed. They conflict, by nature, with GDPR erasure when they carry
  personal data, so they carry ids, counts and hashes only. Retention of those logs: at least six months for
  deployers of high-risk systems (Art. 26(6)); `tombstone-days` default 730 satisfies it. Document the schedule.
* **Art. 14 (human oversight):** default `autoTidy=ASK` and a visible undo window keep a human in the loop for
  anything that silently changes the agent's future behaviour. A learning agent whose memory changes after deployment
  is a "substantial modification" question for the provider (Art. 3(23)); recording each change with reason and
  actor supports the "predetermined changes" carve-out argument, but counsel decides.
* **Art. 13 (transparency):** the Memory page and activity log are the user-facing explanation of what the system
  remembers and why it forgot; Art. 50 (deployer disclosure to people interacting with an AI) is unaffected but the page
  should state that summaries are model-generated.
* **Art. 15 (accuracy, robustness, cybersecurity):** the trust-meet invariant (section 5) is the robustness control against
  memory poisoning persisting through summarisation, mapping to OWASP ASI06. Without it, consolidation would launder
  untrusted content into trusted memory.

Update `eu-ai-act-mapping.md` items on learning agents (section "Learning agents") once built; do not claim
conformance before then.

## 11. Failure modes and handling
| Failure | Handling |
|---|---|
| Model call fails or times out | Skip summarise for that batch, leave sources untouched, record `status=PARTIAL`; free steps still run. Never delete sources before the summary commits. |
| Model returns empty, oversized or redacted-looking text | Reject (reuse `SkillDraftScrubber.redactions` check from the applier), keep sources. |
| Spend cap reached mid-run | `SpendCapReachedException`: stop model steps for the tenant, `BUDGET_SKIPPED`, resume next night. Per-user cap hit: skip that subject only. |
| Bad threshold or clock skew would wipe memory | `max-fraction-forgotten` breaker; switch to proposals; alert. Clock sourced from DB `now()`, not each replica. |
| Two replicas run the same tenant | `PgAdvisoryJobLock` plus the `(subject, run_date)` primary-key claim, as today. |
| Subject edited/forgot a fact between scoring and apply | Per-row hash check (as in `MemoryConsolidationApplier`) inside a transaction; any mismatch aborts that subject whole. |
| Erasure races the nightly job | Erasure fence plus row locks (`SELECT ... FOR UPDATE SKIP LOCKED` on the subject's rows); job rechecks fence before every commit. |
| Graph store down | Row deletion is not rolled back; graph sync queued with retry. For Art. 17 the report says `complete=false` until graph is clean. |
| Tenant erased wholesale | `TenantDataErasureService` already drops the schema; add the new tables to its erase list and test (dedicated assertion that no memory table survives). |

## 12. Tests
Unit (no Spring): `MemorySalienceTest` (formula, defaults, boundaries, pinned, kind half-lives, monotonic in age, bounded);
`MemoryTrustMeetTest` and `MemoryProvenanceDeriveTest` (property test: for any mix of inputs the result is TRUSTED iff all inputs are; empty is QUARANTINED; taint is a union);
`ConsolidationPlannerTest` (bands, `min-age-days`, caps, circuit breaker, person-authored protection, quarantine TTL).

Module (`core/agents-jobs`): `MemoryConsolidationSweepTest` with in-memory fakes of the memory services: dry-run
files proposals only; auto mode applies only `auto` operations; budget exception skips model steps but still
tombstones free ones; model failure leaves sources; summary with one quarantined source is QUARANTINED; trusted and
quarantined batched separately; a hostile model response claiming trust does not change trust; restore never
raises trust; one run per subject per day; tenant A's subject never appears in tenant B's run (two schemas).

ArchUnit (in `core/app`, extend `ModuleBoundaryRulesTest` conventions, do not refreeze baselines): classes in
the consolidation package may not depend on `AgentMemoryService.approve`, `TenantMemoryService.approve*` or any
`MemoryTrust.TRUSTED` literal constructor (use a source-level test that greps for `MemoryTrust.TRUSTED` and
`MemoryProvenance.TRUSTED` in the package). `AsyncHopTenantScopeRulesTest` must stay green.

Integration (`core/app`, follow `DpdpErasureAndExportIT` and `DpdpControlsFixture`): seed a subject with facts across two
agents, shared tier, a summary derived from three of them, a graph edge, a session summary and a knowledge chunk;
call `POST /api/memory/erase`; assert every store is empty, summaries gone (including a summary mixing two subjects
in the shared tier, re-queued without the erased fact), tombstones are hash-only (no text column match), the
fence blocks a concurrent nightly run, another tenant's identical subject is untouched, and `notReached` is
present. Retention: set `MEMORY_FACT` ceiling to 30 days, age a pinned fact, assert RETENTION tombstone and
visibility in `/api/memory/activity`. Export: Art. 15 output includes lineage and excludes deleted text.

Build gates (AGENTS.md rule 14): `make gates`; module tests for `agents-turn`, `agents-jobs`, `app`; `flyway-lint.sh`;
`entity-name-lint.sh` (new JPA entities); `UniqueBeanNamesTest`, `InjectableConstructorTest`,
`ShapeInjectionPointsTest` (the sweep runs only in jobs: inject controller-only beans via `ObjectProvider`);
regenerate the OpenAPI golden and SDKs; `bash scripts/ci/boot-check.sh` for api, jobs and worker shapes. Report
compiled vs unit-tested vs run-on-a-stack separately (rule 11).

## 13. Rollout and open questions

Phases: (1) schema, salience, tombstones on existing delete paths; (2) lineage and the Art. 17 cascade; (3) nightly job
in dry-run (proposals only); (4) opt-in auto mode per tenant once more than 80% of proposals are approved unchanged.

Open questions for the owner: (1) may a legal retention ceiling delete a pinned fact (this design says yes);
(2) how do channel contacts, who have no login, see and undo tidy-ups (recommend operator DPO tooling plus the contact export);
(3) is an HMAC-of-subject tombstone acceptable to counsel; (4) is the session summary store keyed by subject or session id
(verify before build; it decides whether the section 8 cascade needs a new index).
Tenant scoping rules for the whole job: every query runs in the schema set by `TenantSchemaIterator`, work handed to an
executor carries `TenantScope.capture()` (AGENTS.md rule 12), PgBouncer stays in session mode, and shared-tier summaries
are built only from one tenant and never from `agent-private` rows.
