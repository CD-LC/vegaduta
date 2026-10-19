-- AI + rules readiness review recorded at submit time (2026-10-12). The
-- platform admin sees it next to the template in the moderation queue; it
-- is an aid for the reviewer, never an approval. Nullable: templates that
-- pre-date the review (and every platform-seeded template) simply have none.
ALTER TABLE control_plane.community_template
    ADD COLUMN IF NOT EXISTS ai_review_json TEXT,
    ADD COLUMN IF NOT EXISTS ai_reviewed_at TIMESTAMPTZ;
