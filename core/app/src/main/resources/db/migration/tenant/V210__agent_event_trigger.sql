-- Autonomous Agents PR-3 (EVENT TRIGGERS) - agents that start from something
-- HAPPENING rather than from a person asking.
--
-- Before this table an agent could only be started by a human (POST
-- /api/agents/{id}/jobs, V207) or by a clock (agent_schedule, V208). This adds
-- the third source: a platform event already flowing through core/eventbus.
--
-- Like a schedule, a trigger is a JOB PRODUCER and not a second execution path:
-- every firing goes through AgentJobLauncher#enqueue and lands in agent_job, so
-- a triggered run inherits the per-user dollar budget, the tenant message/token
-- caps, the kill switch, the per-turn tool ceiling, the wall clock and the
-- cancel path with no new enforcement code. There is exactly one place agent
-- work executes and adding triggers did not add a third.
--
-- VERSION NUMBER
-- ---------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into ONE
-- version stream, so a number is free only when it is free in BOTH folders (the
-- same paragraph V207/V208 carry - and see docs/ZERO-DOWNTIME-DEPLOY-RULES.md
-- for the 2026-07-31 production outage a duplicate V156 caused; a collision here
-- is an OUTAGE, not a merge conflict). V210 was RESERVED for this PR by V208's
-- own header at authoring time; V211 belongs to approval parking. Checked here:
-- the highest existing version across both folders is 209.
--
-- WHY TENANT AND NOT CONTROLPLANE
-- --------------------------------
-- Tenant, exactly like agent_job (V207) and agent_schedule (V208): a trigger is
-- tenant business configuration and its firing ledger is tenant activity, so
-- both must vanish with the tenant's schema on erasure. Control-plane placement
-- would be a right-to-erasure defect - the same argument V207's header makes,
-- and it applies with more force here because the ledger records, per row, that
-- a specific external message arrived for this tenant at a specific time.

