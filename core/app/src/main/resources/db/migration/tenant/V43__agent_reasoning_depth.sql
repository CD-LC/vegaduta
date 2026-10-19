-- Agent Intelligence research doc (July 2026) - adds a per-agent
-- "reasoning depth" (FAST/CAREFUL/RIGOROUS), see ReasoningDepth's javadoc.
-- FAST is today's unchanged behavior (no extra tool-call verification), so
-- every pre-existing agent backfills to FAST and nothing changes for them
-- until a tenant-admin deliberately opts an agent into RIGOROUS.
ALTER TABLE agent ADD COLUMN reasoning_depth VARCHAR(16) NOT NULL DEFAULT 'FAST';
