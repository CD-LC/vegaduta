-- Live knowledge connectors (P2 product-completeness, core/knowledge):
-- per-tenant connector configs + document source/citation columns.
--
-- knowledge_connector: one row per configured external source feeding a
-- collection - Notion workspace/database, Google Drive folder, Confluence
-- space, SharePoint drive, or a web-crawl URL list. config_jsonb carries the
-- provider-specific shape (tokens are stored AES-GCM-encrypted INSIDE the
-- JSON, not plaintext); sync_cursor lets a connector resume incrementally
-- where the provider supports it.
--
-- knowledge_document gains:
--   source_url   - the canonical link a citation should point at (Notion
--                  page URL, Drive file link, Confluence page, crawled URL).
--                  This is the "with citations" half of the feature: search
--                  results resolve document_id -> source_url.
--   external_ref - the connector's stable document id (Notion page id,
--                  Drive file id, Confluence content id); the re-sync dedup
--                  key - a re-fetched doc replaces its old chunks rather
--                  than duplicating them.
--   connector_id - which connector produced this document (nullable - null
--                  for plain uploads, and intentionally NOT a FK: deleting a
--                  connector must not cascade-delete knowledge).
--
-- TENANT-SCHEMA tables - connector configs and documents are tenant data,
-- same as knowledge_document itself.
--
-- Version 254: V250-259 claimed for this work in docs/AGENT-COORDINATION.md;
-- the shared controlplane+tenant version namespace applies.

CREATE TABLE IF NOT EXISTS knowledge_connector (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    collection_id    UUID NOT NULL REFERENCES knowledge_collection(id) ON DELETE CASCADE,
    type             VARCHAR(24) NOT NULL
                     CHECK (type IN ('NOTION','GOOGLE_DRIVE','CONFLUENCE','SHAREPOINT','WEB_CRAWL')),
    name             VARCHAR(255) NOT NULL,
    config_jsonb     JSONB NOT NULL DEFAULT '{}',
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    sync_cursor      VARCHAR(1024),
    last_synced_at   TIMESTAMPTZ,
    last_error       TEXT,
    created_by       VARCHAR(255),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_knowledge_connector_collection
    ON knowledge_connector (collection_id);

ALTER TABLE knowledge_document
    ADD COLUMN IF NOT EXISTS source_url   VARCHAR(2048),
    ADD COLUMN IF NOT EXISTS external_ref VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS connector_id UUID;

CREATE INDEX IF NOT EXISTS idx_knowledge_document_connector_ref
    ON knowledge_document (connector_id, external_ref);
