-- Long-term agent memory (semantic, cross-session) - the first "self-learning /
-- more dynamic" capability from Agentic-AI-Platform-Competitive-Analysis-2026.md's
-- roadmap. Distinct from SessionMemoryService's Redis-backed, single-conversation
-- chat history: this persists indefinitely (until explicitly forgotten) and is
-- scoped by (agent_id, subject_key) rather than by an ephemeral session_id, so
-- the same real person (a Keycloak user's principal name on the web console, or
-- a WhatsApp contact's phone number via ChannelSession.externalId) is recognized
-- across many separate conversations, not just within one.
--
-- No Hibernate mapping, same reasoning as knowledge_chunks (V10) - pgvector's
-- `vector` type has no JPA column type, so AgentMemoryService talks to this
-- table via raw JdbcTemplate, schema-qualified the same way
-- KnowledgeSchemaSupport does for knowledge_chunks.

CREATE TABLE agent_memory (
    id           UUID PRIMARY KEY,
    agent_id     UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    subject_key  VARCHAR(255) NOT NULL,
    content      TEXT NOT NULL,
    embedding    vector(384) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    use_count    INT NOT NULL DEFAULT 0
);

-- Every recall() filters by (agent_id, subject_key) before the vector distance
-- ORDER BY - this index makes that filter cheap regardless of how many
-- memories accumulate; the distance scan itself stays a plain sequential scan
-- within the filtered rows, same "no ANN index yet" call
-- VectorSchemaInitializer already made for knowledge_chunks (correctness over
-- premature performance tuning at MVP data volumes).
CREATE INDEX idx_agent_memory_agent_subject ON agent_memory(agent_id, subject_key);
