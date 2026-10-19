-- Omnichannel Build Plan Stage 1: audit log + human-in-the-loop approval
-- gate. No channel adapters exist yet (Stage 2+) - this migration only adds
-- the cross-cutting tables every future channel action will be gated and
-- audited through.

CREATE TABLE audit_log_entry (
    id          UUID PRIMARY KEY,
    actor_id    VARCHAR(255),
    action_type VARCHAR(128) NOT NULL,
    target_type VARCHAR(64),
    target_id   VARCHAR(128),
    outcome     VARCHAR(32) NOT NULL,
    detail      TEXT,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_audit_log_entry_action_type ON audit_log_entry (action_type);
CREATE INDEX idx_audit_log_entry_target_type ON audit_log_entry (target_type);
CREATE INDEX idx_audit_log_entry_created_at ON audit_log_entry (created_at);

CREATE TABLE approval_policy_override (
    id                 UUID PRIMARY KEY,
    action_type        VARCHAR(128) NOT NULL,
    requires_approval  BOOLEAN NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_approval_policy_override_action_type UNIQUE (action_type)
);

CREATE TABLE approval_request (
    id                UUID PRIMARY KEY,
    action_type       VARCHAR(128) NOT NULL,
    summary           TEXT,
    payload_json      TEXT,
    status            VARCHAR(32) NOT NULL,
    requested_by      VARCHAR(255),
    decided_by        VARCHAR(255),
    rejection_reason  TEXT,
    execution_error   TEXT,
    created_at        TIMESTAMPTZ NOT NULL,
    decided_at        TIMESTAMPTZ
);

CREATE INDEX idx_approval_request_status ON approval_request (status);
CREATE INDEX idx_approval_request_action_type ON approval_request (action_type);
