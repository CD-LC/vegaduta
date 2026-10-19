-- Per-collection chunking budget (Spring AI gap-fix, 2026-09-28).
--
-- Version V361: application.yml's flyway.locations merges controlplane/ and
-- tenant/ into ONE version stream, so this number is unique across BOTH
-- folders. V361-V363 are this
-- change's (see docs/AGENT-COORDINATION.md).
--
-- TENANT-schema table (no schema qualifier) - knowledge_collection has always
-- lived here (V15).
--
-- Chunk size/overlap were hardcoded at 150/30 words in KnowledgeController and
-- AddToKnowledgeBaseToolCallback. Both columns are NULLABLE with no default and
-- no backfill on purpose: null means "platform default" (Chunker's 150/30), so
-- every existing collection keeps chunking with exactly the budget it had.
-- Only content ingested after a value is set is affected.
--
-- Bounds mirror the sidecar's /embed request model (chunk_size ge=10, le=200)
-- and Chunker.Settings. overlap < size can't be a column CHECK when either side
-- may be null (defaulted in Java), so that pair rule is enforced in
-- Chunker.resolve at create/update time.
ALTER TABLE knowledge_collection ADD COLUMN chunk_size INT;
ALTER TABLE knowledge_collection ADD COLUMN chunk_overlap INT;

ALTER TABLE knowledge_collection
    ADD CONSTRAINT chk_knowledge_collection_chunk_size
    CHECK (chunk_size IS NULL OR chunk_size BETWEEN 10 AND 200);

ALTER TABLE knowledge_collection
    ADD CONSTRAINT chk_knowledge_collection_chunk_overlap
    CHECK (chunk_overlap IS NULL OR chunk_overlap >= 0);
