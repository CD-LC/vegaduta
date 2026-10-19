-- V623: preference_signals - one row each time the person asks for the same kind of style ("shorter", "no emojis",
-- "reply in Hindi"), the raw material the preferences-from-behavior loop COUNTS before it files a PREFERENCE proposal.
-- Tenant schema: unqualified, no tenant_id (the schema is the tenancy). Shared Flyway namespace with controlplane/.
--
-- Only a signal KEY (e.g. 'short-replies', 'language:hindi') and where it came from are stored, never the person's
-- message. Rows older than the counting window are deleted by the loop itself. Inert until
-- app.learning.preferences.enabled=true (on in the personal profile, off elsewhere).

CREATE TABLE IF NOT EXISTS preference_signals (
    id          UUID         PRIMARY KEY,
    subject     VARCHAR(255) NOT NULL,
    agent_id    UUID,
    signal_key  VARCHAR(64)  NOT NULL,
    source      VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_preference_signals_count
    ON preference_signals (subject, signal_key, created_at DESC);
