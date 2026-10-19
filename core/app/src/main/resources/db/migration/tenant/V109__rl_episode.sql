-- In-core RL subsystem - one bandit "episode": a single agent turn whose
-- outcome we want to learn from. context_json captures the observed context
-- bucket/features at decision time; policy_arm_id is whichever policy_arm
-- was selected for this turn (null when no policy was active and the caller
-- used its default). reward is the verifiable composite reward, CLIPPED to
-- [0,1] for the bandit; reward_breakdown_json keeps the raw per-source
-- breakdown that produced it (audit + reward-hacking forensics).
--
-- Schema-per-tenant: NO tenant_id column (see V52__llm_call_log.sql).
CREATE TABLE rl_episode (
    id                     UUID PRIMARY KEY,
    agent_id               UUID NOT NULL,
    session_id             VARCHAR(128) NOT NULL,
    turn_ref               VARCHAR(128) NOT NULL,
    started_at             TIMESTAMPTZ NOT NULL,
    ended_at               TIMESTAMPTZ,
    context_json           TEXT,
    policy_arm_id          UUID,
    reward                 NUMERIC(6, 4),
    reward_breakdown_json  TEXT,
    terminal               BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_rl_episode_agent_id ON rl_episode (agent_id, started_at DESC);
CREATE INDEX idx_rl_episode_turn ON rl_episode (agent_id, session_id, turn_ref);
CREATE INDEX idx_rl_episode_policy_arm_id ON rl_episode (policy_arm_id);
