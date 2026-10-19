-- Event-driven document generation module (core/documentgen) - backs
-- DocumentGenerationJob. TENANT-schema table (no schema qualifier, same
-- convention as guardrail_rule/moderation_review_item): lands in whatever
-- schema is on the search_path, so this migration runs once per tenant
-- schema (new tenant provisioning + TenantSchemaMigrator backfill for
-- existing tenants).
--
-- Version V177: application.yml's flyway.locations merges controlplane/ and
-- tenant/ into ONE version stream, so a version number must be unique across
-- BOTH folders combined (see V121__content_moderation_review_queue.sql's
-- note for the same rule). V176 (tenant/V176__notification.sql) was already
-- taken by another in-flight change by the time this migration was written
-- (the scaffold's original instructions said V176 was next-free; re-checked
-- directly against the filesystem before writing this file) - V177 is the
-- next actually-free slot as of this writing. FLAGGED FOR MERGE: if V177
-- collides with another branch's migration at merge time, rename this file
-- to the next actually-free number (Flyway tolerates version gaps,
-- out-of-order:true is already set) rather than reusing a taken one.
--
-- tenant_id is kept as an explicit column even though the tenant schema
-- itself already isolates these rows - see DocumentGenerationJob's own
-- javadoc ("Tenant-schema entity with an explicit tenant_id column") for why,
-- same rationale guardrail_rule already documents.
--
-- content_json is NOT part of the module's originally scaffolded entity
-- field list - see DocumentGenerationJob's javadoc for why it was added:
-- the async @TransactionalEventListener that actually renders the file has
-- no other durable way to recover what to render once the enqueueing
-- transaction has committed.

CREATE TABLE IF NOT EXISTS document_generation_job (
    id             UUID PRIMARY KEY,
    tenant_id      VARCHAR(255),
    type           VARCHAR(16)  NOT NULL,
    filename       VARCHAR(255) NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    storage_key    VARCHAR(512),
    content_json   TEXT         NOT NULL,
    error_message  TEXT,
    requested_by   VARCHAR(255),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ
);

-- Backs DocumentGenerationJobRepository#findByTenantIdOrderByCreatedAtDesc
-- and the daily/monthly quota-count queries in DocumentGenerationQuotaService.
CREATE INDEX IF NOT EXISTS idx_document_generation_job_tenant_created
    ON document_generation_job (tenant_id, created_at DESC);

-- Backs DocumentGenerationJobRepository#findByTenantIdAndFilenameContainingIgnoreCaseOrderByCreatedAtDesc
-- (the Files page's search box) - a plain btree on filename still helps a
-- case-insensitive LIKE '%...%' avoid a full sequential re-read of every
-- column, even though it can't service the leading-wildcard match itself.
CREATE INDEX IF NOT EXISTS idx_document_generation_job_filename
    ON document_generation_job (filename);
