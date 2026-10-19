-- Mobile SDK data-ingestion (#157 follow-up: "send all data into agentic
-- platform"). Four tenant-scoped tables, same schema-per-tenant posture as
-- mobile_device/device_command (V24) - no tenant_id column needed here for
-- the same reason none of those have one either.
--
-- device_screen_state is an UPSERT table (one row per device, always
-- overwritten with the latest snapshot) - an agent reading "what's on the
-- screen right now" wants the current state, not a history, and keeping only
-- the latest row avoids an unbounded table for something the phone may push
-- every few seconds. device_notification/device_app_usage_event/
-- device_sms_message are append-only logs - an agent (or a future
-- analytics/audit view) may reasonably want "what happened recently", not
-- just the latest single event.
CREATE TABLE device_screen_state (
    id              UUID PRIMARY KEY,
    device_id       UUID NOT NULL UNIQUE REFERENCES mobile_device (id),
    screen_text     TEXT,
    foreground_app  VARCHAR(255),
    captured_at     TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE device_notification (
    id            UUID PRIMARY KEY,
    device_id     UUID NOT NULL REFERENCES mobile_device (id),
    package_name  VARCHAR(255) NOT NULL,
    title         VARCHAR(500),
    text          TEXT,
    posted_at     TIMESTAMPTZ,
    received_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_device_notification_device_received
    ON device_notification (device_id, received_at DESC);

CREATE TABLE device_app_usage_event (
    id            UUID PRIMARY KEY,
    device_id     UUID NOT NULL REFERENCES mobile_device (id),
    package_name  VARCHAR(255) NOT NULL,
    event_type    VARCHAR(16) NOT NULL,
    event_time    TIMESTAMPTZ,
    received_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_device_app_usage_device_received
    ON device_app_usage_event (device_id, received_at DESC);

CREATE TABLE device_sms_message (
    id             UUID PRIMARY KEY,
    device_id      UUID NOT NULL REFERENCES mobile_device (id),
    address        VARCHAR(64),
    body           TEXT,
    direction      VARCHAR(16) NOT NULL,
    sms_timestamp  TIMESTAMPTZ,
    received_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_device_sms_device_received
    ON device_sms_message (device_id, received_at DESC);
