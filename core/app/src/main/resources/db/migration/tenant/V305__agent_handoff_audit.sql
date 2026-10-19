-- Crew-style multi-agent handoff (chat-time), Stage 2: the durable audit
-- trail of a turn's handoff chain (which agents were invoked, in what
-- order, and how each hop resolved). Companion to V304__agent_specialist.sql
-- - see that file's header for the version-number check and for how this
-- feature differs from agent_collaborator (V56).
--
-- WHY A NEW TABLE, NOT audit_log_entry (V3)
-- -------------------------------------------
-- audit_log_entry is a flat, single-event log (actor/action/target/outcome)
-- built for human/approval actions - it has no notion of an ordered chain,
-- and querying "every hop of turn X, in order" out of it would need a LIKE
-- scan over `detail`. A handoff chain is inherently relational (each hop
-- has a from/to pair, a depth, and belongs to one turn), so it gets its own
-- table with the columns that shape actually needs, the same way
-- agent_experiment_assignment (V265) is its own table rather than a shape
-- forced into a generic one. This does NOT replace audit_log_entry for
-- anything else - human-approval actions keep using that table unchanged.
--
-- RELATIONSHIP TO THE EXISTING STEP-EVENT TRACE
-- ------------------------------------------------
-- AgentStepEventEmitter#handoff (2026-09-09) already emits a live, per-turn
-- "handoff" event on the opt-in SSE step-event side channel - see that
-- class's javadoc. That channel is EPHEMERAL (nothing is persisted, and it
-- only exists for turns that explicitly requested step events) and is
-- reused unchanged by this feature's dispatch path for live-trace parity
-- with the existing agent_collaborator delegation. This table is the
-- DURABLE counterpart: every crew handoff is recorded here regardless of
-- whether the turn asked for step events, so "show me every hop this turn
-- took" is answerable after the fact, not just while a client is streaming.
CREATE TABLE agent_handoff_audit (
    id                UUID          PRIMARY KEY,

    -- The chat session id of the OUTERMOST (root) turn a handoff chain
    -- belongs to - see com.agenticai.core.agents.crew.HandoffChain#turnSessionId.
    -- Not a foreign key: a chat session is a Redis-backed conversation, not
    -- a row in this schema.
    turn_session_id   VARCHAR(255)  NOT NULL,

    -- The agent the end user (or workflow/channel) was originally talking
    -- to when this chain started - constant across every row of one chain.
    root_agent_id     UUID          NOT NULL REFERENCES agent(id) ON DELETE CASCADE,

    -- The two sides of THIS hop.
    from_agent_id     UUID          NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    to_agent_id       UUID          NOT NULL REFERENCES agent(id) ON DELETE CASCADE,

    -- The capability tag the delegating agent asked for - nullable only
    -- because a REFUSED hop (NO_SPECIALIST_FOUND) still needs a row even
    -- when nothing in agent_specialist matched it, and in that case
    -- to_agent_id/from_agent_id still identify the attempt while the tag is
    -- whatever the model asked for (kept in this column regardless of
    -- match - see AgentHandoffAuditService).
    capability_tag    VARCHAR(100),

    -- 1-based hop number within the chain (root -> hop 1 -> hop 2 -> ...),
    -- bounded by com.agenticai.core.agents.crew.HandoffChain#MAX_DEPTH.
    depth             INTEGER       NOT NULL,

    -- The sub-task text sent to the specialist, capped - this is a trace
    -- summary, not a second copy of unbounded model output (same posture
    -- AgentStepEventEmitter's redactor already applies to tool previews).
    sub_task          VARCHAR(2000),

    -- Read back through @Enumerated(STRING) (HandoffStatus) - CHECK
    -- constraint for the same reason V212/V260/V265 constrain their own
    -- enum-backed status columns: one bad hand-written value would make
    -- every read of this table throw, not just that row.
    status            VARCHAR(20)   NOT NULL,

    error_message     VARCHAR(1000),

    started_at        TIMESTAMPTZ   NOT NULL,
    completed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT agent_handoff_audit_status_chk
        CHECK (status IN ('PENDING','SUCCESS','FAILED','DEPTH_EXCEEDED','CYCLE_DETECTED','NO_SPECIALIST_FOUND'))
);

-- The one read every caller of AgentHandoffAuditService#findChainForTurn
-- needs: "every hop of this turn's chain, in order."
CREATE INDEX idx_agent_handoff_audit_turn_session ON agent_handoff_audit (turn_session_id, depth);

-- "Every handoff chain this agent has ever been the entry point for" -
-- future admin/observability view, same shape as idx_agent_experiment_agent_id.
CREATE INDEX idx_agent_handoff_audit_root_agent ON agent_handoff_audit (root_agent_id, created_at DESC);
