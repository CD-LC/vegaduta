-- Agent Planning & Multi-Agent Collaboration Build Plan, Stage 2 -
-- Agent.collaboratorAgentIds' backing table, same opt-in shape as
-- V16__agent_allowed_knowledge_collection.sql: an agent with no rows here
-- has no collaborators (no delegation tool offered at all, today's
-- unchanged behavior), a row here grants delegation to that specific other
-- agent. No backfill needed - an empty table for every pre-existing agent
-- is exactly the "no collaborators" state.

CREATE TABLE agent_collaborator (
    agent_id               UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    collaborator_agent_id  UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    PRIMARY KEY (agent_id, collaborator_agent_id),
    CHECK (agent_id <> collaborator_agent_id)
);
