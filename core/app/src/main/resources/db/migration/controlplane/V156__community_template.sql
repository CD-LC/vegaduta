-- Community template gallery (core/templates) - a sanitised, moderated,
-- publicly-browsable catalogue of agent/workflow *designs*. A member snapshots
-- one of their own agents/workflows (server-side ALLOW-LIST: only known-safe
-- design fields - system prompt, model/provider choice, tool/MCP names,
-- guardrail settings, workflow graph; never keys, secrets, tenant ids, PII, or
-- created_by), an admin moderates it (DRAFT -> PENDING -> APPROVED/REJECTED/
-- WITHDRAWN), and approved templates become importable into any tenant as a
-- brand-new agent/workflow. See docs/COMMUNITY-TEMPLATES-CONTRACT-2026-07-30.md.
--
-- CONTROL-PLANE table (schema-qualified control_plane.*), NOT a tenant-schema
-- table: an approved template is visible to the whole world, so it cannot live
-- inside any single tenant's schema (unlike agent / workflow_definition). This
-- is why it goes in controlplane/ and is run once, globally, rather than once
-- per tenant schema. source_tenant_id records provenance for admin
-- traceability but is NEVER part of config_json and never shown publicly.
--
-- VERSION V156: application.yml's flyway.locations merges controlplane/ and
-- tenant/ into ONE version stream, so the number must be unique across BOTH
-- folders combined (see V147__resource_sharing.sql's note for the same rule).
-- Highest version present in either folder at authoring time was
-- tenant/V155__backfill_agent_workflow_owner.sql - V156 is the next free slot.
-- FLAGGED FOR MERGE: this module was built in an isolated worktree alongside
-- other parallel core-3 work that may also be claiming a migration number - if
-- V156 collides at merge time, rename to the next actually-free number (Flyway
-- tolerates gaps, out-of-order:true is already set) rather than reusing a taken
-- one.
CREATE TABLE IF NOT EXISTS control_plane.community_template (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind              VARCHAR(16)  NOT NULL,
    title             VARCHAR(200) NOT NULL,
    summary           TEXT,
    category          VARCHAR(64),
    -- Tags as a JSON-array string (not a Postgres text[]) - keeps the Hibernate
    -- validate mapping trivially correct and avoids an array-type binding; tag
    -- filtering happens in the service layer (the gallery is small-scale).
    tags              TEXT,
    status            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    -- The sanitised allow-list snapshot. Safe to expose on the public detail
    -- endpoint precisely because no secret is ever written into it.
    config_json       TEXT         NOT NULL,
    source_tenant_id  VARCHAR(255),
    submitted_by      VARCHAR(255),
    submitted_at      TIMESTAMPTZ,
    reviewed_by       VARCHAR(255),
    reviewed_at       TIMESTAMPTZ,
    reject_reason     TEXT,
    import_count      BIGINT       NOT NULL DEFAULT 0,
    version           INTEGER      NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_community_template_kind CHECK (kind IN ('AGENT', 'WORKFLOW')),
    CONSTRAINT chk_community_template_status
        CHECK (status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'))
);

-- Backs the public gallery query (APPROVED only, filtered by kind/category,
-- ordered by reviewed_at). Partial index on the hot APPROVED path.
CREATE INDEX IF NOT EXISTS idx_community_template_gallery
    ON control_plane.community_template (kind, category, reviewed_at DESC)
    WHERE status = 'APPROVED';

-- Backs the moderation queue (PENDING, oldest submission first).
CREATE INDEX IF NOT EXISTS idx_community_template_pending
    ON control_plane.community_template (submitted_at)
    WHERE status = 'PENDING';

-- Backs "my submissions".
CREATE INDEX IF NOT EXISTS idx_community_template_submitter
    ON control_plane.community_template (submitted_by);

-- Backs the per-tenant-per-day submission abuse cap.
CREATE INDEX IF NOT EXISTS idx_community_template_source_tenant
    ON control_plane.community_template (source_tenant_id, created_at);
