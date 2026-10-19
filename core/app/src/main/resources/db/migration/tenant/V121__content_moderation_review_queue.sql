-- Phase P6 (content moderation) - the human review queue + the restricted
-- audit sink for the highest-stakes external-classifier matches.
--
-- Version V121 (NOT V101): application.yml's flyway.locations merges
-- controlplane/ and tenant/ into ONE version stream, so a version number must
-- be unique across BOTH folders combined. Per this workstream's coordination,
-- V101-V120 are claimed by other parallel workstreams; V121 is the assigned
-- global next-free slot for P6. Flyway tolerates version gaps (out-of-order:
-- true is already set), so this applies cleanly regardless of which of
-- V101-V120 have landed in this database yet.
--
-- These are TENANT-schema tables (no schema qualifier, so they land in
-- whatever schema is on the search_path): a tenant's flagged content stays
-- inside that tenant's own Postgres schema, inheriting the platform's
-- strongest existing isolation boundary (schema-per-tenant). Both created
-- IF NOT EXISTS, additive-only, no destructive backfill - the convention
-- this project's Flyway setup documents (see application.yml's flyway block).

-- The human review queue. A row is created by ModerationService when a policy
-- pack's signal lands in the review band (between its review and block
-- thresholds); a trust-&-safety reviewer resolves it via
-- ModerationReviewController (approve / block / escalate), and every decision
-- is written to the general audit log.
CREATE TABLE IF NOT EXISTS moderation_review_item (
    id             UUID PRIMARY KEY,
    tenant_id      VARCHAR(255),
    agent_id       UUID,
    session_id     VARCHAR(255),
    content        TEXT,
    policy_pack    VARCHAR(64)  NOT NULL,
    deciding_layer VARCHAR(32)  NOT NULL,
    confidence     INTEGER      NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    reason         TEXT,
    direction      VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reviewed_by    VARCHAR(255),
    reviewed_at    TIMESTAMPTZ,
    review_notes   TEXT
);

-- Backs ModerationReviewItemRepository#findByStatusOrderByCreatedAtDesc - the
-- reviewer's "show me the pending queue, newest first" view.
CREATE INDEX IF NOT EXISTS idx_moderation_review_item_status
    ON moderation_review_item (status, created_at DESC);

-- The RESTRICTED audit sink. Written ONLY by ModerationRestrictedAuditService,
-- ONLY for an ExternalSafetyClassifier match (CSAM / credible violence), and
-- NEVER surfaced through any read endpoint or the general audit log. It stores
-- a coarse category, a confidence, an opaque vendor reference, and a one-way
-- SHA-256 hash of the content - deliberately NOT the raw content and NOT a
-- description. See ModerationRestrictedAudit / ExternalSafetyClassifier javadoc.
--
-- IMPORTANT (legal/vendor sign-off): the shipped ExternalSafetyClassifier is a
-- documented NO-OP - nothing ever writes here until a deployment wires a real,
-- counsel-approved vendor classifier. Retention, access control, and mandated-
-- reporting workflow for this table are a legal decision, not a code default.
CREATE TABLE IF NOT EXISTS moderation_restricted_audit (
    id                   UUID PRIMARY KEY,
    tenant_id            VARCHAR(255),
    agent_id             UUID,
    session_id           VARCHAR(255),
    category             VARCHAR(64),
    confidence           INTEGER     NOT NULL,
    classifier_reference VARCHAR(255),
    content_hash         VARCHAR(64),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
