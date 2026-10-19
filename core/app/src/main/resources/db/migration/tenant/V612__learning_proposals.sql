-- V612: learning_proposals - what the assistant WANTS to learn, waiting for the person to say yes.
-- Tenant schema: unqualified, no tenant_id (the schema is the tenancy). Shared Flyway namespace with controlplane/.
--
-- The generic store behind every "the assistant learns" loop (lessons, preferences, routines,
-- consolidation). A loop only files a row here; NOTHING in this table reaches a prompt, a schedule or
-- a stored preference until the person approves it, and approval is dispatched to the loop's applier.
-- Inert until app.learning.proposals.enabled=true (on in the personal profile, off elsewhere).
--
-- body_hash is sha-256 (hex) of the lower-cased, whitespace-collapsed body, so the same advice proposed
-- twice is one row. The unique index covers PENDING rows only: a decided proposal frees the slot (the
-- service still declines to re-propose something the person rejected).

CREATE TABLE IF NOT EXISTS learning_proposals (
    id          UUID         PRIMARY KEY,
    subject     VARCHAR(255) NOT NULL,
    agent_id    UUID,
    kind        VARCHAR(24)  NOT NULL,
    title       VARCHAR(200) NOT NULL,
    body        TEXT         NOT NULL,
    body_hash   VARCHAR(64)  NOT NULL,
    payload     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    evidence    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status      VARCHAR(12)  NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMPTZ  NOT NULL,
    decided_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_learning_proposals_subject
    ON learning_proposals (subject, status, created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uq_learning_proposals_pending
    ON learning_proposals (subject, kind, body_hash) WHERE status = 'PENDING';
