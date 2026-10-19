-- Notification bell (Diagnostics build-out, 2026-08): the tenant-facing
-- human-readable sibling of platform_issue (V70/V86) - see Notification.java's
-- javadoc for how the two relate. recipient_user_id NULL = broadcast to every
-- ROLE_tenant-admin in this schema; non-NULL targets one Keycloak subject.

CREATE TABLE notification (
    id                 UUID PRIMARY KEY,
    recipient_user_id  VARCHAR(255),
    category           VARCHAR(32)  NOT NULL,
    title              VARCHAR(255) NOT NULL,
    body               TEXT,
    severity           VARCHAR(16)  NOT NULL,
    is_read            BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL,
    link               VARCHAR(512),
    related_issue_id   UUID
);

-- Backs findVisible/countUnread/findAllUnreadVisible's "mine or broadcast,
-- newest first" query shape.
CREATE INDEX idx_notification_recipient ON notification (recipient_user_id, is_read, created_at DESC);
CREATE INDEX idx_notification_broadcast ON notification (is_read, created_at DESC) WHERE recipient_user_id IS NULL;
