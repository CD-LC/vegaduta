-- Per-policy bandit algorithm choice (THOMPSON, the existing default, or
-- UCB1) - see ContextualBanditService#selectArm. Existing rows default to
-- THOMPSON so no behavior changes for any policy an admin already configured.
ALTER TABLE agent_policy ADD COLUMN algorithm VARCHAR(16) NOT NULL DEFAULT 'THOMPSON';
