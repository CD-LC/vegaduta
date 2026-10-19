-- Stage 5 (KB multi-modal storage) - direct follow-on to Stage 3's storage
-- migration (V65, sarvam_generated_audio.object_key) and Stage 4's chat
-- attachments (ChatAttachmentService, which never persisted its key
-- anywhere). Neither of those stages touched knowledge ingestion:
-- KnowledgeController.upload()/uploadAudio() read the uploaded bytes into
-- memory, hand them to the sidecar for extraction, and discard them - only
-- the extracted TEXT survives, as chunks in the per-tenant knowledge_chunks
-- table. That means today there is no way to show a user the original PDF
-- page, replay the original audio clip, or re-render the original image
-- next to a RAG answer - the platform has "read" every file once and thrown
-- the file itself away.
--
-- This migration adds the columns needed to keep the original artifact:
-- object_key/content_type on knowledge_document (nullable - existing rows
-- predate object storage and simply have no original file to offer,
-- exactly like sarvam_generated_audio.object_key's precedent), and
-- document_id on knowledge_chunks so a search hit can be resolved back to
-- the KnowledgeDocument that owns it (today chunks only carry the
-- free-text `source` filename column, no FK - added in V10 before
-- knowledge_document even existed as of V45).
--
-- Renumbered from V66 to V69 (originally created right after V65, but
-- collided with controlplane/V66__provider_model_catalog.sql once both
-- folders were checked together - see application.yml's flyway.locations
-- comment: version numbers must be unique across controlplane/ AND
-- tenant/ COMBINED. V69 is the first free slot past controlplane's then-
-- highest (V67) and tenant's own V68__user_tool_script.sql).
ALTER TABLE knowledge_document
    ADD COLUMN object_key   VARCHAR(512),
    ADD COLUMN content_type VARCHAR(128);

ALTER TABLE knowledge_chunks
    ADD COLUMN document_id UUID REFERENCES knowledge_document(id);

CREATE INDEX idx_knowledge_chunks_document ON knowledge_chunks (document_id);
