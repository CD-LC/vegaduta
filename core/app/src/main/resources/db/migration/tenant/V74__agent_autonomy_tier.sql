-- #815 Governance follow-up - per-agent autonomy tier (AgentAutonomyTier).
-- SEMI_AUTONOMOUS backfilled for every existing agent - matches today's
-- exact behavior (defers entirely to the tenant's existing per-action-type
-- ApprovalPolicyService policy), so this migration changes nothing on its
-- own.
ALTER TABLE agent
    ADD COLUMN autonomy_tier VARCHAR(32) NOT NULL DEFAULT 'SEMI_AUTONOMOUS';
