-- Skill lifecycle, versioning and usage (area "skills" of the personal-agent tranche).
--
-- Until now a custom skill was either enabled or not (V235) and an agent-written draft was simply
-- "disabled + created_by 'agent:...'". This adds the explicit review lifecycle the console and the
-- learning loop need, without changing what the runtime reads:
--
--   * `enabled` stays the ONLY runtime gate (metadataForAgent reads it, nothing else).
--     `status` is the human-facing state and is kept in step with it by the application:
--     enabled = (status = 'ACTIVE'). Existing rows are backfilled below so the invariant holds.
--   * `origin` records where the content came from (IMPORTED archive, LEARNED by an agent,
--     AUTHORED in the editor). Pre-existing rows are AUTHORED unless an agent wrote them.
--   * content_version / skill_version: a snapshot of every version that went live, so an edit of
--     an approved skill can be inspected and rolled back by hand. The skill table keeps only the
--     current content.
--   * uses / successes / failures / last_used_at: the effectiveness signal. `uses` is bumped when
--     load_skill returns the skill; successes/failures only when the learning loop is on (it is
--     the loop that observes turn outcomes). Counters are written with bulk UPDATEs that bypass
--     the entity, so a tool call never invalidates the per-turn skill cache.
--   * frontmatter_extra: the optional Agent Skills frontmatter keys (license, compatibility,
--     metadata, allowed-tools) as JSON text, kept verbatim so import -> export loses nothing.
--     Informational only: allowed-tools never grants a tool.
--
-- Everything is additive and nullable or defaulted: rows written by an older pod during a rolling
-- deploy read as ACTIVE/AUTHORED/version 1, which is exactly the pre-migration behaviour.
-- Tenant-schema tables (no tenant_id column), same convention as V235.

ALTER TABLE skill
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'ARCHIVED')),
    ADD COLUMN IF NOT EXISTS origin VARCHAR(16) NOT NULL DEFAULT 'AUTHORED'
        CHECK (origin IN ('IMPORTED', 'LEARNED', 'AUTHORED')),
    ADD COLUMN IF NOT EXISTS content_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS uses BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS successes BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS failures BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS review_note VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS learned_for_agent_id UUID,
    ADD COLUMN IF NOT EXISTS frontmatter_extra TEXT;

-- Backfill so enabled = (status = 'ACTIVE') holds for every existing row:
-- a disabled agent-written draft is awaiting review, any other disabled skill was switched off.
UPDATE skill SET origin = 'LEARNED' WHERE created_by LIKE 'agent:%';
UPDATE skill SET status = 'PENDING' WHERE enabled = FALSE AND created_by LIKE 'agent:%';
UPDATE skill SET status = 'ARCHIVED' WHERE enabled = FALSE AND status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_skill_status ON skill (status);

-- One row per version that went live. Payload is the same shape as the live skill: description,
-- body and the bundled files as one JSON object {path: content}.
CREATE TABLE IF NOT EXISTS skill_version (
    id             UUID PRIMARY KEY,
    skill_id       UUID          NOT NULL REFERENCES skill (id) ON DELETE CASCADE,
    version        INTEGER       NOT NULL,
    description    VARCHAR(1024) NOT NULL,
    body           TEXT          NOT NULL,
    files          JSONB,
    content_sha256 VARCHAR(64),
    created_by     VARCHAR(128),
    note           VARCHAR(255),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_skill_version UNIQUE (skill_id, version)
);
