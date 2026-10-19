-- Store compliance (the app-store compliance requirements (described inline)):
-- Google Play's AI-Generated Content policy requires in-app reporting of
-- offensive AI output "to developers". The developer here is the platform
-- admin, who must see reports from EVERY tenant - so this lives in
-- control_plane (same reason device_pairing_code, V36, does), not in a
-- tenant schema that a platform-admin token has no search_path into.
--
-- tenant_id is TenantContext's tenant id, i.e. the tenant SCHEMA name (the
-- JWT tenant_id claim), not the control_plane.tenant UUID. reporter_id is
-- the Keycloak subject; it is nulled (not the row deleted) when that user
-- deletes their account (AccountSelfDeletionService), so the moderation
-- record survives without pointing at a person.
CREATE TABLE control_plane.ai_content_report (
    id            UUID PRIMARY KEY,
    tenant_id     VARCHAR(63),
    reporter_id   VARCHAR(255),
    surface       VARCHAR(16)   NOT NULL,
    reason        VARCHAR(32)   NOT NULL,
    comment       VARCHAR(1000),
    content       TEXT          NOT NULL,
    prompt        TEXT,
    agent_id      VARCHAR(128),
    session_id    VARCHAR(255),
    message_ref   VARCHAR(255),
    on_device     BOOLEAN       NOT NULL DEFAULT FALSE,
    app_platform  VARCHAR(16),
    app_version   VARCHAR(32),
    status        VARCHAR(16)   NOT NULL DEFAULT 'OPEN',
    review_note   VARCHAR(2000),
    reviewed_by   VARCHAR(255),
    reviewed_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL
);

-- Platform-admin review queue: GET /api/platform/ai-reports?status=OPEN.
CREATE INDEX idx_ai_content_report_status_created
    ON control_plane.ai_content_report (status, created_at DESC);

-- Per-user rate limit (30 / hour) count query on every submit.
CREATE INDEX idx_ai_content_report_reporter_created
    ON control_plane.ai_content_report (reporter_id, created_at);
