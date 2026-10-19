-- Autonomous Agents PR-2 (SCHEDULES) - cron-triggered agent jobs.
--
-- Before this table, only WORKFLOWS could run without a human present
-- (workflow_definition.schedule_cron + WorkflowScheduler, V23). An AGENT could
-- only ever act inside an HTTP request somebody made. This table brings the
-- same trigger down to the agent level, deliberately modelled on the workflow
-- schedule API so an operator learns ONE concept, not two.
--
-- A scheduled agent is a JOB PRODUCER, not a second execution path: every
-- firing goes through AgentJobLauncher#enqueue and lands in agent_job (V207).
-- There is exactly one place agent work executes, and adding schedules did not
-- add a second one.
--
-- VERSION NUMBER
-- ---------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into ONE
-- version stream, so a number is free only when it is free in BOTH folders
-- (same paragraph as V207/V175/V155 - and see docs/ZERO-DOWNTIME-DEPLOY-RULES.md
-- for the 2026-07-31 production outage a duplicate V156 caused; a collision here
-- is an OUTAGE, not a merge conflict). Checked at authoring time: tenant/'s
-- highest is V207 (agent_job, this workstream's FOUNDATION PR), controlplane/'s
-- highest is V206, so V208 is the next slot free in both. V209 (agent job output
-- + progress) is claimed by the sibling OBSERVABILITY PR; V210 and V211 are
-- RESERVED for the deferred event-trigger and approval-parking PRs and must not
-- be taken by anything in this fan-out.
--
-- WHY A SEPARATE TABLE AND NOT COLUMNS ON `agent`
-- -----------------------------------------------
-- `agent` is already ~60 columns wide and is read on the chat hot path for
-- every single turn. These fifteen columns are NULL for very nearly every agent
-- a tenant owns, and the counters below are written by a background tick, not by
-- the agent editor. workflow_definition made the opposite choice (schedule_cron
-- lives on the definition) and it is the reason "is this workflow scheduled?"
-- and "when did it last skip?" cannot be answered without loading the whole
-- definition graph.

CREATE TABLE agent_schedule (
    id                      UUID          PRIMARY KEY,

    -- FK WITH CASCADE here, unlike agent_job.agent_id which is deliberately not
    -- a foreign key at all. The two are not inconsistent: a JOB is history and
    -- must outlive the agent that ran it, whereas a SCHEDULE is configuration
    -- and is meaningless the moment its agent is gone - a surviving row would be
    -- a tick that fires forever against nothing, and the overlap guard would
    -- happily let it.
    agent_id                UUID          NOT NULL REFERENCES agent(id) ON DELETE CASCADE,

    -- Stored already NORMALIZED to Spring's 6-field form (seconds first). The
    -- console's frequency picker emits plain 5-field unix cron; CronExpression
    -- rejects that outright ("must consist of 6 fields, found 5"). Normalizing
    -- at write time rather than at every read is the same fix, and the same
    -- reasoning, as WorkflowService#normalizeCron - see AgentCronSchedules.
    cron                    VARCHAR(64)   NOT NULL,
    -- Evaluated in this zone, not UTC. A "9am daily" report that silently means
    -- 2:30pm local is the single most common complaint about cron features, and
    -- storing the zone is what lets DST be handled by the cron library instead
    -- of by arithmetic nobody will get right.
    timezone                VARCHAR(64)   NOT NULL DEFAULT 'UTC',
    -- Rendered at ENQUEUE into agent_job.goal, so the goal a job actually ran is
    -- frozen and auditable even after the template is edited.
    goal_template           TEXT          NOT NULL,

    enabled                 BOOLEAN       NOT NULL DEFAULT TRUE,

    -- The claim target. The tick UPDATEs this to the NEXT slot before deciding
    -- whether to fire, which is what makes a blocked firing a SKIP rather than a
    -- retry that piles up behind the blocking job.
    next_run_at             TIMESTAMPTZ,
    last_fired_at           TIMESTAMPTZ,

    -- DISPLAY POINTER ONLY. The overlap guard must NEVER follow this: it goes
    -- stale the instant somebody starts a manual run for the same agent, and a
    -- stale pointer here is a MISSED overlap - exactly the bug the guard exists
    -- to prevent. The guard queries agent_job BY schedule_id instead
    -- (AgentJobRepository#findFirstByScheduleIdAndStatusInOrderByCreatedAtDesc).
    last_job_id             UUID,

    -- The four columns that answer "is my schedule broken, or is it still
    -- working?" without anyone reading a log. A skip is a normal, expected
    -- outcome (a 5-minute schedule against a 20-minute job skips ~3 times per
    -- run by design); silence about it is what turns normal into mysterious.
    last_skipped_at         TIMESTAMPTZ,
    last_skip_reason        VARCHAR(48),
    consecutive_skip_count  INTEGER       NOT NULL DEFAULT 0,
    total_fire_count        BIGINT        NOT NULL DEFAULT 0,
    total_skip_count        BIGINT        NOT NULL DEFAULT 0,

    -- The human whose authority every firing runs with. Frozen at configure
    -- time and threaded into AgentJobLaunch#requestedBy/#subjectKey - a
    -- scheduled job must never be a way to spend money no human owns, or to act
    -- with an elevated "system" identity no person has.
    created_by              VARCHAR(200),
    created_at              TIMESTAMPTZ   NOT NULL,
    updated_at              TIMESTAMPTZ   NOT NULL,

    -- One schedule per agent, parity with workflow_definition (which likewise
    -- carries exactly one schedule_cron). Multiple cadences per agent is a real
    -- future request, but it changes the overlap guard's meaning entirely -
    -- "the previous run" stops being well defined - so it is a deliberate later
    -- decision, not something to leave accidentally possible now.
    CONSTRAINT uq_agent_schedule_agent UNIQUE (agent_id)
);

-- The tick's only read: enabled schedules whose slot has passed. Partial, since
-- disabled rows are never due and would otherwise bloat the index.
CREATE INDEX idx_agent_schedule_due ON agent_schedule (enabled, next_run_at) WHERE enabled;

-- ---------------------------------------------------------------------------
-- GUARD OF RECORD #1 - overlap. At most ONE live job per schedule, enforced by
-- POSTGRES rather than by application logic.
--
-- This is the point of the whole feature: a 5-minute schedule whose job takes
-- 20 minutes must never spawn four concurrent runs of the same agent. The
-- application does check first (AgentScheduleService#decide, a pure function
-- with its own tests) and that check is what produces a RECORDED skip with a
-- reason an operator can read. But the check cannot be the guarantee -
-- PgAdvisoryJobLock is documented FAIL-OPEN, so during a lock outage two
-- replicas genuinely can reach the enqueue at the same moment. Then this index
-- is what stops the double run, exactly the posture platform_quota_reset_log's
-- unique constraint takes for the same fail-open reason.
--
-- AWAITING_APPROVAL is in the list because it is NON-TERMINAL: a job parked on a
-- human approval gate is still this schedule's outstanding run, and firing
-- another one would queue a second copy behind the same unanswered question.
CREATE UNIQUE INDEX uq_agent_job_live_per_schedule ON agent_job (schedule_id)
    WHERE schedule_id IS NOT NULL AND status IN ('QUEUED','RUNNING','AWAITING_APPROVAL');

-- GUARD OF RECORD #2 - exactly once per occurrence. A genuinely DIFFERENT
-- failure mode from #1, which is why both exist: if the previous run has already
-- finished, two replicas firing the SAME 09:00 slot a millisecond apart both
-- pass #1 and the tenant is billed for two identical LLM runs. Keyed on the SLOT
-- (schedule_occurrence_at), never on the firing instant, so the two replicas
-- collide instead of writing two rows a millisecond apart.
CREATE UNIQUE INDEX uq_agent_job_schedule_occurrence
    ON agent_job (schedule_id, schedule_occurrence_at) WHERE schedule_id IS NOT NULL;
