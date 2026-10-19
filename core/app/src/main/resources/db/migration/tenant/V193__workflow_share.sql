-- Workflow sharing to the external world - the same anonymous-public-link
-- pattern as V144's agent_share, for a registered tenant's own workflow.
-- Mirrors that table exactly (workflow_id in place of agent_id) - see
-- WorkflowShare.java / WorkflowShareService.java for the Java side.
--
-- Schema-per-tenant: NO tenant_id column (same convention as agent_share).
-- The cross-tenant lookup ("which tenant owns this share id") lives in the
-- control-plane channel_account_link table via
-- ExternalAccountProvider.WORKFLOW_SHARE - this table is only ever queried
-- AFTER that lookup has already resolved the caller into this tenant's schema.
CREATE TABLE workflow_share (
    id            UUID PRIMARY KEY,
    workflow_id   UUID NOT NULL,
    share_id      VARCHAR(64) NOT NULL,
    revoked       BOOLEAN NOT NULL DEFAULT FALSE,
    report_count  INTEGER NOT NULL DEFAULT 0,
    viewer_count  BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_workflow_share_workflow_id UNIQUE (workflow_id),
    CONSTRAINT uq_workflow_share_share_id UNIQUE (share_id)
);

CREATE INDEX idx_workflow_share_share_id ON workflow_share (share_id);
