-- Cost-transparency panel ("what's protecting your spend"): prompt-cache
-- write/read token counts were already being EXTRACTED from Anthropic's
-- response usage metadata (LlmMetricsAdvisor#logCall, from
-- AnthropicApi.Usage#cacheCreationInputTokens/cacheReadInputTokens) and
-- threaded through LlmCallLogService#record as far as
-- LlmPricingCatalog#estimateCostUsd (folded into estimated_cost_usd) - but
-- the two counts themselves were never persisted as their own columns, so
-- no per-call cache-effectiveness history existed to query. These two
-- nullable columns close that gap: same opt-in posture as every other
-- nullable column on this table (trace_id, experiment_variants, etc.) -
-- null for any provider/call that doesn't report cache usage (every
-- non-Anthropic provider today, and any Anthropic call that didn't hit the
-- cache path), never a fabricated 0.
ALTER TABLE llm_call_log ADD COLUMN cache_creation_tokens BIGINT;
ALTER TABLE llm_call_log ADD COLUMN cache_read_tokens BIGINT;

-- Supports LlmCallLogRepository#cacheEffectivenessSince - the tenant-facing
-- spend-protection panel's windowed (e.g. last-7-days) per-provider
-- cache-token-proportion query, filtered to rows that actually carry cache
-- data.
CREATE INDEX idx_llm_call_log_cache_read_tokens ON llm_call_log (started_at) WHERE cache_read_tokens IS NOT NULL;
