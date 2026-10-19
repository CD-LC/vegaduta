-- Custom Code Tools (core/agents, com.agenticai.core.tools.code) - backs
-- CustomCodeTool. The "real code" sibling of user_tool_script: a tenant writes
-- an actual Java or Python program and it is compiled/run by the standalone
-- tool-sandbox microservice, never in core's JVM.
--
-- TENANT-schema table (no schema qualifier, same convention as
-- user_tool_script / guardrail_rule): lands in whatever schema is on the
-- search_path, so this migration runs once per tenant schema (new-tenant
-- provisioning + TenantSchemaMigrator backfill for existing tenants). No
-- tenant_id column - schema-per-tenant means the schema IS the tenancy, the
-- same choice user_tool_script made.
--
-- Version V179: application.yml's flyway.locations merges controlplane/ and
-- tenant/ into ONE version stream, so a version number must be unique across
-- BOTH folders combined (see V121__content_moderation_review_queue.sql for the
-- same rule). V178 (tenant/V178__provider_model_catalog_capability_summary.sql)
-- was the highest taken in either folder when this was written, re-checked
-- directly against the filesystem. FLAGGED FOR MERGE: if V179 collides with
-- another branch's migration at merge time, rename this file to the next
-- actually-free number (Flyway tolerates gaps, out-of-order:true is already
-- set) rather than reusing a taken one.

CREATE TABLE IF NOT EXISTS custom_code_tool (
    -- The literal name an agent's LLM calls this tool by. UNIQUE because a
    -- duplicate would make tool dispatch ambiguous for the model, not merely
    -- untidy; CustomCodeToolService checks it up front so the constraint is a
    -- backstop, not the primary error path.
    id                 UUID PRIMARY KEY,
    tool_name          VARCHAR(64)  NOT NULL UNIQUE,
    display_name       VARCHAR(128) NOT NULL,
    description        TEXT,
    -- CustomCodeToolLanguage, stored as the enum NAME (JAVA / PYTHON).
    language           VARCHAR(16)  NOT NULL,
    -- The tenant-authored program. TEXT: the real size bound is
    -- tool-sandbox.max-source-bytes, enforced in the service.
    source_code        TEXT         NOT NULL,
    input_schema_json  TEXT,
    output_description TEXT,
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    -- CustomCodeToolStatus (DRAFT / VALIDATED / REJECTED). Only VALIDATED rows
    -- are ever offered to an agent; a REJECTED row is kept on purpose so the
    -- tenant can read validation_error and fix the code.
    status             VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    validation_error   TEXT,
    -- Hex SHA-256 of source_code, recomputed on every source change.
    source_sha256      VARCHAR(64),
    timeout_ms         INTEGER      NOT NULL DEFAULT 5000,
    memory_mb          INTEGER      NOT NULL DEFAULT 128,
    -- User-visible revision counter bumped on each source change. NOT a
    -- Hibernate @Version optimistic-lock column (see CustomCodeTool's javadoc).
    version            INTEGER      NOT NULL DEFAULT 1,
    created_by         VARCHAR(128),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Backs CustomCodeToolRepository#findAllByEnabledTrueAndStatus - the chat-turn
-- hot path (AgentToolProvider#resolveCustomCodeTools runs it on every turn), so
-- it must never degrade into a sequential scan as a tenant accumulates drafts.
CREATE INDEX IF NOT EXISTS idx_custom_code_tool_runnable
    ON custom_code_tool (enabled, status);

-- Backs the console list view's ordering.
CREATE INDEX IF NOT EXISTS idx_custom_code_tool_created
    ON custom_code_tool (created_at DESC);
