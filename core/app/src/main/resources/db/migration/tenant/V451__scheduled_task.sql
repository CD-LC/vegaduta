-- Scheduled tasks (prompts that run on a recurring schedule) - ChatGPT-Tasks / Claude-scheduled-tasks parity.
--
-- VERSION CLAIM: V451 (tenant dir), assigned by the wave-d orchestrator. Flyway's controlplane/ and tenant/
-- dirs are ONE version namespace; at authoring time the highest on disk were tenant V434 and controlplane V415.
--
-- WHY A NEW TABLE AND NOT agent_schedule (V208/V338)
-- ---------------------------------------------------
-- agent_schedule models "agent + cadence + goal" but is deliberately ONE-PER-AGENT (uq_agent_schedule_agent):
-- its overlap guard, its console and its run history all assume exactly one schedule per agent. A user's
-- "every weekday at 8am summarise my unread email" is a PERSONAL task - one user can own many, several can
-- share one agent, and each needs its own name, delivery target, expiry and run cap. Loosening the V208 unique
-- constraint would change the meaning of "the previous run" for every existing operator schedule, so this is
-- a sibling table that REUSES the machinery rather than a change to it: every firing still goes through
-- AgentJobLauncher#enqueue and lands in agent_job (scheduled_task.id is written to agent_job.schedule_id, so
-- V208's two unique indexes - at most one live job per task, exactly one job per occurrence - guard it too).
--
-- Nothing here executes anything. The tick (ScheduledTaskTicker) and the settle sweep read these rows.

CREATE TABLE IF NOT EXISTS scheduled_task (
    id                    UUID          PRIMARY KEY,
    -- CASCADE like agent_schedule: a task is configuration and is meaningless once its agent is gone.
    agent_id              UUID          NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    name                  VARCHAR(120)  NOT NULL,
    -- Rendered into agent_job.goal at enqueue, so what a run actually did is frozen and auditable.
    prompt                TEXT          NOT NULL,

    -- The friendly schedule the user picked (DAILY | WEEKDAYS | WEEKLY | MONTHLY | CUSTOM) plus its parts, so
    -- the edit form can round-trip it. `cron` is the DERIVED, normalized 6-field Spring cron the tick runs.
    schedule_kind         VARCHAR(12)   NOT NULL,
    schedule_time         VARCHAR(5),
    schedule_day          VARCHAR(8),
    cron                  VARCHAR(64)   NOT NULL,
    timezone              VARCHAR(64)   NOT NULL DEFAULT 'UTC',

    -- IN_APP | EMAIL | WHATSAPP | TELEGRAM | SLACK. delivery_to is the channel's own address form and is
    -- required for everything except IN_APP. Outbound sends go through ChannelSendPort, i.e. the platform's
    -- approval gate and each channel adapter's own opt-in / consent rules - never a side door.
    delivery              VARCHAR(12)   NOT NULL DEFAULT 'IN_APP',
    delivery_to           VARCHAR(255),

    enabled               BOOLEAN       NOT NULL DEFAULT TRUE,
    -- Why a task is paused when the SYSTEM paused it (auto-disable, expiry, run cap); NULL for a user pause.
    disabled_reason       VARCHAR(255),

    -- The tick's claim target (compare-and-set), same idiom as agent_schedule.next_run_at.
    next_run_at           TIMESTAMPTZ,
    last_run_at           TIMESTAMPTZ,
    -- RUNNING | SUCCEEDED | FAILED | CANCELLED | SKIPPED; NULL until the first run.
    last_status           VARCHAR(16),
    last_job_id           UUID,
    -- Final failures in a row (a failure that was retried once and failed again counts ONCE). Reset by a success.
    consecutive_failures  INTEGER       NOT NULL DEFAULT 0,
    run_count             BIGINT        NOT NULL DEFAULT 0,
    -- Optional per-task caps: stop after this many runs / after this instant. NULL = no cap.
    max_runs              INTEGER,
    expires_at            TIMESTAMPTZ,

    -- The human whose authority (and spend ceiling) every firing runs with. Frozen at creation.
    owner_subject         VARCHAR(200)  NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL
);

-- The tick's only read: enabled tasks whose slot has passed. Partial - paused rows are never due.
CREATE INDEX IF NOT EXISTS idx_scheduled_task_due ON scheduled_task (enabled, next_run_at) WHERE enabled;
CREATE INDEX IF NOT EXISTS idx_scheduled_task_owner ON scheduled_task (owner_subject, created_at DESC);

-- One row per firing (history, cost, delivery). Pruned to the newest rows per task by the application; the FK
-- cascade removes them with the task.
CREATE TABLE IF NOT EXISTS scheduled_task_run (
    id               UUID          PRIMARY KEY,
    task_id          UUID          NOT NULL REFERENCES scheduled_task(id) ON DELETE CASCADE,
    -- SCHEDULE (the tick) | MANUAL (run-now) | RETRY (the single automatic retry)
    trigger_kind     VARCHAR(12)   NOT NULL,
    -- ENQUEUED (job created, not yet terminal) | SUCCEEDED | FAILED | CANCELLED | SKIPPED
    status           VARCHAR(16)   NOT NULL,
    attempt          INTEGER       NOT NULL DEFAULT 1,
    started_at       TIMESTAMPTZ   NOT NULL,
    finished_at      TIMESTAMPTZ,
    job_id           UUID,
    cost_usd         NUMERIC(14,6),
    -- NULL until the settle sweep has delivered / given up: IN_APP | DELIVERED | PENDING_APPROVAL | FAILED
    delivery_status  VARCHAR(20),
    error            TEXT,
    -- The settle sweep's exactly-once predicate: FALSE while the run is still waiting on its job.
    settled          BOOLEAN       NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_scheduled_task_run_task ON scheduled_task_run (task_id, started_at DESC);
-- Partial: the sweep only ever looks at the handful of unsettled rows, never the history.
CREATE INDEX IF NOT EXISTS idx_scheduled_task_run_unsettled ON scheduled_task_run (started_at) WHERE NOT settled;