CREATE TABLE agent_event_trigger (
    id                  UUID          PRIMARY KEY,

    -- FK WITH CASCADE, same split agent_schedule (V208) draws and for the same
    -- reason: a JOB is history and must outlive the agent that ran it (hence
    -- agent_job.agent_id is deliberately not a foreign key at all), whereas a
    -- TRIGGER is configuration and is meaningless the moment its agent is gone.
    -- A surviving row would be a subscription that matches events forever and
    -- enqueues jobs against nothing. The cascade is also what lets the dispatcher
    -- skip an agent-existence read on the publishing thread: a trigger cannot
    -- outlive its agent, so there is no orphan case to check for per event.
    agent_id            UUID          NOT NULL REFERENCES agent(id) ON DELETE CASCADE,

    -- Constrained in the application to the NINE constants WorkflowEventTypes
    -- actually declares (AgentTriggerEventTypes), rejected with a 400 at write
    -- time. Deliberately NOT a CHECK constraint: the allow-list grows every time
    -- the platform emits a new event type, and a CHECK would make each of those
    -- a migration. The write path is the only way a row gets here.
    event_type          VARCHAR(128)  NOT NULL,

    -- Optional equality filter over the event payload, as a flat JSON object
    -- ({"channel":"whatsapp"}). Every entry must match for the trigger to fire.
    -- Validated at write time against the fields that event type actually
    -- carries, so an operator finds out about a typo at configuration time
    -- rather than by wondering why nothing ever fires.
    filter_json         TEXT,

    -- Rendered at ENQUEUE into agent_job.goal, so the goal a job actually ran is
    -- frozen and auditable even after the template is edited. Payload values are
    -- interpolated as DATA (sanitized, length-capped, behind an explicit
    -- untrusted-data preamble) - see AgentEventTriggerGoal: an event payload is
    -- attacker-influenced, a channel event ultimately originates from a stranger.
    goal_template       TEXT          NOT NULL,

    enabled             BOOLEAN       NOT NULL DEFAULT TRUE,
    -- Why it is off. 'LOOP_GUARD_TRIPPED' is the one an operator will actually
    -- see and is the whole point of the column: without it, a trigger the loop
    -- guard turned off is indistinguishable from one somebody disabled on
    -- purpose, and the operator re-enables it straight back into the loop.
    disabled_reason     VARCHAR(48),

    -- THE LOOP GUARD's only state. An agent whose own actions publish an event
    -- it is subscribed to runs forever and spends real money - the live example
    -- is GenerateDocumentToolCallback -> DocumentGenerationService ->
    -- DocumentGenerationEventListener -> document.generation.completed -> a
    -- trigger whose agent holds the generate-document tool -> forever, with no
    -- external party involved at all.
    --
    -- The COUNTS ARE NOT STORED HERE. They are derived from
    -- agent_event_trigger_fire, which already has exactly one row per claimed
    -- (trigger, event) and is written under a unique index. Two reasons, both
    -- from the review of the first cut:
    --
    --   1. A counter column is a read-modify-write. Firings genuinely run
    --      concurrently (document generation publishes from an @Async thread;
    --      channel webhooks arrive on concurrent request threads), the entity
    --      has no @Version, and two firings that both read N and both write N+1
    --      lose a count - so the guard undercounted exactly when the firing rate
    --      was highest, which is precisely the moment it exists for. Counting
    --      rows in the ledger cannot lose a fire: each fire IS a row.
    --
    --   2. A tumbling window (reset whenever now - window_started_at > window)
    --      can never catch a loop slower than the window. Two SLIDING counts,
    --      one short and one long, catch both shapes - see
    --      app.agent-jobs.trigger.{window,max-fires-per-window} for the burst
    --      and .{sustained-window,max-fires-per-sustained-window} for the slow
    --      loop the burst cap provably cannot see (20 fires per 5 minutes is one
    --      per 15s, and a document-generation cycle takes longer than that).
    --
    -- Postgres rather than Redis on purpose: WorkflowTriggerRateLimiter (the
    -- existing Redis limiter) lives in core/omnichannel, which DEPENDS ON
    -- core/agents, so reusing it would be a module cycle - and it is documented
    -- fail-open, which a spend guard must never be.
    --
    -- guard_reset_at is what makes re-enabling possible at all once the counts
    -- are derived: the ledger rows that tripped the guard are still there and
    -- would re-trip it on the next event. Re-saving a trigger stamps this, and
    -- every count below only looks at fires AFTER it.
    guard_reset_at      TIMESTAMPTZ,

    last_fired_at       TIMESTAMPTZ,
    fire_count          BIGINT        NOT NULL DEFAULT 0,

    -- The human whose authority every firing runs with, frozen at configure time
    -- and threaded into AgentJobLaunch#requestedBy/#subjectKey. A triggered job
    -- must never be a way to act with an elevated "system" identity no person
    -- has, or to spend against a budget nobody owns - identical reasoning, and
    -- identical mechanism, to agent_schedule.created_by.
    created_by          VARCHAR(200),
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,

    -- One trigger per (agent, event type), which is what makes PUT an upsert
    -- with no create-vs-update distinction for the caller to get wrong - the
    -- same shape agent_schedule uses. Two subscriptions of one agent to one
    -- event type could only differ by filter, and would double every firing for
    -- any event both filters accept, which is the opposite of what the "exactly
    -- one job per event" acceptance criterion promises.
    CONSTRAINT uq_agent_event_trigger_agent_event UNIQUE (agent_id, event_type)
);

-- The dispatcher's ONLY read, and it runs on a webhook request thread for every
-- published event - so it has to be an index seek, not a scan. Partial on
-- `enabled` for the same reason V208's idx_agent_schedule_due is: a disabled
-- trigger is never dispatched and would only bloat the index.
CREATE INDEX idx_agent_event_trigger_dispatch
    ON agent_event_trigger (event_type, enabled) WHERE enabled;

