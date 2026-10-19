-- Mobile SDK (#157) - a paired phone (MobileDevice) and the commands queued
-- for it (DeviceCommand). Ordinary tenant-scoped tables - automatic
-- schema-per-tenant isolation via Hibernate's connection-level schema
-- switch, same as workflow_definition/workflow_run and every other table in
-- this migration path; no tenant_id column needed here for the same reason
-- none of those have one either.
CREATE TABLE mobile_device (
    id            UUID PRIMARY KEY,
    device_name   VARCHAR(120) NOT NULL,
    platform      VARCHAR(16) NOT NULL,
    device_token  VARCHAR(64) NOT NULL UNIQUE,
    created_at    TIMESTAMPTZ NOT NULL,
    last_seen_at  TIMESTAMPTZ,
    revoked       BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE device_command (
    id              UUID PRIMARY KEY,
    device_id       UUID NOT NULL REFERENCES mobile_device (id),
    command_type    VARCHAR(64) NOT NULL,
    payload         TEXT,
    status          VARCHAR(16) NOT NULL,
    result          TEXT,
    workflow_run_id UUID,
    created_at      TIMESTAMPTZ NOT NULL,
    delivered_at    TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ
);

-- DeviceCommandService#nextPendingCommand's poll query - one device, one
-- status, oldest first.
CREATE INDEX idx_device_command_device_status_created
    ON device_command (device_id, status, created_at);
