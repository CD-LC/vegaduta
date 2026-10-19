-- Safety & Moderation admin surface (roadmap P6, core-3).
--
-- Backs the four panels on web-admin's /safety page, which until now were
-- labelled "coming soon" against these exact endpoints:
--   GET/PUT  /api/platform/moderation/policy-packs
--   GET/PUT  /api/platform/moderation/thresholds        (no table - see below)
--   GET      /api/platform/moderation/review-queue
--   POST     /api/platform/moderation/review-queue/{id}/decision
--   GET/PUT  /api/platform/moderation/blocklists
--
-- CONTROLPLANE, not tenant: every one of these is a platform-operator concern.
-- Policy packs and blocklists are set BY the operator FOR a tenant (or a whole
-- plan), and the review queue is worked by a trust-&-safety reviewer who sits
-- above any single tenant. Putting them in tenant schemas would make a tenant
-- able to read - and a schema-scoped bug able to write - its own moderation
-- settings, which is exactly backwards.
--
-- VERSION 206: the Flyway version namespace is SHARED across the controlplane
-- and tenant migration directories (one Flyway bean scans both). At the time of
-- writing controlplane reaches V202 and tenant reaches V205, so 206 is the next
-- free number ACROSS BOTH. A duplicate version here is not a merge conflict, it
-- is a production outage - see docs and the 2026-07-31 duplicate-V156 incident.
--
-- Thresholds deliberately have NO table: they are a handful of scalars and
-- platform_settings already exists for exactly this, with a live-reload service
-- in front of it (the same mechanism FreeTierUsageLimiterService uses for the
-- hosted-LLM quota knobs). A three-column table would have been ceremony.

-- ---------------------------------------------------------------------------
-- Policy packs: which deterministic packs are on, for whom.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS control_plane.moderation_policy_pack_setting (
    id          UUID         PRIMARY KEY,
    -- 'PLAN' (applies to every tenant on that plan) or 'TENANT' (one tenant,
    -- and it overrides the plan-level row).
    scope_type  VARCHAR(16)  NOT NULL,
    -- The plan name ('FREE'/'PREMIUM'/'UNLIMITED') or the tenant id.
    scope_key   VARCHAR(255) NOT NULL,
    pack_id     VARCHAR(64)  NOT NULL,
    enabled     BOOLEAN      NOT NULL,
    updated_by  VARCHAR(255),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT moderation_policy_pack_scope_chk CHECK (scope_type IN ('PLAN', 'TENANT'))
);

-- One row per (scope, pack): the upsert key. Without this a PUT could quietly
-- create a second, contradictory row for the same pack and the effective value
-- would depend on row order.
CREATE UNIQUE INDEX IF NOT EXISTS moderation_policy_pack_setting_scope_uk
    ON control_plane.moderation_policy_pack_setting (scope_type, scope_key, pack_id);

-- ---------------------------------------------------------------------------
-- Review queue: flagged content awaiting a human decision.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS control_plane.moderation_review_item (
    id             UUID         PRIMARY KEY,
    tenant_id      VARCHAR(255) NOT NULL,
    -- Which pack/category fired, and the concrete rule inside it. Both are
    -- recorded so a decision can be audited against the policy as it was at
    -- decision time, not as it is today.
    pack_id        VARCHAR(64)  NOT NULL,
    policy_fired   VARCHAR(255) NOT NULL,
    confidence     DOUBLE PRECISION,
    -- A short REDACTED excerpt, never the full payload. The whole point of the
    -- queue is that a human can judge without the raw content being copied into
    -- another store; see ModerationReviewService for the redaction rule.
    excerpt        TEXT,
    source         VARCHAR(64),
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_by     VARCHAR(255),
    decided_at     TIMESTAMPTZ,
    decision_note  TEXT,
    CONSTRAINT moderation_review_status_chk
        CHECK (status IN ('PENDING', 'APPROVED', 'BLOCKED', 'ESCALATED'))
);

-- The queue is read as "oldest pending first" on essentially every load.
CREATE INDEX IF NOT EXISTS moderation_review_item_status_idx
    ON control_plane.moderation_review_item (status, created_at);
CREATE INDEX IF NOT EXISTS moderation_review_item_tenant_idx
    ON control_plane.moderation_review_item (tenant_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Blocklists: users, phone numbers, domains, regions.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS control_plane.moderation_blocklist_entry (
    id          UUID         PRIMARY KEY,
    entry_type  VARCHAR(16)  NOT NULL,
    -- Stored normalised (trimmed, lower-cased for DOMAIN/USER/REGION) so that
    -- "Example.COM " and "example.com" cannot both be blocked separately and
    -- so a lookup is a plain equality match.
    value       VARCHAR(512) NOT NULL,
    reason      TEXT,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by  VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT moderation_blocklist_type_chk
        CHECK (entry_type IN ('USER', 'PHONE', 'DOMAIN', 'REGION'))
);

CREATE UNIQUE INDEX IF NOT EXISTS moderation_blocklist_entry_uk
    ON control_plane.moderation_blocklist_entry (entry_type, value);

-- ---------------------------------------------------------------------------
-- Threshold defaults.
--
-- Conservative on purpose. `pattern` is a deterministic match so its threshold
-- is effectively "did it match"; the semantic judge and the external classifier
-- are probabilistic, and the review band is what routes an uncertain call to a
-- human instead of silently allowing or blocking it. A wide band means more
-- human work and fewer wrong automatic decisions - the right default for a
-- surface that has not been tuned against real traffic yet.
-- ---------------------------------------------------------------------------
INSERT INTO control_plane.platform_setting (key, value, description)
VALUES
    ('platform.moderation.threshold.semantic', '0.80',
     'Confidence at or above which the semantic (LLM-judge) guardrail layer blocks outright.'),
    ('platform.moderation.threshold.external', '0.90',
     'Confidence at or above which the external safety classifier blocks outright, for the highest-stakes categories.'),
    ('platform.moderation.review-band-low', '0.50',
     'Lower edge of the human-review band. Confidence below this is allowed without review.'),
    ('platform.moderation.review-band-high', '0.80',
     'Upper edge of the human-review band. Between low and high, content is queued for a trust-and-safety reviewer rather than auto-decided.')
ON CONFLICT (key) DO NOTHING;
