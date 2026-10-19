-- In-core RL subsystem - one component of an episode's composite reward,
-- from a single source. RewardService blends the non-SAFETY records into a
-- weighted, clipped [0,1] scalar, with a SAFETY veto: any SAFETY-source
-- violation caps the final reward at <= 0.1. Keeping each component as its
-- own row (rather than only the blended scalar) is the verifiable-reward /
-- anti-reward-hacking posture - the blend is always reconstructable and
-- auditable from its parts.
--
-- Schema-per-tenant: NO tenant_id column (see V52__llm_call_log.sql).
CREATE TABLE rl_reward_record (
    id           UUID PRIMARY KEY,
    episode_id   UUID NOT NULL,
    -- RewardSource enum stored as its name:
    -- HUMAN / JUDGE / TASK_SUCCESS / COST / LATENCY / SAFETY.
    source       VARCHAR(24) NOT NULL,
    value        NUMERIC(8, 4) NOT NULL,
    weight       NUMERIC(6, 4) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_rl_reward_record_episode ON rl_reward_record (episode_id);
