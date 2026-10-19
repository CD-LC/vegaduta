-- Skill self-improvement (P1 developer-extensibility surface) - the
-- improve_skill agent tool's reviewable-draft half. propose_skill (V235)
-- can only create NEW disabled draft skills; it cannot touch an existing
-- approved skill, which left the "agent notices a skill is wrong and fixes
-- it" half of the learning loop unimplemented.
--
-- Model: a staged, pending improvement lives ON the skill row it targets,
-- as parallel pending_* columns, never in the live description/body/files
-- columns - the active skill every agent's prompt advertises stays
-- byte-identical until a workspace admin applies or discards the staged
-- change (same review gate propose_skill already documents: a poisoned
-- conversation can at worst leave a draft for a human to look at). The
-- admin endpoints live on SkillController (.../improvement/apply|discard).
--
-- pending_files is one JSONB object {path: content} rather than rows in
-- skill_file: a staged change must not disturb the live file rows a
-- running agent can still read, and jsonb keeps "pending" a purely
-- additive, droppable shape. On apply the map is validated by the same
-- SkillService.validateFiles limits an import or editor save enforces.
--
-- Version V243: controlplane/ and tenant/ share ONE Flyway version stream.
-- V241 (tenant webhooks) and V242 (inbound safety mode) are this session's;
-- V250-V269 are claimed by the two parallel P2 sessions per
-- docs/AGENT-COORDINATION.md. If it collides at merge time, rename to the
-- next free number.

ALTER TABLE skill
    ADD COLUMN IF NOT EXISTS pending_description  VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS pending_body         TEXT,
    ADD COLUMN IF NOT EXISTS pending_files        JSONB,
    ADD COLUMN IF NOT EXISTS pending_rationale    TEXT,
    ADD COLUMN IF NOT EXISTS pending_proposed_by  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS pending_proposed_at  TIMESTAMPTZ;

-- Review queue lookup: "which skills have an agent-proposed improvement
-- waiting" should not scan the whole table row-by-row. Partial index keeps
-- it cheap - the overwhelming majority of rows never carry a pending change.
CREATE INDEX IF NOT EXISTS idx_skill_pending_improvement
    ON skill (pending_proposed_at)
    WHERE pending_proposed_at IS NOT NULL;
