-- V239 - xAI Agent Tools API per-agent opt-in (2026-09-23).
--
-- Same shape as V97__agent_e2b_enabled.sql: enables xAI's server-side tools
-- (web_search / x_search / code_execution) on GROK-provider agents. Off by
-- default for every pre-existing agent - these tools execute on xAI's own
-- infrastructure and are billed by xAI per invocation, outside this platform's
-- guardrail/approval wrapping, so enabling must be an explicit per-agent
-- choice, never an upgrade side effect.
--
-- Version number verified free across BOTH migration dirs at write time
-- (tenant max was V238__channel_contacts, controlplane max V237__free_mode_setting
-- - the two dirs share one Flyway namespace per ZERO-DOWNTIME-DEPLOY-RULES).

ALTER TABLE agent ADD COLUMN xai_server_tools_enabled BOOLEAN NOT NULL DEFAULT false;
