-- Cross-user resource sharing foundation (core/sharing) - lets a user who
-- created an Agent or Workflow grant specific teammates (by Keycloak user
-- id) RUNNER/VIEWER/EDITOR access to it, layered on top of today's
-- tenant-only isolation. Neither `agent` nor `workflow_definition` gains a
-- `created_by` column here - that's a separate, next-workstream migration
-- (also needs AgentService/WorkflowService changes to populate it on
-- create, out of scope for this one) - this migration only adds the grant
-- table itself.
--
-- Version V147: application.yml's flyway.locations merges controlplane/ and
-- tenant/ into ONE version stream, so a version number must be unique across
-- BOTH folders combined (see V121__content_moderation_review_queue.sql's
-- note for the same rule). Highest version present in either folder as of
-- this writing is V146 (controlplane/V146__demo_chat_message.sql) - V147 is
-- the next free slot. FLAGGED FOR MERGE: this module was worked in an
-- isolated worktree alongside other parallel core-3 work that may ALSO be
-- claiming a migration number right now - if V147 collides with another
-- branch's migration at merge time, rename this file to the next
-- actually-free number (Flyway tolerates version gaps, out-of-order:true is
-- already set) rather than reusing a taken one.
--
-- TENANT-schema table (no tenant_id column, same convention as agent_share,
-- V144): lands in whatever schema is on the search_path, so this migration
-- runs once per tenant schema (new tenant provisioning + TenantSchemaMigrator
-- backfill for existing tenants) - a grant only ever governs access within
-- the ONE tenant schema it lives in, which is also why grantee_user_id is
-- validated same-tenant at write time (see ResourceAccessService#grant)
-- rather than relying on this table's own structure to enforce it.
CREATE TABLE resource_grant (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resource_type      VARCHAR(32)  NOT NULL,
    resource_id        UUID         NOT NULL,
    grantee_user_id    VARCHAR(255) NOT NULL,
    permission_level   VARCHAR(16)  NOT NULL,
    granted_by         VARCHAR(255) NOT NULL,
    granted_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at         TIMESTAMPTZ,
    revoked_by         VARCHAR(255),
    CONSTRAINT chk_resource_grant_type CHECK (resource_type IN ('AGENT', 'WORKFLOW')),
    CONSTRAINT chk_resource_grant_level CHECK (permission_level IN ('RUNNER', 'VIEWER', 'EDITOR'))
);

-- Partial unique index (revoked_at IS NULL only) - re-granting the same
-- (resource, grantee) pair while an active grant already exists must be an
-- upsert (ResourceAccessService#grant updates the existing row's level), not
-- a constraint violation; a revoked grant's row is left in place as history,
-- so the same pair can legitimately have many revoked rows plus at most one
-- active one.
CREATE UNIQUE INDEX uq_resource_grant_active
    ON resource_grant (resource_type, resource_id, grantee_user_id)
    WHERE revoked_at IS NULL;

-- Backs ResourceGrantRepository#findActiveGrantsForGrantee ("shared with me").
CREATE INDEX idx_resource_grant_grantee
    ON resource_grant (grantee_user_id)
    WHERE revoked_at IS NULL;

-- Backs ResourceGrantRepository#findActiveGrantsForResource (owner's "who has access" panel)
-- and the requireAccess() lookup for one specific grantee on one resource.
CREATE INDEX idx_resource_grant_resource
    ON resource_grant (resource_type, resource_id)
    WHERE revoked_at IS NULL;
