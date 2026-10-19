-- Fallback observability follow-up (#955) - "per-agent fallback counter on
-- LLM telemetry." Both counters default to 0 (no backfill needed: every
-- pre-existing agent's fallback has fired zero times as far as this column
-- knows, which is exactly true - these counters didn't exist before now,
-- so there's no history to reconstruct). last_fallback_at stays NULL until
-- the first fallback ever fires for that agent, same "null means never
-- happened" shape as Agent.fallbackProviderName itself (V73).
ALTER TABLE agent
    ADD COLUMN fallback_triggered_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN fallback_failed_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_fallback_at TIMESTAMPTZ;
