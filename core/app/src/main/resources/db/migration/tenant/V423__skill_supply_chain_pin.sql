-- Skill supply-chain pinning (skillsec lane). A skill's text is third-party content that ends up
-- in an agent's context; the controls here are (1) a content-hash pin the owner approves and (2) a
-- cached static-scan risk level, so a skill that CHANGES after approval can be flagged
-- (NEEDS_REVIEW) and, when app.skills.supply-chain.enforce is on, withheld from load_skill until
-- an admin re-approves it.
--
-- skill.content_sha256 (V235) already holds the current canonical hash and is kept up to date on
-- every write, so it is reused as-is. What did not exist is the APPROVED hash, who/when approved
-- it, and a per-component manifest so the review screen can say what changed. All columns are
-- nullable and additive: null approved_sha256 means "no approval recorded yet" (UNPINNED), which is
-- exactly today's behaviour, so there is no backfill and no behaviour change for existing rows.
--
-- approved_manifest is TEXT (JSON written by SkillIntegrity.manifestJson), not JSONB: it is only
-- ever read back whole, and TEXT keeps the JPA mapping a plain String.
ALTER TABLE skill
    ADD COLUMN IF NOT EXISTS approved_sha256   VARCHAR(64),
    ADD COLUMN IF NOT EXISTS approved_by       VARCHAR(128),
    ADD COLUMN IF NOT EXISTS approved_at       TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS approved_manifest TEXT,
    ADD COLUMN IF NOT EXISTS scan_risk         VARCHAR(16);
