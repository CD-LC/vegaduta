-- Master Roadmap 2026 item #18 (cost optimization: routing + semantic
-- caching, §G). Backfills every pre-existing agent to false - same "off by
-- default, no behavior change until someone opts in" shape as
-- sarvam_enabled (V63) and share_memory_with_tenant (V78).
ALTER TABLE agent ADD COLUMN response_caching_enabled BOOLEAN NOT NULL DEFAULT FALSE;
