# ADR: Step journal and resumable jobs/swarms (L1)

Status: first slice implemented behind flags, default OFF. Not run on a real Postgres, not deployed.

## Context

An agent job interrupted mid-run (deploy, OOM, SIGKILL) is failed `ORPHANED` unless it provably did zero work
(`ZeroWorkOrphanRecoveryPolicy`). Reasons: there is no job cost ledger, spend is metered per chat call,
`turns_used` is written BEFORE the model call, tool side effects are not deduped on a request id, and
`delegate_parallel` state lives only in the tool call's stack. Re-queuing a job with `turns_used == max_turns`
would also run zero turns and "succeed" empty, so a naive re-queue is wrong, not merely risky.

## Options

1. Temporal / external workflow engine. Rejected by the roadmap (infra weight, not needed for single-replica).
2. Re-run from the top with idempotent tools only. Cannot cover the model-call double charge; tools are
   mostly not idempotent.
3. **Postgres `run_id + step_id` journal (chosen).** One table `agent_step_journal` (V376, tenant schema),
   best-effort writes, fail-safe reads: a missing row can only mean "no resume point".

## What is built (flags default OFF)

- `agent_step_journal(run_id, step_id, kind, status STARTED|COMPLETED, result_text)`, unique `(run_id, step_id)`.
- `StepJournal` service (`core/agents`, `com.agenticai.core.agents.tools`), no-op unless
  `app.agent-jobs.durability.step-journal-enabled=true`. With ONLY that flag it is observability: rows are
  written, nothing is replayed. Every behaviour change (below: effect dedupe, swarm replay, turn resume)
  additionally needs `resume-from-journal=true` (`StepJournal.isResumeEnabled()`).
- Turns: `AgentJobExecution.runTurns` writes `turn:<n>` STARTED before and COMPLETED after the model call.
- Orphan recovery: `ZeroWorkOrphanRecoveryPolicy` re-queues instead of `ORPHANED` when
  `resume-from-journal=true` and the journal shows the last started turn COMPLETED
  (`journal-turn-completed`: the resumed attempt reuses the result, no model call, nothing re-charged, no
  tool re-run). `AgentJobExecution.runTurns` applies the journal ONLY to an orphan-requeue claim (QUEUED with
  `attempt_count >= 1`, read BEFORE the claim). An approval-park resume (`AWAITING_APPROVAL`) is deliberately
  excluded: its parked turn is STARTED-only, and re-running it after a human approved the action would run two
  turns where the design grants one and repeat the parked action. With `resume-rerun-incomplete-turn=true` it also re-runs a STARTED-only turn
  (`journal-rerun-incomplete-turn`); this can re-bill one model call. Attempt ceiling, queue TTL and cancel
  clauses still apply first.
- Exactly-once tool effects: `EffectJournal` gains an optional `DurableStore` (the `StepJournal`). In a job
  session (`job:<uuid>`) the effect key uses a stable step id instead of the per-process random turn id, so a
  resumed attempt hits the recorded result. Requires `app.tools.effect-journal.enabled=true` as well. Scope
  limit: inside one job an identical durable call (same tool + args) is performed once, even across turns.
- Durable swarms: `delegate_parallel` branches in a job session are journaled per
  `sha256(agent|question|context)`; a COMPLETED branch is replayed (`resumedFromJournal: true`) instead of
  re-run. Same identical-question-within-one-job scope limit.

## Stale rows and what is not journaled

- A fresh run (QUEUED, `attempt_count = 0`, `turns_used = 0`) purges its rows (`run_id` = job id and
  `job:<id>`) before running, so a reused job id cannot replay another run's COMPLETED turn/swarm row.
  Effect rows are keyed by a hash that includes the unique `job:<uuid>` session id; they are not purged.
- Swarm branch FAILURES are not journaled: only a successful answer is cached, so a failed branch is re-run.

## Single-replica assumptions (explicit)

- No cross-replica lease on a run. Safety relies on the existing guarded `claim`/`requeueIfStillRunning`
  UPDATEs (one owner per job) and the orphan sweep's Postgres advisory lock. Two workers racing on one run
  would both see STARTED.
- Effect dedupe is keyed per tenant schema; the in-memory/Redis layers of `EffectJournal` stay per-node.
- Journal writes are best-effort and not in the same transaction as the tool/model side effect: a crash
  between the effect and its record can still repeat that one effect. Closing that window needs
  transactional outbox semantics per tool; out of scope.

## Staged plan

1. (done) table, journal, turn resume, effect backing, swarm branches.
2. Run on real Postgres; kill-9 a staging job at each point (before model, mid-tool, after turn) and verify.
3. Retention job for `agent_step_journal` (index on `created_at` exists); today rows are kept.
4. Multi-turn partial resume with a journaled message history (today resume reuses only the last turn result).
5. Workflow-node runs (`WorkflowExecutionEngine`) on the same journal.

## Risks

- Rerun-incomplete mode can double-bill one model call; non-DURABLE-classified tools are not deduped.
- Journal growth until step 3 (retention is deferred; the table is unbounded meanwhile).
- Effect dedupe across turns of one job may suppress a legitimate identical repeat write.
