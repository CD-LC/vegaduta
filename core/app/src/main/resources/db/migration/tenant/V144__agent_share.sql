-- Agent sharing to the external world - a registered tenant's own agent,
-- exposed to anonymous viewers via an unguessable share id. Mirrors the
-- demo sandbox's share_id column on demo_session, but as its own table
-- since a real agent can be shared/revoked/re-shared independently of the
-- agent's own lifecycle (unlike a demo session, which the share dies with).
--
-- Schema-per-tenant: NO tenant_id column (same convention as agent_policy,
-- V112). The cross-tenant lookup ("which tenant owns this share id") lives
-- in the control-plane channel_account_link table via
-- ExternalAccountProvider.AGENT_SHARE - this table is only ever queried
-- AFTER that lookup has already resolved the caller into this tenant's schema.
CREATE TABLE agent_share (
    id            UUID PRIMARY KEY,
    agent_id      UUID NOT NULL,
    share_id      VARCHAR(64) NOT NULL,
    revoked       BOOLEAN NOT NULL DEFAULT FALSE,
    report_count  INTEGER NOT NULL DEFAULT 0,
    viewer_count  BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_agent_share_agent_id UNIQUE (agent_id),
    CONSTRAINT uq_agent_share_share_id UNIQUE (share_id)
);

CREATE INDEX idx_agent_share_share_id ON agent_share (share_id);
