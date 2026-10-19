-- Multi-model knowledge ingestion - lets a tenant pick which local
-- embedding model a collection uses (EmbeddingModelCatalog.java) instead of
-- every collection being permanently stuck on the single MiniLM-L6 model
-- Stage 5 originally hardcoded.
--
-- Widening the vector column, not adding a second one: every model in the
-- catalog outputs at most 768 dimensions (all-mpnet-base-v2; the other three
-- are 384). The sidecar zero-pads every embedding up to 768 before it's ever
-- sent to Java, regardless of source model, so one column serves all of
-- them. Zero-padding is safe here specifically because pgvector's cosine
-- distance operator (<=>) computes strictly from each vector's own
-- direction and magnitude - appending zeros to both operands changes
-- neither (a zero coordinate contributes nothing to a dot product or to a
-- vector's L2 norm), so padding two same-length vectors from the SAME model
-- leaves their cosine similarity mathematically identical to the
-- unpadded computation. It does NOT make vectors from two DIFFERENT models
-- comparable to each other (they were never comparable regardless of
-- dimension - see EmbeddingModelCatalog's javadoc), which is exactly why
-- embedding_model is added below and KnowledgeSearchService now filters by
-- it before ever computing a distance.
--
-- The USING clause below backfills every existing row (all of which were
-- embedded by the original 384-dim model, before this migration existed)
-- by padding them out to 768 with the same zero-padding scheme the sidecar
-- now applies going forward - existing search results are unaffected.
ALTER TABLE knowledge_chunks
    ALTER COLUMN embedding TYPE vector(768)
    USING (embedding::real[] || array_fill(0::real, ARRAY[384]))::vector(768);

ALTER TABLE knowledge_chunks
    ADD COLUMN embedding_model VARCHAR(64) NOT NULL DEFAULT 'minilm-l6-v2';

ALTER TABLE knowledge_collection
    ADD COLUMN embedding_model VARCHAR(64) NOT NULL DEFAULT 'minilm-l6-v2';

-- The DEFAULT clauses above already backfill every existing row (including
-- the seeded "Default" collection from V15) to 'minilm-l6-v2', which is
-- exactly correct - that's the only model that has ever existed until now.
