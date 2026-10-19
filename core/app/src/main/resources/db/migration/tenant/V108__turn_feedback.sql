-- In-core RL subsystem - the human reward signal. One row per explicit
-- human judgement on a completed agent turn (thumbs up/down, a 1-5 rating,
-- a free-text comment, or a corrected/edited answer). Written by
-- FeedbackService; converted to a HUMAN-source rl_reward_record on the
-- matching rl_episode (agent_id + session_id + turn_ref) when one exists.
--
-- Schema-per-tenant: NO tenant_id column - this table only ever exists
-- inside one tenant's own schema, same as every other table under
-- db/migration/tenant (see V52__llm_call_log.sql).
CREATE TABLE turn_feedback (
    id             UUID PRIMARY KEY,
    agent_id       UUID NOT NULL,
    session_id     VARCHAR(128) NOT NULL,
    turn_ref       VARCHAR(128) NOT NULL,
    -- FeedbackSignal enum stored as its name: UP / DOWN / RATING / EDIT.
    signal         VARCHAR(16) NOT NULL,
    -- Only meaningful for signal = RATING (1..5); null otherwise.
    rating         SMALLINT,
    comment        TEXT,
    edited_answer  TEXT,
    created_by     VARCHAR(255),
    created_at     TIMESTAMPTZ NOT NULL
);

-- Per-agent feedback listing / counts for the RL summary, and the
-- (agent, session, turn) lookup FeedbackService uses to find the episode a
-- signal belongs to.
CREATE INDEX idx_turn_feedback_agent_id ON turn_feedback (agent_id, created_at DESC);
CREATE INDEX idx_turn_feedback_turn ON turn_feedback (agent_id, session_id, turn_ref);
