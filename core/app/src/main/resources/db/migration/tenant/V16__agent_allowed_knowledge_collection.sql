-- Knowledge Stage F1 - Agent.allowedKnowledgeCollectionIds' backing table,
-- same opt-in shape as V12__agent_allowed_tool.sql: an agent with no rows
-- here has no restriction (every collection its tenant has is searchable,
-- today's unchanged behavior), a row here scopes it down. No backfill
-- needed - an empty table for every pre-existing agent is exactly the
-- "no restriction" state.

CREATE TABLE agent_allowed_knowledge_collection (
    agent_id      UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    collection_id UUID NOT NULL REFERENCES knowledge_collection(id) ON DELETE CASCADE,
    PRIMARY KEY (agent_id, collection_id)
);
