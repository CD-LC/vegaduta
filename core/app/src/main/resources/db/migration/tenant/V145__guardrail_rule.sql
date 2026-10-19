-- 2026-07-27 billing audit follow-up: core/guardrails had ZERO plan-awareness
-- - it modeled no concept of a tenant-authored rule at all, only hardcoded
-- Java pattern lists (PatternBasedGuardrailService) and platform-admin-only
-- moderation packs (ModerationPolicyService). This migration introduces the
-- persisted distinction the billing strategy needs: a DEFAULT_TEMPLATE row
-- (platform-seeded safety-net preset, read-only, every tenant gets these for
-- free) vs a CUSTOM row (tenant-authored; authoring is gated to PREMIUM/
-- UNLIMITED in GuardrailRuleService - see that class).
--
-- Version V145: application.yml's flyway.locations merges controlplane/ and
-- tenant/ into ONE version stream, so a version number must be unique across
-- BOTH folders combined (see V121__content_moderation_review_queue.sql's note
-- for the same rule). Highest version present in either folder as of this
-- writing is V144 (tenant/V144__agent_share.sql) - V145 is the next free
-- slot. FLAGGED FOR MERGE: this module was worked in an isolated worktree
-- alongside other parallel core-3 work that may ALSO be claiming a migration
-- number right now - if V145 collides with another branch's migration at
-- merge time, rename this file to the next actually-free number (Flyway
-- tolerates version gaps, out-of-order:true is already set) rather than
-- reusing a taken one.
--
-- TENANT-schema table (no schema qualifier, same convention as
-- moderation_review_item/moderation_restricted_audit in V121): lands in
-- whatever schema is on the search_path, so this migration runs once per
-- tenant schema (new tenant provisioning + TenantSchemaMigrator backfill for
-- existing tenants) and the INSERT below seeds that SAME set of default
-- templates into every tenant's own copy of this table - GuardrailRuleRepository
-- then needs no cross-schema UNION to return "this tenant's defaults + its own
-- customs", a plain "all rows in this schema" query already is that.

CREATE TABLE IF NOT EXISTS guardrail_rule (
    id          UUID PRIMARY KEY,
    tenant_id   VARCHAR(255),
    rule_type   VARCHAR(32)  NOT NULL,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    pattern     TEXT         NOT NULL,
    action      VARCHAR(32)  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Backs GuardrailRuleRepository#findAllByOrderByCreatedAtAsc / #findByRuleTypeOrderByCreatedAtAsc.
CREATE INDEX IF NOT EXISTS idx_guardrail_rule_type
    ON guardrail_rule (rule_type, created_at ASC);

-- Seed the safety-net presets every tenant (including FREE) gets read/use
-- access to for free, deliberately mirroring PatternBasedGuardrailService's
-- own compiled-in HARD_BLOCK_PATTERNS / REDACTION_PATTERNS so the data-driven
-- and hardcoded layers agree on what a "default" looks like. tenant_id is
-- NULL on these rows - nobody authored a preset, see GuardrailRule's javadoc.
-- Fixed ids so this INSERT is idempotent/re-runnable per Flyway checksum
-- rules without relying on gen_random_uuid() (not guaranteed available on
-- every deployment's Postgres without the pgcrypto extension).
INSERT INTO guardrail_rule (id, tenant_id, rule_type, name, description, pattern, action, enabled)
VALUES
    ('00000000-0000-0000-0000-000000000101', NULL, 'DEFAULT_TEMPLATE',
     'Prompt-injection markers',
     'Blocks tool output containing blatant prompt-injection phrasing (e.g. "ignore previous instructions").',
     'ignore\s+(all\s+)?(the\s+)?(above|previous)\s+instructions', 'BLOCK', TRUE),
    ('00000000-0000-0000-0000-000000000102', NULL, 'DEFAULT_TEMPLATE',
     'Cloud/API secret redaction',
     'Redacts common secret-shaped substrings (cloud provider API keys, tokens) before they reach model context.',
     'AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z\-_]{35}|gh[pousr]_[A-Za-z0-9]{36,}', 'REDACT', TRUE),
    ('00000000-0000-0000-0000-000000000103', NULL, 'DEFAULT_TEMPLATE',
     'Private key / JWT redaction',
     'Redacts PEM private key blocks and JWT-shaped strings before they reach model context.',
     '-----BEGIN [A-Z ]*PRIVATE KEY-----|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}', 'REDACT', TRUE)
ON CONFLICT (id) DO NOTHING;
