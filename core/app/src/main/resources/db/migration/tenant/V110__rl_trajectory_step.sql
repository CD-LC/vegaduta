-- In-core RL subsystem - the ordered steps that make up one rl_episode's
-- trajectory (system/user/assistant messages, tool calls, tool results),
-- step_index ascending. Recorded by TrajectoryService (explicit begin/step/
-- end) and by RlTrajectoryAdvisor (one assistant step per real LLM call).
--
-- Schema-per-tenant: NO tenant_id column (see V52__llm_call_log.sql).
CREATE TABLE rl_trajectory_step (
    id           UUID PRIMARY KEY,
    episode_id   UUID NOT NULL,
    step_index   INTEGER NOT NULL,
    role         VARCHAR(32),
    action_type  VARCHAR(64),
    content      TEXT,
    tool_name    VARCHAR(256),
    tokens       BIGINT,
    latency_ms   BIGINT,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_rl_trajectory_step_episode ON rl_trajectory_step (episode_id, step_index);
