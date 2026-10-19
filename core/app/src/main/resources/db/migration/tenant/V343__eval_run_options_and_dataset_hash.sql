-- Evals v1 completion (workstream "feat/evals-sim"): the three run-time
-- controls the C2 spec asks for that V213/V260 did not carry, plus the
-- dataset snapshot hash the promote gate binds to.
--
--   1. eval_dataset.content_hash - SHA-256 hex of the dataset's EVALUATION-
--      RELEVANT content (pass_threshold, tool_execution_enabled, and every
--      case's position/user_message/expected_output/expected_tools, in
--      canonical order). Recomputed in Java (EvalDatasetService) on every
--      mutation. NULL for rows that predate this column until their first
--      touch - the hash is a pure function of current content, so lazy
--      computation is identical to eager and needs no fragile SQL/Java
--      dual-implementation backfill.
--
--   2. eval_run.dataset_hash - the content_hash value snapshotted at
--      trigger time. EvalGateService only accepts a qualifying run whose
--      dataset_hash equals the dataset's CURRENT hash: editing the dataset
--      between run and promote makes the run stale (Reason.DATASET_STALE)
--      instead of silently letting a run testify for cases it never saw.
--
--   3. Run-time judge/cost configuration, snapshotted at trigger:
--        requested_judge_model - optional per-run judge override (applied
--            through JudgeChatClientFactory's scoped override); judge_model
--            stays the resolved provenance column.
--        strict_judge - when true, trigger REFUSES (400) a judge model
--            identical to the agent's own model (self-grading bias); when
--            false the run proceeds and the console warns instead.
--        repeats - 1..5 replay+score repetitions per case; a case passes
--            only when a strict majority of its repetitions pass
--            (non-determinism guard).
--        budget_usd - per-run cost cap, default $5.00. Between cases the
--            worker sums llm_call_log.estimated_cost_usd for the run's
--            replay sessions and CANCELS the run (remaining cases never
--            replayed) once the cap is exceeded - a cancelled run can never
--            satisfy a gate, so an over-budget partial run is never mistaken
--            for a pass.
--        cost_usd - observed spend accumulated during the run.
--        idempotency_key - optional caller-supplied key (the CI hook's
--            Idempotency-Key header); a second trigger with the same key
--            returns the SAME run row instead of a 409 or a duplicate.
--
--   4. eval_run_case_result.passed - the case-level three-valued answer
--      (TRUE passed / FALSE failed / NULL not evaluable, e.g. UNAVAILABLE
--      judges) so the JUnit report and the console's pass/fail chips read a
--      column instead of re-deriving the rule.
--
-- VERSION NUMBER
-- ---------------
-- Same shared controlplane/tenant Flyway namespace as every migration in
-- this line (see V212/V260/V265/V266's headers for the 2026-07-31 outage a
-- collision caused). Checked at authoring time (2026-09-27) by numeric sort
-- of BOTH directories: tenant/'s highest is V340, controlplane/'s highest is
-- V339. V343/V344 are the slots assigned to this workstream; this file is
-- the only V343 in the repository.

ALTER TABLE eval_dataset
    ADD COLUMN content_hash VARCHAR(64);

ALTER TABLE eval_run
    ADD COLUMN dataset_hash          VARCHAR(64),
    ADD COLUMN requested_judge_model VARCHAR(255),
    ADD COLUMN strict_judge          BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN repeats               INTEGER       NOT NULL DEFAULT 1,
    ADD COLUMN budget_usd            NUMERIC(10,2) NOT NULL DEFAULT 5.00,
    ADD COLUMN cost_usd              NUMERIC(12,6),
    ADD COLUMN idempotency_key       VARCHAR(128),
    ADD CONSTRAINT eval_run_repeats_chk    CHECK (repeats BETWEEN 1 AND 5),
    ADD CONSTRAINT eval_run_budget_usd_chk CHECK (budget_usd > 0);

-- Idempotent CI triggers: at most one run per (dataset, key). Partial like
-- uq_eval_run_live_per_dataset because most runs carry no key.
CREATE UNIQUE INDEX uq_eval_run_idempotency_key
    ON eval_run (dataset_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

ALTER TABLE eval_run_case_result
    ADD COLUMN passed BOOLEAN;
