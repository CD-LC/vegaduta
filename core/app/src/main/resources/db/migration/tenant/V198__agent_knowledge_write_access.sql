-- New feature: lets an agent INSERT content into a knowledge collection
-- (AddToKnowledgeBaseToolCallback, core/knowledge), not just search it
-- (KnowledgeSearchToolCallback, unaffected). Same "off by default, explicit
-- per-agent opt-in" shape as code_workspace_enabled (V103), and deliberately
-- reuses that migration's deny-by-default allow-list convention rather than
-- allowedKnowledgeCollectionIds' (V16) "empty = unrestricted" one:
--
--   - allowedKnowledgeCollectionIds (read/search, V16): empty means "every
--     collection this tenant has is searchable" - safe, because search is
--     read-only and can only ever surface what's already there.
--   - allowed_knowledge_write_collection (this migration): empty means
--     "no collection granted" (deny-all) - because a write tool lets an
--     agent inject content that every future search turn (from potentially
--     every user talking to this agent, or another agent sharing the same
--     collection) will retrieve as if it were trustworthy uploaded
--     material. A tenant-admin flipping knowledge_write_enabled on but
--     forgetting to grant a collection should get "this agent can write
--     but nothing resolves to a permitted target," never silently-
--     unrestricted write access to every collection the tenant has -
--     exact same reasoning V103's header comment gives for
--     agent_allowed_codeintel_repo.
--
-- Both columns default/start empty so no pre-existing agent's behavior
-- changes on deploy: knowledge_write_enabled false means
-- AgentToolProvider#resolveKnowledgeTools continues to offer only the
-- existing list/search tools, exactly today's (pre-migration) behavior.
ALTER TABLE agent ADD COLUMN knowledge_write_enabled BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE agent_allowed_knowledge_write_collection (
    agent_id      UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    collection_id UUID NOT NULL,
    PRIMARY KEY (agent_id, collection_id)
);
