-- OpenAPI tool import (core/agents, com.agenticai.core.tools.openapi) - backs
-- OpenApiToolSource + OpenApiTool. The "fastest tool-onboarding path": a
-- tenant points the platform at an OpenAPI 3 / Swagger 2 document (URL or
-- pasted text) and every operation in it becomes an agent-callable tool with
-- no code written - name from operationId, description from summary, JSON
-- input schema from the path/query/header parameters plus the request body.
--
-- TENANT-schema tables (no schema qualifier, same convention as
-- user_tool_script / custom_code_tool): they land in whatever schema is on
-- the search_path, so this migration runs once per tenant schema. No
-- tenant_id column - schema-per-tenant means the schema IS the tenancy.
--
-- Version V232: flyway.locations merges controlplane/ and tenant/ into ONE
-- shared version stream, so a number must be free across BOTH folders. V232
-- was reserved for this feature by the integrator; renumber on collision
-- (scripts/ci/flyway-lint.sh is the gate) rather than reusing a taken one.

CREATE TABLE IF NOT EXISTS openapi_tool_source (
    id                 UUID         PRIMARY KEY,
    -- Human label shown in the console ("Petstore", "Internal billing API").
    name               VARCHAR(128) NOT NULL,
    -- Lowercase slug prefixed onto every generated tool name
    -- (<prefix>_<operationId>) so two imported APIs that both expose
    -- "listUsers" can never collide in the LLM's tool catalog. UNIQUE for the
    -- same reason tool_name is unique below: a duplicate would make dispatch
    -- ambiguous, not merely untidy.
    tool_prefix        VARCHAR(32)  NOT NULL UNIQUE,
    -- Where the document was fetched from; NULL when it was pasted. A
    -- refresh re-fetches from here when present, otherwise re-parses spec_text.
    spec_url           TEXT,
    -- The document as last fetched/pasted, kept verbatim so a pasted source
    -- can be re-parsed on refresh and so the console can show what was
    -- imported. TEXT: the real size bound (1 MB) is enforced in the service.
    spec_text          TEXT         NOT NULL,
    spec_title         VARCHAR(256),
    spec_version       VARCHAR(64),
    -- Every operation's path is appended to this at call time. Validated as
    -- a public http(s) origin on save AND re-validated (after DNS resolution)
    -- on every call - see OpenApiUrlGuard.
    base_url           TEXT         NOT NULL,
    -- OpenApiAuthType, stored as the enum NAME (NONE / API_KEY_HEADER / BEARER).
    auth_type          VARCHAR(24)  NOT NULL DEFAULT 'NONE',
    -- Header carrying the key for API_KEY_HEADER (e.g. X-API-Key). Unused for
    -- the other two modes.
    auth_header_name   VARCHAR(128),
    -- AES-GCM via EncryptionService - the same chokepoint every other stored
    -- credential in this platform goes through, so APP_ENCRYPTION_KEY
    -- rotation covers this table too. NEVER plaintext, and never returned by
    -- any endpoint. May hold a {{secret.NAME}} vault reference instead of a
    -- literal value; that is resolved (tenant-scoped) at call time.
    encrypted_secret   TEXT,
    -- Last 4 plaintext chars ONLY, for "...4242" in the console without a
    -- decrypt round-trip (blank for short values - same rule as tenant_secret).
    secret_preview     VARCHAR(16),
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    last_refreshed_at  TIMESTAMPTZ,
    -- Why the last refresh failed, if it did - shown in the console.
    last_error         TEXT,
    created_by         VARCHAR(255),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS openapi_tool (
    id                 UUID         PRIMARY KEY,
    source_id          UUID         NOT NULL REFERENCES openapi_tool_source (id) ON DELETE CASCADE,
    -- The literal name an agent's LLM calls this tool by (<prefix>_<op>).
    tool_name          VARCHAR(64)  NOT NULL UNIQUE,
    -- "METHOD /path/template" - the stable identity of an operation across
    -- refreshes (operationId is optional in the spec and authors rename it).
    operation_key      VARCHAR(600) NOT NULL,
    operation_id       VARCHAR(256),
    http_method        VARCHAR(12)  NOT NULL,
    path_template      TEXT         NOT NULL,
    summary            TEXT,
    description        TEXT,
    -- JSON array of {name, in, required, schema} for path/query/header
    -- parameters - what OpenApiToolExecutor uses to place each input field.
    parameters_json    TEXT         NOT NULL,
    -- NONE / FLATTENED (body object's properties are top-level input fields)
    -- / WRAPPED (body travels under a single "body" input field).
    body_mode          VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    body_content_type  VARCHAR(128),
    -- The JSON Schema handed to the LLM as this tool's inputSchema.
    input_schema_json  TEXT         NOT NULL,
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    deprecated         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_openapi_tool_source_operation UNIQUE (source_id, operation_key)
);

-- Backs OpenApiToolRepository#findRunnable - the chat-turn hot path
-- (AgentToolProvider#resolveOpenApiTools runs it on every turn).
CREATE INDEX IF NOT EXISTS idx_openapi_tool_runnable ON openapi_tool (enabled) WHERE enabled;
CREATE INDEX IF NOT EXISTS idx_openapi_tool_source ON openapi_tool (source_id);