-- ---------------------------------------------------------------------------
-- THE FIRING LEDGER. Three jobs: dedupe, the loop guard's counts, and the
-- operator's answer to "the event arrived - where is my job?".
--
-- DEDUPE, AND EXACTLY WHAT IT DOES AND DOES NOT GUARANTEE. Read this before
-- writing "exactly one job per event" anywhere.
--
-- Two keys, because the platform supplies two very different qualities of
-- identity:
--
--   * event_key = `eventType|runId`, under a UNIQUE index. This is exact, and
--     it is the guard of record for any event whose runId is STABLE across
--     re-publication. Today that is exactly ONE of the nine subscribable types:
--     document.generation.completed, which passes the document job's own id
--     (DocumentGenerationEventListener). The insert goes FIRST and the unique
--     index makes the second delivery lose - never "check then insert", which
--     two concurrent deliveries would both pass.
--
--   * payload_fingerprint, checked over a short time window and NOT unique.
--     The other EIGHT subscribable types are channel events published through
--     WorkflowEventPublisher#publishNewRun, which mints a FRESH RANDOM UUID per
--     call. A provider redelivery therefore re-enters the controller, gets a new
--     runId, and produces a new event_key that the unique index above cannot
--     recognise. That was a real hole: the first line of defence for those
--     paths is InboundMessageDedupService (core/omnichannel), which is Redis
--     SETNX and documented FAIL-OPEN, and two of the nine paths (XDmPollingJob,
--     EmailInboundPollingJob) do not call it at all. So this column is the
--     durable second line: a SHA-256 over the event type and the payload's
--     canonicalised entries, and a trigger will not fire twice on the same
--     fingerprint inside app.agent-jobs.trigger.duplicate-window.
--
--     Suppressing on payload equality is not lossy HERE, which is the reason it
--     is acceptable at all: a channel payload is {channel, fromMasked,
--     bodyLength} and that is the entire information content a trigger can act
--     on (the goal template can only interpolate those fields). Two events with
--     an identical payload would therefore produce two jobs that are identical
--     in every observable respect - and each one costs real LLM budget.
--
-- Net, stated honestly: a redelivery of the SAME event is absorbed exactly for
-- document.generation.completed, and absorbed within the duplicate window for
-- the channel types. Outside that window a channel redelivery CAN still produce
-- a second job. See docs/AUTONOMOUS-AGENTS-PR3-EVENT-TRIGGERS.md.
CREATE TABLE agent_event_trigger_fire (
    id           UUID         PRIMARY KEY,
    trigger_id   UUID         NOT NULL REFERENCES agent_event_trigger(id) ON DELETE CASCADE,
    event_key    VARCHAR(255) NOT NULL,

    -- Hex SHA-256 (64 chars). Nullable so a payload that cannot be canonicalised
    -- never blocks a firing - the exact key above still applies in that case.
    payload_fingerprint VARCHAR(64),

    -- NULLABLE, and that is meaningful rather than sloppy: the row is written
    -- BEFORE the job is created (it is the claim), so a NULL job_id records
    -- "this delivery was claimed but produced no job" - the loop guard tripped
    -- on it, or the trigger had no created_by to run as. An operator asking
    -- "the event arrived, where is my job?" gets an answer from this table.
    job_id       UUID,
    fired_at     TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_agent_event_trigger_fire UNIQUE (trigger_id, event_key)
);

-- For a future retention sweep. This table grows one row per (trigger, matched
-- event) and NOTHING PRUNES IT TODAY - that is a known, documented gap, not an
-- oversight (see docs/AUTONOMOUS-AGENTS-PR3-EVENT-TRIGGERS.md). Growth is
-- bounded per trigger by the loop guard's firing caps, but bounded is not the
-- same as pruned, and the index is here so that whoever adds the sweep does not
-- also have to add a migration under time pressure.
CREATE INDEX idx_agent_event_trigger_fire_age ON agent_event_trigger_fire (fired_at);

-- THE LOOP GUARD'S READ. Both sliding counts (burst and sustained) are
-- `count(*) WHERE trigger_id = ? AND fired_at >= ?`, on a webhook request
-- thread, once per firing - so it has to be an index range scan over one
-- trigger's rows, not a scan of the tenant's whole firing history.
CREATE INDEX idx_agent_event_trigger_fire_window
    ON agent_event_trigger_fire (trigger_id, fired_at);

-- THE DUPLICATE-PAYLOAD READ, same shape and same reason: an EXISTS over one
-- trigger's rows for one fingerprint inside the duplicate window.
CREATE INDEX idx_agent_event_trigger_fire_fingerprint
    ON agent_event_trigger_fire (trigger_id, payload_fingerprint, fired_at);
