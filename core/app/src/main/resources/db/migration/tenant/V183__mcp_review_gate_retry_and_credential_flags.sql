-- MCP-STDIO-SANDBOXING (2026-08-05) - defensive hardening for the platform's
-- own MCP integration, per core/MCP-STDIO-SANDBOXING-DESIGN.md's option (d)
-- recommendation (a staff-review gate before self-service STDIO registration
-- is allowed to spawn a process) - plus three independent reliability fixes
-- bundled in the same migration since they touch the same table: decrypt-
-- failure visibility, bounded auto-retry with backoff, and a distinct
-- "last attempted" timestamp.
--
-- Version V183: application.yml's flyway.locations merges controlplane/ and
-- tenant/ into ONE version stream, so this number must be unique across BOTH
-- folders combined - same convention V133's header documents. Highest
-- existing number as of this writing: tenant V179 (V179__custom_code_tool.sql),
-- controlplane V182 (V182__retire_grok3_deepseek_v3_groq_gemma_catalog_repair.sql).
-- V183 is the next free number across both.
--
-- TENANT-schema table (no schema qualifier) - mcp_server_registration has
-- always lived here (see V48/V104/V107/V133/V135, all under
-- db/migration/tenant/), so these additive columns follow it.

ALTER TABLE mcp_server_registration
    -- Staff-review gate (McpReviewStatus: NOT_REQUIRED/PENDING/APPROVED/REJECTED).
    -- Existing rows default to NOT_REQUIRED, not PENDING - this migration only
    -- starts gating registrations SAVED FROM NOW ON (McpRegistryService#register
    -- recomputes reviewStatus on every save, including an upsert-by-name
    -- reconfigure of an existing row). Retroactively blocking every
    -- already-running custom STDIO connector on deploy day - some of which are
    -- production tenants' working integrations - would be a disruptive,
    -- out-of-scope behavior change nobody asked for; grandfathering existing
    -- rows in and gating the write path going forward is the safe-forward
    -- direction without an unannounced outage.
    ADD COLUMN IF NOT EXISTS review_status            VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED',
    ADD COLUMN IF NOT EXISTS review_rejection_reason   TEXT,
    ADD COLUMN IF NOT EXISTS requested_by              VARCHAR(255),

    -- Decrypt-failure visibility (McpRegistryService#decryptEnvDetailed) -
    -- distinguishes "ciphertext exists but failed to decrypt/parse" from
    -- "tenant genuinely configured no env vars", both of which previously
    -- collapsed to the same silent empty map.
    ADD COLUMN IF NOT EXISTS credentials_corrupted     BOOLEAN NOT NULL DEFAULT FALSE,

    -- Bounded auto-retry with backoff (McpServerRegistration#markError /
    -- #backoffFor: 5m, 15m, 1h, then a 6h steady state; gives up after
    -- MAX_AUTO_RETRY_ATTEMPTS=8 consecutive failures).
    ADD COLUMN IF NOT EXISTS retry_attempt_count        INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_retry_at               TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS retry_exhausted             BOOLEAN NOT NULL DEFAULT FALSE,

    -- Distinct from connected_at (null on any failure) and updated_at (also
    -- touched by unrelated edits like enabling/disabling) - stamped on every
    -- REAL connect/test attempt, success or failure.
    ADD COLUMN IF NOT EXISTS last_attempted_at           TIMESTAMPTZ;
