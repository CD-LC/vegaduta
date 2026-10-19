-- Stage F1 (Hierarchical Knowledge Base Build Plan) - introduces named,
-- described, optionally-nested collections that knowledge_chunks belong to.
-- The description column is not cosmetic - Stage F2's list_knowledge_collections
-- tool returns it verbatim so a model can decide which collection is
-- relevant to a scenario before searching.
--
-- Every existing tenant's chunks (uploaded before this migration existed)
-- are backfilled into an auto-created "Default" collection, then
-- collection_id is tightened to NOT NULL - same backfill-then-constrain
-- convention used for every prior migration in this series (see
-- V12/V13/V14's header comments for the same reasoning applied to their own
-- new columns).
--
-- ON DELETE CASCADE on parent_collection_id: deleting a parent collection
-- deletes its children too, deliberately - a collection with no parent
-- left behind (orphaned) would be unreachable via the hierarchy anyway.
-- Deleting a collection that still has knowledge_chunks in it is NOT
-- cascaded (see the FK below) - that must go through the application layer
-- so KnowledgeSearchService/ingestion can decide what "delete a
-- non-empty collection" should actually do (reject it, or move its chunks
-- to Default) rather than Postgres silently deleting embedded content.

CREATE TABLE knowledge_collection (
    id                    UUID PRIMARY KEY,
    name                  VARCHAR(255) NOT NULL,
    description           TEXT,
    parent_collection_id  UUID REFERENCES knowledge_collection(id) ON DELETE CASCADE,
    -- Exactly one row per tenant has this TRUE (the seeded row below) -
    -- KnowledgeCollectionService uses it to find "where uploads land when
    -- no collection is specified" without relying on the fragile "name
    -- happens to be Default" (a tenant could otherwise rename or duplicate
    -- that name), and to refuse deleting it.
    is_default            BOOLEAN NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Every tenant needs a Default collection to exist, whether or not they
-- have any knowledge_chunks yet - both for the backfill immediately below
-- and so future uploads via KnowledgeController always have somewhere to
-- land when no collection is specified.
INSERT INTO knowledge_collection (id, name, description, is_default)
VALUES (
    gen_random_uuid(),
    'Default',
    'Uncategorized documents - either uploaded before collections existed, or uploaded without an explicit collection.',
    TRUE
);

ALTER TABLE knowledge_chunks
    ADD COLUMN collection_id UUID REFERENCES knowledge_collection(id);

UPDATE knowledge_chunks
SET collection_id = (SELECT id FROM knowledge_collection WHERE is_default = TRUE LIMIT 1)
WHERE collection_id IS NULL;

ALTER TABLE knowledge_chunks ALTER COLUMN collection_id SET NOT NULL;
