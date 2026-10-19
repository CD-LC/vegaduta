-- Master-Roadmap-2026.md item #2 (hybrid search + reranking for RAG),
-- Platform-Tech-Stack-Audit-2026.md P3.2: knowledge_chunks has only ever
-- supported pure vector-similarity search (see KnowledgeSearchService's
-- <=> cosine-distance query) - a query whose useful match hinges on an
-- exact term (a product SKU, an error code, a proper noun the embedding
-- model wasn't trained to weight highly) can rank poorly on pure semantic
-- similarity even when the right chunk contains that exact term verbatim.
-- Hybrid search (this column + KnowledgeSearchService's new keyword-search
-- query, fused via Reciprocal Rank Fusion) closes that gap without
-- replacing vector search - both run and get merged.
--
-- GENERATED ALWAYS AS ... STORED (not a trigger, not computed at query
-- time): Postgres keeps this column in sync automatically on every
-- INSERT/UPDATE of `content`, and STORED means it's materialized on disk
-- and indexable, unlike a VIRTUAL generated column. 'english' is a fixed
-- text-search configuration, not per-tenant-configurable - acceptable for
-- a v1 (matches this platform's English-only UI copy today; a future
-- per-tenant/per-document language config would need a second config
-- column and a rebuild of this generated column, not a small change).
--
-- No new extension required - tsvector/to_tsvector/ts_rank_cd/
-- plainto_tsquery are all built into core Postgres, unlike pgvector which
-- needed CREATE EXTENSION (see V10). A GIN index is the standard choice for
-- tsvector columns (unlike a GiST index, which trades index size/build
-- speed for query speed the other way) - this table is read-heavy
-- (searched far more often than chunks are inserted/updated), which is
-- exactly GIN's strength.
ALTER TABLE knowledge_chunks
    ADD COLUMN content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED;

CREATE INDEX idx_knowledge_chunks_content_tsv ON knowledge_chunks USING GIN (content_tsv);
