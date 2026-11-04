# Phase 1 implementation plan: unified agent memory

Status: proposal for review. No code is written. Inputs: `01-memory-inventory.md`,
`ADR-0028-unified-agent-memory.md`, `03-consolidation-decay-privacy.md`,
`04-measurement-scorecard.md`.

## Ground rules

- Every capability ships behind a flag that defaults OFF (platform AND tenant AND agent).
- Nothing learned becomes TRUSTED without a human `approve`. Derived items take the lowest trust of their sources.
- Each work package (WP) is its own PR-sized change with tests, rollback, and a CHANGELOG line.
- Gate to turn anything ON: scorecard gates G1-G11 pass (04), with hard-zero gates for poisoning, isolation and forgetting.
- Phase 1 must not be turned ON before Phase 0 packages B (persisted taint) and P (delegated-turn taint) are merged.

## Work packages

| WP | Scope | Depends on | Flyway |
|---|---|---|---|
| 0 | Fix inventory gaps that are bugs today: scratchpad subject column + taint gate (G5), session summary taint flag (G8), `knowledge_parent_chunk` trust (G7), `purgeCreatedBefore` spares `#mem:` blocks (G9), stale comment in `MemoryImportService` | Phase 0 merged | claim via `flyway-lint.sh` |
| 1 | Seams for measurement: per-agent memory on/off switch with recall trace, injectable `Clock`, per-turn token/latency counter | none | none |
| 2 | Ports `agents.memory.spi` in kernel; new module `core/agents-memory`; `SemanticMemorySource` adapter over `agent_memory`/`tenant_memory`/graph | 1 | none |
| 3 | `memory_item`, `memory_item_source`, `memory_event` (ADR V679) and `memory_competence` (V680); service, trust CHECKs, lineage, generation cap | 2 | V679, V680 (re-check numbers) |
| 4 | Episodic write path (post-turn, proposals only) and recall merge inside the existing pre-turn read; 800-token prompt budget | 3 | none |
| 5 | Salience/decay (`MemorySalience`), `MemoryConsolidationSweep` in dry-run (7 days, proposals only), circuit breaker, cost caps | 3, 4 | none |
| 6 | Privacy: `/api/memory` patch/activity/restore/export/erase, tombstones, erasure cascade over lineage incl. graph sync, retention classes | 3 | with 3 or own |
| 7 | Memory page UI: kinds, trust badges, approve/reject, edit, delete, settings | 6 | none |
| 8 | Eval harness `core/quality/.../memeval`, datasets, nightly workflow, `memory-scorecard-gate.sh`; extract red-team corpus to a shared resource | 1 | none |
| 9 | Self-model competence counters (verified outcomes only) and graph projection carrying `memory_item_id` | 3, 4 | none |

Procedural memory (reusable skills) stays PENDING by CHECK constraint; promotion is Phase 2.

## Parallelism

- Wave 1 (parallel): WP0, WP1, WP8-datasets.
- Wave 2 (after WP1 and WP2 ports): WP2, WP8-harness.
- Wave 3 (after WP3): WP4, WP5, WP6, WP9 in parallel, disjoint packages.
- Wave 4: WP7 after WP6.
- Each wave integrates through cherry-pick onto the Phase 0 branch, then runs the `app` module tests (AGENTS.md rule 14).

## Rollout

1. Merge dark (all flags OFF); run `make gates` and `app` tests.
2. Enable for internal tenants with human approval for every write; run nightly scorecard.
3. SHIP DARK until the scorecard passes for 7 consecutive nights; then SHIP per tenant opt-in.
4. Rollback: disable the flag; data is retained but not recalled; erasure still works.

## Open decisions for the owner

1. Module placement: new `agents-memory` module vs an existing one (ArchUnit baseline impact unchecked).
2. Whether distillation (LLM-written summaries) is in Phase 1 or deferred (default: deferred, `distiller.enabled=false`).
3. Retention defaults and the 30% nightly forget circuit breaker.
4. Counsel review of the EU AI Act "substantial modification" question for self-updating behaviour.
5. Weights, token budgets and thresholds in the ADR are unmeasured placeholders; they are tuned by the scorecard.
