-- Alerting infrastructure pass (2026-07) - durable storage for every distinct
-- alert delivered to PlatformAlertWebhookController's POST
-- /webhook/alerts/alertmanager receiver (see that entity's javadoc for why
-- this is its own control_plane table rather than a reuse of the
-- tenant-scoped platform_issue table). V137 because tenant/ had already
-- claimed up to V135 and controlplane/ up to V136 by the time this was
-- written - same combined-sequence collision-avoidance reasoning V84/V59's
-- own header comments document (this project's Flyway config merges
-- controlplane/ and tenant/ into one version sequence).
CREATE TABLE control_plane.platform_alert_webhook_event (
    id                  UUID PRIMARY KEY,
    fingerprint         VARCHAR(64) NOT NULL UNIQUE,
    alert_name          VARCHAR(255),
    severity            VARCHAR(32),
    status              VARCHAR(16) NOT NULL,
    summary             VARCHAR(500),
    description         TEXT,
    labels_json         TEXT,
    annotations_json    TEXT,
    generator_url       VARCHAR(1000),
    starts_at           TIMESTAMPTZ,
    ends_at             TIMESTAMPTZ,
    occurrence_count    INT NOT NULL DEFAULT 1,
    first_seen_at       TIMESTAMPTZ NOT NULL,
    last_seen_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_platform_alert_webhook_event_last_seen ON control_plane.platform_alert_webhook_event (last_seen_at DESC);
CREATE INDEX idx_platform_alert_webhook_event_status ON control_plane.platform_alert_webhook_event (status);
