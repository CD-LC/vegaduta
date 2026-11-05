-- V685: a corrected fact supersedes the old one instead of sitting next to it (memory eval gate G6, stale values).
-- Tenant schema: unqualified, no tenant_id (the schema is the tenancy). Shared Flyway namespace with controlplane/.
--
-- agent_memory and tenant_memory (the legacy fact stores) stored every fact as a new row: "I moved from Pune to Mumbai"
-- lived next to "I live in Pune", both were recalled, and the stale one reached the answer in half of the update probes.
-- Columns, all NULL/neutral for every existing row (nothing is backfilled, nothing is hidden by this migration):
--   superseded_by   id of the newer fact that replaced this one. A superseded row is never deleted: it stays in the table,
--                   in the person's memory list and export and under erasure, and is only left out of recall. Not a
--                   foreign key on purpose: forgetting the newer fact must not delete or silently resurrect the old one.
--   superseded_at   when it was superseded (the memory clock).
--   supersedes_ids  set on a QUARANTINED row only: the TRUSTED rows it WOULD replace. A quarantined fact has no effect until
--                   a person approves it; approval applies this list and clears it.
--   updated_at      when the fact was last written or re-confirmed (the same text told again refreshes it, no new row).

ALTER TABLE agent_memory  ADD COLUMN IF NOT EXISTS superseded_by  UUID;
ALTER TABLE agent_memory  ADD COLUMN IF NOT EXISTS superseded_at  TIMESTAMPTZ;
ALTER TABLE agent_memory  ADD COLUMN IF NOT EXISTS supersedes_ids UUID[];
ALTER TABLE agent_memory  ADD COLUMN IF NOT EXISTS updated_at     TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE tenant_memory ADD COLUMN IF NOT EXISTS superseded_by  UUID;
ALTER TABLE tenant_memory ADD COLUMN IF NOT EXISTS superseded_at  TIMESTAMPTZ;
ALTER TABLE tenant_memory ADD COLUMN IF NOT EXISTS supersedes_ids UUID[];
ALTER TABLE tenant_memory ADD COLUMN IF NOT EXISTS updated_at     TIMESTAMPTZ NOT NULL DEFAULT now();

-- Recall reads only the live rows of one agent and person (or one person for the shared tier).
CREATE INDEX IF NOT EXISTS idx_agent_memory_live  ON agent_memory (agent_id, subject_key)  WHERE superseded_by IS NULL;
CREATE INDEX IF NOT EXISTS idx_tenant_memory_live ON tenant_memory (subject_key)           WHERE superseded_by IS NULL;
