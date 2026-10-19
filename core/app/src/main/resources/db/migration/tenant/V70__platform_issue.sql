-- Admin Issues portal (#425/#426): "apply the event-driven pattern where it
-- fits, keep sync where needed". Failure paths (unhandled exceptions in
-- GlobalExceptionHandler, WorkflowRun reaching FAILED, ToolCallLog errors)
-- write here SYNCHRONOUSLY and best-effort at the moment of failure - not
-- via the Kafka/outbox event pipeline - because an issue record must still
-- be captured even if Kafka/the broker itself is down, and volume here is
-- inherently low (failures, not routine traffic). Rows are deduplicated by
-- fingerprint (source + source_id + a normalized message) so a repeating
-- failure accumulates occurrence_count/last_seen_at on one row instead of
-- flooding the table - the "aggregation, not a firehose" shape an admin
-- triage view needs.
--
-- Renumbered from V67 to V70 for the same reason V66 -> V69 above (this
-- file's original V67 collided with controlplane/V67__provider_model_catalog_modalities.sql).

CREATE TABLE platform_issue (
    id                UUID PRIMARY KEY,
    fingerprint       VARCHAR(128) NOT NULL,
    source            VARCHAR(32)  NOT NULL,
    source_id         VARCHAR(128),
    severity          VARCHAR(16)  NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    title             VARCHAR(255) NOT NULL,
    message           TEXT,
    occurrence_count  INTEGER      NOT NULL DEFAULT 1,
    first_seen_at     TIMESTAMPTZ  NOT NULL,
    last_seen_at      TIMESTAMPTZ  NOT NULL,
    acknowledged_by   VARCHAR(255),
    acknowledged_at   TIMESTAMPTZ,
    resolved_at       TIMESTAMPTZ,
    CONSTRAINT uq_platform_issue_fingerprint UNIQUE (fingerprint)
);

CREATE INDEX idx_platform_issue_status ON platform_issue (status);
CREATE INDEX idx_platform_issue_last_seen_at ON platform_issue (last_seen_at);
CREATE INDEX idx_platform_issue_source ON platform_issue (source);
