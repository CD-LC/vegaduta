-- Simulation v1 completion (workstream "feat/evals-sim"), extending V266
-- with the controls the C3 spec's edge cases require:
--
--   1. simulation_session.budget_usd - per-run cost cap (default $10.00,
--      the spec's number) copied onto each simulation_run at fan-out.
--      Between turns the worker sums llm_call_log.estimated_cost_usd for the
--      run's "sim:<runId>" replay session and stops the conversation once
--      the cap is exceeded (run ends CANCELLED - a budget-capped run is not
--      a verdict).
--
--   2. Cooperative cancel, exactly the shape eval_run (V213) and agent_job
--      (V207) already use: cancel writers flip cancel_requested(+_by) only;
--      the owning worker observes it between turns and writes the CANCELLED
--      status itself - no second writer ever touches status.
--
--   3. simulation_run.termination - WHY the conversation ended, distinct
--      from the verdict (which is the judge's opinion of WHAT happened):
--      GOAL_MET (simulator declared its objective achieved/exhausted via
--      the sentinel), MAX_TURNS, AGENT_ERROR (target replay threw),
--      SIMULATOR_ERROR (simulator produced nothing usable / broke
--      character), SAFETY_TRIP (reserved - guardrail-tripped turn),
--      NO_PROGRESS (three near-duplicate persona turns - the polite
--      infinite-loop detector). NULL on ERROR/CANCELLED rows.
--
--   4. simulation_run.agent_version_id - the agent_version row the run was
--      bound to at trigger time, the same binding eval_run.agent_version_id
--      (V260) gives regression runs. Nullable: pre-existing rows stay
--      readable and simply never carry a binding.
--
--   5. 'CANCELLED' added to both status CHECK constraints - the enum-backed
--      columns need the widened domain before the entities can write it.
--
-- VERSION NUMBER
-- ---------------
-- Same shared controlplane/tenant Flyway namespace (see V260/V265/V266's
-- headers). Checked at authoring time (2026-09-27): tenant/'s highest is
-- V340 (+ V343 claimed by this workstream's sibling file), controlplane/'s
-- highest is V339. V344 is free in both; this file is the only V344.

ALTER TABLE simulation_session
    ADD COLUMN budget_usd          NUMERIC(10,2) NOT NULL DEFAULT 10.00,
    ADD COLUMN cancel_requested    BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN cancel_requested_by VARCHAR(255),
    ADD CONSTRAINT simulation_session_budget_chk CHECK (budget_usd > 0);

ALTER TABLE simulation_run
    ADD COLUMN cost_usd         NUMERIC(12,6),
    ADD COLUMN agent_version_id UUID,
    ADD COLUMN termination      VARCHAR(24),
    ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT simulation_run_termination_chk CHECK (termination IS NULL OR termination IN
        ('GOAL_MET','GOAL_FAILED','MAX_TURNS','AGENT_ERROR','SIMULATOR_ERROR','SAFETY_TRIP','NO_PROGRESS'));

ALTER TABLE simulation_session DROP CONSTRAINT simulation_session_status_chk;
ALTER TABLE simulation_session
    ADD CONSTRAINT simulation_session_status_chk
    CHECK (status IN ('QUEUED','RUNNING','COMPLETED','CANCELLED'));

ALTER TABLE simulation_run DROP CONSTRAINT simulation_run_status_chk;
ALTER TABLE simulation_run
    ADD CONSTRAINT simulation_run_status_chk
    CHECK (status IN ('QUEUED','RUNNING','PASSED','FAILED','ERROR','CANCELLED'));
