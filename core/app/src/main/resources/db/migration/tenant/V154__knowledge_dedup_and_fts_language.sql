-- RAG audit fixes (2026-07-29), two related knowledge-module gaps in one
-- migration (deliberately one file, not two: controlplane/ and tenant/ share
-- ONE Flyway version namespace - see V151's header for the same rule, which
-- has broken the build four times when forgotten - so burning a single
-- version for this batch minimizes collision risk with concurrent work).
--
-- Version V154: highest version present in either folder as of this writing
-- is V153 (controlplane/V153__smart_ai_budget_settings.sql; tenant's highest
-- is V151). V154 is the next free slot in the combined stream, verified
-- absent from BOTH folders before creation.
--
-- ============================================================================
-- Part 1: content-hash dedup for knowledge_document.
--
-- Re-uploading the exact same file used to create a brand-new
-- knowledge_document row and append a full duplicate set of chunks, every
-- time, forever - no dedup, and (until this same audit pass) no per-document
-- delete to clean it up either. content_hash stores the SHA-256 (hex, 64
-- chars) of the uploaded file's raw bytes; KnowledgeController's upload paths
-- now reject a same-collection re-upload of identical content with a clear
-- error instead of silently duplicating (see KnowledgeDocumentService
-- #requireNoDuplicate for the chosen reject-not-replace behavior and why).
--
-- Nullable, no backfill, by design: rows created before this migration have
-- no stored original bytes guaranteed available to hash (object_key is
-- nullable per V69), and a NULL hash simply never matches any dedup lookup -
-- pre-existing rows keep behaving exactly as today. Same "nullable, no
-- backfill, no breaking change" shape as V151's created_by.
ALTER TABLE knowledge_document
    ADD COLUMN content_hash VARCHAR(64);

-- KnowledgeDocumentRepository.findByCollectionIdAndContentHash - the dedup
-- lookup runs once per upload against (collection, hash).
CREATE INDEX idx_knowledge_document_content_hash
    ON knowledge_document (collection_id, content_hash);

-- ============================================================================
-- Part 2: per-collection full-text-search language configuration.
--
-- V87 hardcoded 'english' into a GENERATED tsvector column, which its own
-- header flagged as a v1 shortcut. 'english' is not merely suboptimal for
-- non-English content - its stemmer and stopword list actively mangle
-- Hindi/Tamil/other Indic text, driving keyword recall to effectively zero
-- for exactly the languages this platform positions itself around.
-- 'simple' (lowercase + split, no language-specific stemming or stopwords)
-- is the correct language-agnostic default for mixed/Indic content.
--
-- The config becomes a per-collection choice (knowledge_collection
-- .text_search_config, validated against TextSearchConfigCatalog on the Java
-- side), chosen at creation and immutable afterward for the same reason
-- embedding_model is: every chunk's stored tsvector was built with it, and
-- changing it later would make new chunks' vectors inconsistent with old ones.
--
-- Why the column default here is 'simple' but existing non-empty collections
-- are backfilled to 'english': a collection that already holds chunks had
-- every one of those tsvectors built by V87's 'english' generated column -
-- keeping it on 'english' preserves its current search behavior EXACTLY
-- (stemming included). Empty collections (including V15's seeded default in
-- brand-new tenant schemas, where this migration runs before any upload)
-- have nothing built yet and get the better language-agnostic default.
ALTER TABLE knowledge_collection
    ADD COLUMN text_search_config VARCHAR(64) NOT NULL DEFAULT 'simple';

UPDATE knowledge_collection kc
   SET text_search_config = 'english'
 WHERE EXISTS (SELECT 1 FROM knowledge_chunks c WHERE c.collection_id = kc.id);

-- content_tsv changes from GENERATED ALWAYS ('english', fixed) to a plain
-- tsvector column the ingestion code populates itself using the owning
-- collection's config. A generated column cannot be per-collection: its
-- expression must be immutable, and a per-row config lookup (column value
-- cast to regconfig) is not - so the population moves to
-- KnowledgeIngestionService's insert path (chunks are insert-only; content
-- is never updated after insert, so losing the auto-sync property of
-- GENERATED costs nothing in practice). Dropping the column also drops
-- V87's GIN index; it is recreated under the same name below.
ALTER TABLE knowledge_chunks DROP COLUMN content_tsv;
ALTER TABLE knowledge_chunks ADD COLUMN content_tsv tsvector;

-- Rebuild every existing chunk's tsvector with its own collection's config -
-- for pre-existing (backfilled-to-'english') collections this reproduces
-- V87's values bit-for-bit, so existing tenants see zero behavior change.
UPDATE knowledge_chunks c
   SET content_tsv = to_tsvector(kc.text_search_config::regconfig, c.content)
  FROM knowledge_collection kc
 WHERE kc.id = c.collection_id;

-- Defensive: any chunk row without a resolvable collection (collection_id
-- predates V15's NOT NULL enforcement in some historical schemas) still gets
-- a searchable, language-agnostic tsvector rather than staying NULL.
UPDATE knowledge_chunks
   SET content_tsv = to_tsvector('simple'::regconfig, content)
 WHERE content_tsv IS NULL;

CREATE INDEX idx_knowledge_chunks_content_tsv ON knowledge_chunks USING GIN (content_tsv);
