-- Scheduled agent triggers (P0) - interval cadence, workflow targets, run history.
--
-- VERSION CLAIM: V338 (tenant dir). Flyway's controlplane/ and tenant/ dirs are ONE
-- version namespace; at authoring time the highest were tenant V334 and controlplane
-- V300, V335/V336/V337 belong to other streams. All three changes below live in this
-- single file on purpose (one claim, one migration).
--
-- 1. INTERVAL SCHEDULES. `cron` becomes nullable and `interval_seconds` is the
--    alternative cadence ("every 15 minutes"). Exactly one of the two must be set,
--    enforced by Postgres (chk_agent_schedule_cadence) and again by the service.
--    Minimum 60 seconds - the tick itself only runs once a minute.
-- 2. WORKFLOW TARGETS. target_type AGENT (default, the pre-V338 behaviour: enqueue an
--    agent_job) or WORKFLOW (run workflow_id). agent_id stays NOT NULL: the schedule is
--    still configured, owned and access-checked through its agent.
-- 3. RUN HISTORY. One small row per firing so "did it run, and how did it end?" is
--    answerable without reading logs. Pruned to the newest rows per schedule by the
--    application; the FK cascade removes them with the schedule.

ALTER TABLE agent_schedule ALTER COLUMN cron DROP NOT NULL;

ALTER TABLE agent_schedule ADD COLUMN IF NOT EXISTS interval_seconds INTEGER;
ALTER TABLE agent_schedule ADD COLUMN IF NOT EXISTS target_type VARCHAR(16) NOT NULL DEFAULT 'AGENT';
ALTER TABLE agent_schedule ADD COLUMN IF NOT EXISTS workflow_id UUID REFERENCES workflow_definition(id) ON DELETE CASCADE;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_agent_schedule_cadence') THEN
        ALTER TABLE agent_schedule ADD CONSTRAINT chk_agent_schedule_cadence CHECK (
            (cron IS NOT NULL AND interval_seconds IS NULL)
            OR (cron IS NULL AND interval_seconds IS NOT NULL AND interval_seconds >= 60));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_agent_schedule_target') THEN
        ALTER TABLE agent_schedule ADD CONSTRAINT chk_agent_schedule_target CHECK (
            (target_type = 'AGENT' AND workflow_id IS NULL)
            OR (target_type = 'WORKFLOW' AND workflow_id IS NOT NULL));
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS agent_schedule_run (
    id               UUID         PRIMARY KEY,
    schedule_id      UUID         NOT NULL REFERENCES agent_schedule(id) ON DELETE CASCADE,
    target_type      VARCHAR(16)  NOT NULL,
    -- SCHEDULE (the tick) or MANUAL (run-now)
    trigger_kind     VARCHAR(16)  NOT NULL,
    -- ENQUEUED (agent job created; live status is resolved from agent_job at read time),
    -- RUNNING / SUCCEEDED / FAILED (workflow runs, updated on completion),
    -- SKIPPED (run-now refused by the overlap guard).
    status           VARCHAR(16)  NOT NULL,
    started_at       TIMESTAMPTZ  NOT NULL,
    finished_at      TIMESTAMPTZ,
    -- agent_job id for AGENT targets, workflow run id for WORKFLOW targets.
    job_id           UUID,
    workflow_run_id  UUID,
    error            TEXT
);

CREATE INDEX IF NOT EXISTS idx_agent_schedule_run_schedule
    ON agent_schedule_run (schedule_id, started_at DESC);
