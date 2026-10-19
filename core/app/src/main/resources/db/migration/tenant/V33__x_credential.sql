-- A tenant's own X (Twitter) connection - two-step BYOK-then-OAuth (see
-- XCredential's javadoc). status starts at APP_REGISTERED once client_id/
-- encrypted_client_secret are set, moves to ACTIVE once the OAuth consent
-- completes and x_user_id/x_username/encrypted_refresh_token are populated.
-- last_dm_event_id is XDmPollingJob's cursor into GET /2/dm_events.

CREATE TABLE x_credential (
    id                          UUID PRIMARY KEY,
    client_id                   VARCHAR(255) NOT NULL,
    encrypted_client_secret     TEXT NOT NULL,
    x_user_id                   VARCHAR(32),
    x_username                  VARCHAR(255),
    encrypted_refresh_token     TEXT,
    last_dm_event_id            VARCHAR(32),
    status                      VARCHAR(16) NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL,
    last_polled_at              TIMESTAMPTZ
);
