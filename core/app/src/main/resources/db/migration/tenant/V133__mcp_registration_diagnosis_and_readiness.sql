-- MCP-DOCTOR (2026-07-25) - structured connection diagnosis + readiness gate
-- on dynamic MCP registrations.
--
-- Version V133: application.yml's flyway.locations merges controlplane/ and
-- tenant/ into ONE version stream, so this number must be unique across BOTH
-- folders combined (controlplane is at V132, tenant at V121 as of this
-- writing) - same convention V121's header documents.
--
-- TENANT-schema table (no schema qualifier). Additive-only, IF NOT EXISTS,
-- per the project's Flyway conventions.
--
-- connection_phase / connection_hint: the structured halves of the existing
-- connection_error column - WHICH step failed (SPAWN/HTTP/HANDSHAKE/
-- INIT_TIMEOUT) and an actionable next step ("this server requires
-- credentials - add Authorization = Bearer <token> to its env entries").
--
-- usage_status: the readiness gate (UNTESTED/READY/FAILED/ACKNOWLEDGED) -
-- agents only receive tools from READY/ACKNOWLEDGED registrations. Existing
-- rows default to UNTESTED deliberately: every enabled server re-proves
-- itself on the next startup reconnect (a successful connect marks READY),
-- so a briefly stricter window after this deploy is the fail-safe direction.
ALTER TABLE mcp_server_registration
    ADD COLUMN IF NOT EXISTS connection_phase VARCHAR(32),
    ADD COLUMN IF NOT EXISTS connection_hint  TEXT,
    ADD COLUMN IF NOT EXISTS usage_status     VARCHAR(32) NOT NULL DEFAULT 'UNTESTED';
