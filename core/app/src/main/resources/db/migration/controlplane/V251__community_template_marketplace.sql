-- Marketplace layer on top of the community template gallery (core/templates):
-- ratings + an admin-awarded "verified" badge.
--
-- community_template gains:
--   verified/verified_by/verified_at - a platform-admin endorsement layered ON
--     TOP of approval. APPROVED means "cleared moderation, safe to publish";
--     VERIFIED means staff actually exercised/reviewed the template beyond the
--     snapshot allow-list check. The badge is additive - it never changes
--     visibility rules (APPROVED-only on the public surface stays true).
--   rating_count/rating_avg - denormalised aggregates maintained
--     transactionally by CommunityTemplateService on every rating write, so
--     the public gallery can show and ORDER BY rating without a join against
--     the rating table on every page load.
--
-- community_template_rating: one row per (template, rater). rater_subject is
-- the Keycloak subject of the member who rated - needed to enforce one-vote-
-- per-member upsert semantics, but NEVER surfaced on the public ratings
-- endpoint (same privacy posture as submitted_by).
--
-- CONTROL-PLANE table for the same reason as community_template itself (V156):
-- ratings and badges are cross-tenant marketplace data, not tenant-schema data.
--
-- Version 251: controlplane/ and tenant/ share ONE combined Flyway sequence;
-- V250-259 is claimed for this work in docs/AGENT-COORDINATION.md.

ALTER TABLE control_plane.community_template
    ADD COLUMN IF NOT EXISTS verified     BOOLEAN          NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS verified_by  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS verified_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS rating_count BIGINT           NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS rating_avg   DOUBLE PRECISION NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS control_plane.community_template_rating (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id     UUID         NOT NULL
        REFERENCES control_plane.community_template (id) ON DELETE CASCADE,
    rater_subject   VARCHAR(255) NOT NULL,
    rater_tenant_id VARCHAR(255),
    stars           SMALLINT     NOT NULL,
    review          TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_community_template_rating_rater
        UNIQUE (template_id, rater_subject),
    CONSTRAINT chk_community_template_rating_stars CHECK (stars BETWEEN 1 AND 5)
);

-- Backs the public "recent reviews" listing on a template detail page.
CREATE INDEX IF NOT EXISTS idx_community_template_rating_template
    ON control_plane.community_template_rating (template_id, created_at DESC);

-- Backs "has this member already rated X" lookups and future per-member
-- rating history.
CREATE INDEX IF NOT EXISTS idx_community_template_rating_rater
    ON control_plane.community_template_rating (rater_subject);

-- Backs the gallery's sort-by-rating option (APPROVED rows only, so a partial
-- index mirrors idx_community_template_gallery).
CREATE INDEX IF NOT EXISTS idx_community_template_rating_sort
    ON control_plane.community_template (rating_avg DESC, rating_count DESC)
    WHERE status = 'APPROVED';
