-- Self-check of a learned skill draft (learning loop "self-check before showing").
--
-- Before an agent-drafted skill is queued for review, the learning loop asks the agent's own model
-- whether following the draft would have answered the originating request at least as well as what
-- actually happened. The verdict is shown next to the pending draft; a FAIL draft is never stored.
--
-- Additive and nullable: null means "not checked" (the check is off, the model call failed, or the
-- skill was not drafted by the learning loop). Nothing at runtime reads these columns; they are
-- review information only. Tenant-schema table (no tenant_id column), same convention as V235/V590.

ALTER TABLE skill
    ADD COLUMN IF NOT EXISTS self_check_verdict VARCHAR(8)
        CHECK (self_check_verdict IN ('PASS', 'WEAK', 'FAIL')),
    ADD COLUMN IF NOT EXISTS self_check_reason VARCHAR(300),
    ADD COLUMN IF NOT EXISTS self_checked_at TIMESTAMPTZ;
