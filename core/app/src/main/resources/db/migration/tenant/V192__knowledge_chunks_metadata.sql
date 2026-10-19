-- Grounding-fidelity fix (2026-08-07), found live against tenant ruf_duta,
-- collection fcbb435e-e06f-40ad-a7f2-89a1f6dff382: a knowledge_chunks row has
-- no way to say what kind of source it came from, so a search_knowledge_base
-- query has no way to exclude noisy chunks even when it knows it should -
-- e.g. Tesseract-OCR'd product images ranking above clean text catalog docs
-- for the same product, garbled SKUs/prices feeding the agent context it
-- then extrapolated fictitious products from. See KnowledgeSearchService's
-- javadoc on the new excludeImageSourced parameter for how this is consumed.
--
-- metadata is a general-purpose JSONB bag (not a single source_type column)
-- so future structured-filter needs (price, category, etc, once an ingestion
-- path populates them) don't require another migration - KnowledgeIngestionService
-- currently only ever writes {"source_type": "image"|"text"} into it.
--
-- Version V190: highest version present in either folder as of this writing
-- is V189 (controlplane/V189__platform_quota_reset_log.sql; tenant's highest
-- is V187) - both folders share one Flyway version namespace (see V154's
-- header for the same rule), so V190 is the next free slot in the combined
-- stream, verified absent from BOTH folders before creation.
--
-- NOT NULL DEFAULT '{}'::jsonb, no backfill beyond the default: existing
-- chunks predate any source-type tracking and have no reliable way to
-- classify retroactively, so they default to an empty object - metadata->>
-- 'source_type' reads NULL for them, which excludeImageSourced treats as
-- "not image-sourced" (include), preserving their current search behavior.
ALTER TABLE knowledge_chunks
    ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'::jsonb;

-- jsonb_path_ops rather than the default jsonb_ops - every current and
-- anticipated lookup is an equality/containment check on a top-level key
-- (metadata->>'source_type' = 'image', eventually metadata @> '{"category":...}'),
-- which jsonb_path_ops supports at roughly a third the index size.
CREATE INDEX idx_knowledge_chunks_metadata ON knowledge_chunks USING GIN (metadata jsonb_path_ops);
