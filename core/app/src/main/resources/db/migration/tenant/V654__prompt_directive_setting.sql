-- V654: prompt_directive_setting - a tenant's owner-set changes to the platform's built-in prompt directives.
-- Tenant schema: unqualified, no tenant_id (the schema is the tenancy). Shared Flyway namespace with controlplane/.
--
-- One row per directive the owner touched; no row means the built-in text, on. directive_key is a stable key from
-- the directive registry (current_date, memory_usage, tool_trust, ...), or the reserved 'custom_instructions',
-- whose text_override is a free-text block appended after the built-ins for every agent. enabled=false removes the
-- directive from the prompt; text_override (NULL = built-in text) replaces its wording.
CREATE TABLE IF NOT EXISTS prompt_directive_setting (
    directive_key VARCHAR(64)  PRIMARY KEY,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    text_override TEXT         NULL,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by    VARCHAR(255) NULL
);
