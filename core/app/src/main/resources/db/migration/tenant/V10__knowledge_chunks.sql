-- Closes a real cross-tenant data leak found testing Phase 0 Stage 5's
-- signup flow end-to-end with two independently self-signed-up tenants:
-- knowledge_chunks previously lived only in whatever schema
-- VectorSchemaInitializer's plain JdbcTemplate connection defaulted to
-- (effectively one single shared table for the whole application), because
-- pgvector's `vector` column type has no Hibernate mapping and that table
-- was never brought into the tenant-scoped Flyway set V1__legacy_mvp_baseline.sql
-- explicitly called out as a gap. Every tenant's uploaded documents were
-- visible to every other tenant's knowledge search as a result.
--
-- Moving it here gives every tenant schema (this app's actual isolation
-- boundary) its own knowledge_chunks table, the same way `agent` and
-- `llm_provider_credential` already work - CREATE EXTENSION is database-wide
-- and idempotent, so running it once per tenant schema is harmless.
--
-- Existing tenant schemas provisioned before this migration existed
-- (tenant_b and any signups that predate this fix) do not get this
-- automatically - Spring Boot's own startup Flyway run only ever targeted
-- the default schema, not every tenant schema. See README for the manual
-- backfill command for those.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_chunks (
    id          UUID PRIMARY KEY,
    source      VARCHAR(500),
    chunk_index INT NOT NULL,
    content     TEXT NOT NULL,
    embedding   vector(384) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
