-- Mobile Sentinels (the Mobile Sentinels design (opt-in sync, privacy-first defaults)): on-device
-- monitor agents in the Android/iOS apps that may, only when the tenant policy
-- allows it, sync the events they raise to core.
--
-- mobile_sentinel_policy is a single tenant-wide row (id pinned to 1 by the
-- CHECK). No row is inserted here: the service returns the privacy-first
-- defaults (sync OFF, raw readings OFF, escalation OFF, 30-day retention) when
-- it is absent, and the first admin PUT creates it. allowed_kinds is a
-- comma-separated list of signal kinds; NULL means "all kinds", '' means none.
--
-- mobile_sentinel_event holds one synced event per (user_id, client_event_id);
-- the device-generated client_event_id is the idempotency key, so a retried
-- upload is a no-op. findings and readings are JSON text; readings stays NULL
-- unless the policy allows raw readings. escalated_at/escalated_to record the
-- caregiver escalation (at most one per user+sentinel per 30 minutes). Rows
-- older than the policy's retention_days are purged on ingest, and a user can
-- erase all of their own rows.
CREATE TABLE IF NOT EXISTS mobile_sentinel_policy (
    id                           SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled                      BOOLEAN      NOT NULL DEFAULT TRUE,
    sync_enabled                 BOOLEAN      NOT NULL DEFAULT FALSE,
    allow_raw_readings           BOOLEAN      NOT NULL DEFAULT FALSE,
    allowed_kinds                TEXT,
    caregiver_escalation_enabled BOOLEAN      NOT NULL DEFAULT FALSE,
    retention_days               INT          NOT NULL DEFAULT 30,
    updated_by                   VARCHAR(255),
    updated_at                   TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS mobile_sentinel_event (
    id               UUID         PRIMARY KEY,
    user_id          VARCHAR(255) NOT NULL,
    client_event_id  VARCHAR(64)  NOT NULL,
    sentinel_id      VARCHAR(64),
    sentinel_name    VARCHAR(160),
    template         VARCHAR(64),
    severity         VARCHAR(16)  NOT NULL,
    title            VARCHAR(200),
    summary          TEXT,
    kinds            TEXT,
    findings         TEXT,
    readings         TEXT,
    device_label     VARCHAR(120),
    occurred_at      TIMESTAMPTZ  NOT NULL,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    escalated_at     TIMESTAMPTZ,
    escalated_to     VARCHAR(255),
    CONSTRAINT uq_mobile_sentinel_event_client UNIQUE (user_id, client_event_id)
);

CREATE INDEX IF NOT EXISTS idx_mobile_sentinel_event_user_time ON mobile_sentinel_event(user_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_mobile_sentinel_event_time ON mobile_sentinel_event(occurred_at);
