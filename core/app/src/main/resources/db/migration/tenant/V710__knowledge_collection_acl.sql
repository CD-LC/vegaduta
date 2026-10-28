-- Per-collection access control for the knowledge base.
--
-- Until now any tenant member could search and download every knowledge
-- collection. This adds the two columns the sharing model needs
-- (core/sharing's ResourceAccessService, as agents and workflows already use):
--
--   created_by  the owner's subject. Nullable and NOT backfilled on purpose,
--               exactly like V151 (agent/workflow): a NULL owner is a "legacy"
--               collection that predates the feature.
--   visibility  PRIVATE  - owner (and tenant/platform admins) only; grants are
--                          kept but dormant.
--               SHARED   - owner, explicit grantees (VIEWER: search/download,
--                          EDITOR: ingest/delete documents) and admins.
--               TENANT   - every tenant member can read (the behaviour of every
--                          collection before this migration).
--
-- BACKWARD COMPATIBLE: the column default is 'TENANT', so every existing
-- collection - and any row inserted by an older core during a rolling deploy -
-- stays visible to the whole tenant. No behaviour changes on upgrade; a
-- collection becomes restricted only when its owner or an admin changes its
-- visibility.
--
-- resource_grant (V147) pins resource_type to AGENT / WORKFLOW with a CHECK
-- constraint; widen it so a grant can name a KNOWLEDGE_COLLECTION. The
-- constraint is dropped and re-added (Postgres has no in-place ALTER for a
-- CHECK); IF EXISTS keeps a re-run safe.

ALTER TABLE knowledge_collection
    ADD COLUMN created_by VARCHAR(255),
    ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'TENANT';

ALTER TABLE knowledge_collection
    ADD CONSTRAINT chk_knowledge_collection_visibility
        CHECK (visibility IN ('PRIVATE', 'TENANT', 'SHARED'));

ALTER TABLE resource_grant DROP CONSTRAINT IF EXISTS chk_resource_grant_type;
ALTER TABLE resource_grant
    ADD CONSTRAINT chk_resource_grant_type
        CHECK (resource_type IN ('AGENT', 'WORKFLOW', 'KNOWLEDGE_COLLECTION'));
