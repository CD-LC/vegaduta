-- Extra per-agent sampling controls, alongside max_tokens / temperature / top_p.
-- All optional: NULL (or an empty stop_sequences array) means "not set", i.e. the
-- provider's own default - identical to behavior before this migration, so no
-- backfill is needed. Providers that do not support a given field ignore it.
ALTER TABLE agent ADD COLUMN IF NOT EXISTS top_k             INTEGER;
ALTER TABLE agent ADD COLUMN IF NOT EXISTS stop_sequences    JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE agent ADD COLUMN IF NOT EXISTS frequency_penalty DOUBLE PRECISION;
ALTER TABLE agent ADD COLUMN IF NOT EXISTS presence_penalty  DOUBLE PRECISION;
ALTER TABLE agent ADD COLUMN IF NOT EXISTS seed              INTEGER;
