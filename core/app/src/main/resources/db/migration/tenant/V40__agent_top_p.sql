-- Adds nucleus-sampling (top_p) as a per-agent generation parameter,
-- alongside the pre-existing max_tokens/temperature columns - direct
-- feedback: "customer should be able to select the temperature, p and all
-- other details they can set on agents." 1.0 is the provider's own default
-- (no narrowing of the sampling distribution), so every pre-existing agent
-- backfills to behavior identical to before this column existed.
ALTER TABLE agent ADD COLUMN top_p DOUBLE PRECISION NOT NULL DEFAULT 1.0;
