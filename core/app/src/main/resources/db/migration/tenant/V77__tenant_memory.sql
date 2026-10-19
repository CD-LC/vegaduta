-- Shared cross-agent memory tier (#847) - a tenant-wide memory pool that any
-- agent (with sharing turned on) can write to and read from, alongside
-- (never replacing) each agent's own private agent_memory (V18). Same
-- reasoning as agent_memory: scoped by subject_key (the real person on the
-- other end - a Keycloak principal name or a WhatsApp phone number via
-- ChannelSession.externalId) so a fact learned about someone by one agent
-- follows that same person into a conversation with a different agent in
-- this tenant, instead of staying siloed behind whichever single agent
-- happened to learn it first.
--
-- Deliberately a separate table, not a "scope" column on agent_memory:
-- agent_memory's queries are always agent_id-scoped by design (private
-- recall must never leak between agents), and its 50-memory cap is
-- per-agent - a tenant-wide pool needs its own, larger cap and its own
-- unscoped query shape, which is cleaner as a distinct table than as an
-- agent_id-nullable special case bolted onto agent_memory.
--
-- No Hibernate mapping, same reasoning as agent_memory/knowledge_chunks -
-- pgvector's `vector` type has no JPA column type, so TenantMemoryService
-- talks to this table via raw JdbcTemplate, schema-qualified the same way
-- TenantMemorySchemaSupport (mirroring AgentMemorySchemaSupport) does.

CREATE TABLE tenant_memory (
    id                 UUID PRIMARY KEY,
    subject_key        VARCHAR(255) NOT NULL,
    content            TEXT NOT NULL,
    embedding          vector(384) NOT NULL,
    created_by_agent_id UUID REFERENCES agent(id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    use_count          INT NOT NULL DEFAULT 0
);

-- Every recall() filters by subject_key before the vector distance ORDER BY -
-- this index makes that filter cheap. created_by_agent_id is SET NULL (not
-- CASCADE) on agent deletion - a shared fact a deleted agent contributed
-- stays available to the rest of the tenant, it just loses its provenance
-- pointer, since the whole point of this table is that a fact outlives any
-- single agent's lifecycle.
CREATE INDEX idx_tenant_memory_subject ON tenant_memory(subject_key);
