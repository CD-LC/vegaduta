-- In-core RL subsystem - a per-agent, per-dimension bandit policy. dimension
-- is one of the SAFE, non-side-effecting decision points only (MODEL_ROUTE,
-- REASONING_DEPTH, PROMPT_VARIANT, TOOL_SUBSET, GEN_PARAMS) - a tool
-- EXECUTION / side-effecting choice is NEVER modelled as a bandit here.
-- enabled defaults false: nothing explores until an admin turns the
-- dimension on. exploration_rate is the epsilon-floor for forced random
-- exploration on top of Thompson sampling.
--
-- Schema-per-tenant: NO tenant_id column (see V52__llm_call_log.sql).
CREATE TABLE agent_policy (
    id                UUID PRIMARY KEY,
    agent_id          UUID NOT NULL,
    -- PolicyDimension enum stored as its name.
    dimension         VARCHAR(32) NOT NULL,
    enabled           BOOLEAN NOT NULL DEFAULT FALSE,
    exploration_rate  NUMERIC(4, 3) NOT NULL DEFAULT 0.100,
    CONSTRAINT uq_agent_policy_agent_dimension UNIQUE (agent_id, dimension)
);

CREATE INDEX idx_agent_policy_agent_id ON agent_policy (agent_id);
