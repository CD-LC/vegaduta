-- Phase 0 Stage 2: Flyway baseline for the tables MVP Stages 2-6 already
-- created via `ddl-auto: update`. This migration exists so a CLEAN database
-- ends up identical to what ddl-auto was producing - it is not meant to run
-- against an existing ddl-auto-managed database (see Stage 2's Definition
-- of Done / verification prompt in Agentic-AI-Platform-Phase0-Build-Plan.md
-- for the "drop and re-migrate clean" verification path; an in-place
-- baseline-on-existing-schema flow uses Flyway's `baseline` command instead
-- and is a one-time manual step on a real deployment, not scripted here).
--
-- Column shapes were read directly off the current JPA entities
-- (Agent, LlmProviderCredential, McpServerRegistration, ToolCallLog) to keep
-- this migration and the entity mappings in sync - Stage 2's Definition of
-- Done requires Hibernate's ddl-auto: validate to pass against this exact
-- shape with zero drift.
--
-- NOT included here: knowledge_chunks and the pgvector extension. Those
-- remain managed by VectorSchemaInitializer's idempotent Java-side setup
-- (CREATE EXTENSION IF NOT EXISTS vector, self-healing DROP INDEX for the
-- ivfflat correctness bug documented in that class) because that self-healing
-- behavior doesn't cleanly collapse into a single one-shot migration file.
-- Folding it into Flyway properly is real follow-up work, called out
-- honestly here rather than silently only doing half the job.

CREATE TABLE agent (
    id                    UUID PRIMARY KEY,
    name                  VARCHAR(255) NOT NULL,
    system_prompt         TEXT NOT NULL,
    provider_name         VARCHAR(32) NOT NULL,
    model                 VARCHAR(128) NOT NULL,
    max_tokens            INTEGER NOT NULL,
    temperature           DOUBLE PRECISION NOT NULL,
    knowledge_base_enabled BOOLEAN NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL
);

CREATE TABLE llm_provider_credential (
    id                 UUID PRIMARY KEY,
    provider_name      VARCHAR(32) NOT NULL,
    status             VARCHAR(16) NOT NULL,
    encrypted_api_key  TEXT NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    last_validated_at  TIMESTAMPTZ,
    CONSTRAINT uq_llm_provider_credential_provider_name UNIQUE (provider_name)
);

CREATE TABLE mcp_server_registration (
    id          UUID PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    type        VARCHAR(32) NOT NULL,
    description TEXT,
    enabled     BOOLEAN NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_mcp_server_registration_name UNIQUE (name)
);

CREATE TABLE tool_call_log (
    id            UUID PRIMARY KEY,
    agent_id      UUID NOT NULL,
    session_id    VARCHAR(128) NOT NULL,
    tool_name     VARCHAR(256) NOT NULL,
    tool_input    TEXT,
    tool_output   TEXT,
    error_message TEXT,
    started_at    TIMESTAMPTZ NOT NULL,
    completed_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_tool_call_log_agent_id ON tool_call_log (agent_id);
