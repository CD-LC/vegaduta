-- Tenant Jev policy (2026-09-25): per-tenant control over WHERE the Jev
-- decision layer may act, not just whose key funds it. Every column is
-- NULLABLE on purpose - NULL means "inherit the platform default" so the
-- empty/absent row is a complete, honest "let the platform decide" state:
--
--   cost_routing      - may Jev sit on the per-turn complexity check
--                       (QueryComplexityClassifier) that rescues a turn to
--                       the agent's economy model?
--   agent_routing     - may Jev pick/flag agents in Quick Task
--                       (AgentRelevanceSelector)?
--   simple_threshold  - sensitivity of the cost-routing noul; the minimum
--                       probability Jev must report before a "probably
--                       complex" turn is downgraded (platform default 0.75).
--
-- agent.cost_routing_jev_threshold is the per-AGENT override of that same
-- threshold (PATCH /api/agents/{id}/cost-routing) - precedence is
-- agent > tenant > platform.

CREATE TABLE jev_policy (
    id                 UUID PRIMARY KEY,
    cost_routing       BOOLEAN,
    agent_routing      BOOLEAN,
    simple_threshold   DOUBLE PRECISION,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL
);

ALTER TABLE agent
    ADD COLUMN cost_routing_jev_threshold DOUBLE PRECISION;
