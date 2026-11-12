-- V684: real importance signals for the nightly memory consolidation, and the run row's proposal count
-- (ADR-0028 WP5c; phase 1 design 03, sections 3, 4 and 6).
-- Tenant schema: unqualified, no tenant_id (the schema is the tenancy). Shared Flyway namespace with controlplane/.
--
-- agent_memory and tenant_memory (the legacy fact stores) had no importance, pin or authorship signal, so the
-- consolidation snapshot had to hard-code importance 0.5 and could never forget a TRUSTED fact (minimum salience
-- 0.225 against a forget threshold of 0.12). The three columns carry what the design calls for:
--   importance       0..1 prior assigned at write time (0.8 typed by a person, 0.6 saved by the agent's tool,
--                    0.5 default and imported); the job attenuates it by evidence of use, see FactImportance.
--   pinned           a person asked to keep it: never forgotten, merged away or summarised.
--   person_authored  typed on the Memory page (not learned by the agent): protected by
--                    app.memory.decay.protect-person-authored.
-- Existing rows keep the neutral defaults (0.5, false, false): nothing is backfilled.
--
-- memory_consolidation_runs.proposals counts the MERGE and SUMMARISE proposals a run filed (QUARANTINED + PENDING
-- memory_item rows, a person approves them), so a run's report shows what was filed rather than only what was planned.

ALTER TABLE agent_memory  ADD COLUMN IF NOT EXISTS importance      REAL    NOT NULL DEFAULT 0.5;
ALTER TABLE agent_memory  ADD COLUMN IF NOT EXISTS pinned          BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent_memory  ADD COLUMN IF NOT EXISTS person_authored BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tenant_memory ADD COLUMN IF NOT EXISTS importance      REAL    NOT NULL DEFAULT 0.5;
ALTER TABLE tenant_memory ADD COLUMN IF NOT EXISTS pinned          BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tenant_memory ADD COLUMN IF NOT EXISTS person_authored BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE memory_consolidation_runs ADD COLUMN IF NOT EXISTS proposals INTEGER NOT NULL DEFAULT 0;
