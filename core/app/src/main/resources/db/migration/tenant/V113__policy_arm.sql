-- In-core RL subsystem - one arm of an agent_policy's bandit, carrying its
-- Thompson-sampling Beta(alpha, beta) posterior. alpha/beta both start at 1.0
-- (uniform prior); on each observed reward r in [0,1]: alpha += r,
-- beta += (1 - r), pulls += 1, reward_sum += r. arm_config_json is the
-- opaque config this arm selects (e.g. {"model":"..."} for MODEL_ROUTE,
-- {"temperature":...} for GEN_PARAMS, a prompt_version id for PROMPT_VARIANT).
-- context_bucket scopes the posterior to a coarse context slice (null/empty =
-- the global bucket).
--
-- Schema-per-tenant: NO tenant_id column (see V52__llm_call_log.sql).
CREATE TABLE policy_arm (
    id               UUID PRIMARY KEY,
    policy_id        UUID NOT NULL,
    arm_key          VARCHAR(128) NOT NULL,
    arm_config_json  TEXT,
    alpha            DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    beta             DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    pulls            BIGINT NOT NULL DEFAULT 0,
    reward_sum       DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    context_bucket   VARCHAR(64),
    enabled          BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_policy_arm_policy ON policy_arm (policy_id);
CREATE INDEX idx_policy_arm_lookup ON policy_arm (policy_id, arm_key, context_bucket);
