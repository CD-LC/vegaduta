-- Gated semantic (embedding-similarity) response caching - a SEPARATE, stronger
-- opt-in than response_caching_enabled (V95, exact-match). Off by default, no
-- behavior change until a tenant explicitly enables it on an agent - same shape
-- as response_caching_enabled (V95) and share_memory_with_tenant (V78). See
-- Agent.semanticResponseCachingEnabled and SemanticResponseCacheService.
ALTER TABLE agent ADD COLUMN semantic_response_caching_enabled BOOLEAN NOT NULL DEFAULT FALSE;
