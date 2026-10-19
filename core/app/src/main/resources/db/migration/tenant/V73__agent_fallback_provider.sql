-- #815 Gap 4 - graceful-degradation/model-fallback routing. Both columns
-- nullable, no backfill: null (no fallback configured) is exactly today's
-- behavior for every existing agent - a provider failure still surfaces to
-- the user unless a tenant explicitly opts an agent into a fallback.
ALTER TABLE agent
    ADD COLUMN fallback_provider_name VARCHAR(32),
    ADD COLUMN fallback_model VARCHAR(128);
