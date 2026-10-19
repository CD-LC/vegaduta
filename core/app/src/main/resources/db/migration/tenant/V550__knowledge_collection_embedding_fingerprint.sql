-- Which embedding source produced a knowledge collection's vectors (embeddings
-- tranche, 2026-10-17). Version V550 - unique across the merged controlplane/ +
-- tenant/ Flyway stream.
--
-- TENANT-schema column (no schema qualifier) on knowledge_collection (V15).
--
-- embedding_model (V26) is a catalog key such as 'minilm-l6-v2'. With the opt-in
-- external embedders (app.embeddings.provider=ollama | openai-compatible) that key
-- is only echoed, so it cannot tell nomic-embed-text vectors from MiniLM vectors
-- once an operator changes provider: the new vectors were silently padded into
-- the same vector(768) column and compared against the old ones. This column
-- records the real source - 'sidecar', or 'provider:model:nativeWidth' - and
-- ingestion/search/re-embed compare it with the live embedder instead.
--
-- NULL = unbound (every existing collection, and a new one until its first
-- ingest): the first ingest or re-embed binds it to the live source, so nothing
-- changes for existing installs. No backfill UPDATE: the migration cannot know
-- which provider an existing install was running.
ALTER TABLE knowledge_collection
    ADD COLUMN embedding_fingerprint VARCHAR(190);
