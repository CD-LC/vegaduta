-- Semantic (embedding-similarity) LLM response cache. Backs
-- SemanticResponseCacheService, the opt-in "return a cached answer for a
-- semantically-near-identical question" tier layered on top of the exact-match
-- Redis cache (LlmResponseCacheService). Postgres/pgvector rather than Redis
-- because this needs vector-similarity search, exactly like agent_memory
-- (V18) - same embedding model (sidecar, 384-dim), same `vector` column with no
-- Hibernate mapping (raw JDBC in the service), same schema-per-tenant placement.
--
-- Rows carry their own expires_at (a wall-clock TTL, since pgvector rows don't
-- auto-expire the way a Redis key does); the service filters on it at read time
-- and prunes expired/over-cap rows on write.
CREATE TABLE llm_response_cache (
    id           UUID PRIMARY KEY,
    agent_id     UUID NOT NULL,
    question     TEXT NOT NULL,
    answer       TEXT NOT NULL,
    embedding    vector(384) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL
);

-- Every lookup filters by agent_id (a tenant's agents never share cached
-- answers) before the vector distance is computed, and prunes by expires_at -
-- so both columns are worth an index. No ANN index on embedding yet, same
-- "correctness over premature tuning at MVP data volumes" call agent_memory
-- (V18) and knowledge_chunks (V10) make - a per-agent exact scan over a bounded
-- cache is cheap.
CREATE INDEX idx_llm_response_cache_agent ON llm_response_cache (agent_id);
CREATE INDEX idx_llm_response_cache_expires ON llm_response_cache (expires_at);
