-- A2-PR1 - data-driven plan_limits table, replacing the hardcoded
-- FREE_MAX_USERS/FREE_MAX_AGENTS/FREE_MAX_WORKFLOWS/PREMIUM_MAX_* constants
-- that used to live in core/tenancy/TenantLimitsServiceImpl.java. Read by
-- the new PlanLimitsService (core/tenancy) - see that class's javadoc for
-- the Redis-cached read path.
--
-- Platform-wide (control_plane schema), same convention as
-- V58__platform_hosted_llm_provider.sql / V92__feature_flags.sql - plan
-- definitions are global, not per-tenant, so this intentionally does NOT
-- live in the tenant/ migration path (see this repo's CLAUDE.md /
-- VegaDuta-PR-Prompts-A2-B1-C1.md A2-PR1 spec: "put plan_limits in the
-- PLATFORM (shared) path").
--
-- plan is the PK (TenantPlan enum name - FREE/PREMIUM/UNLIMITED, exactly
-- one row per plan, never per-tenant). -1 in any max_* column means
-- "no cap" - translated to Integer.MAX_VALUE (maxUsers, a primitive int
-- with no "unlimited" sentinel of its own) or Optional.empty()
-- (maxAgents/maxWorkflows) at the TenantLimits interface boundary in
-- TenantLimitsServiceImpl, matching the exact semantics the pre-refactor
-- UNLIMITED case already used (Integer.MAX_VALUE / Optional.empty()).
--
-- Seed values for max_users/max_agents/max_workflows match the
-- pre-refactor hardcoded constants exactly (FREE 2/5/3, PREMIUM 10/25/15 -
-- see TenantLimitsServiceImpl's FREE_MAX_USERS et al. before this PR).
-- max_kb_collections/byok_allowed/whatsapp_allowed/white_label are new
-- columns per Agentic-AI-Platform-Subscription-Plan-and-GTM-Strategy.md's
-- tier matrix - not yet read/enforced by any caller as of this migration
-- (PlanLimitsService exposes them; wiring enforcement is later PR work,
-- e.g. the knowledge-collection cap note in A2-PR1's own OVERLAP CHECK) -
-- stored now so enforcing them later doesn't need another migration.
CREATE TABLE control_plane.plan_limits (
    plan                VARCHAR(32)  PRIMARY KEY,
    max_users           INTEGER      NOT NULL,
    max_agents          INTEGER      NOT NULL,
    max_workflows       INTEGER      NOT NULL,
    max_kb_collections  INTEGER      NOT NULL,
    byok_allowed        BOOLEAN      NOT NULL DEFAULT FALSE,
    whatsapp_allowed    BOOLEAN      NOT NULL DEFAULT FALSE,
    white_label         BOOLEAN      NOT NULL DEFAULT FALSE
);

INSERT INTO control_plane.plan_limits
  (plan, max_users, max_agents, max_workflows, max_kb_collections, byok_allowed, whatsapp_allowed, white_label)
VALUES
  ('FREE',       2,  5,  3,  1, FALSE, FALSE, FALSE),
  ('PREMIUM',   10, 25, 15,  5, TRUE,  TRUE,  FALSE),
  ('UNLIMITED', -1, -1, -1, -1, TRUE,  TRUE,  TRUE);
