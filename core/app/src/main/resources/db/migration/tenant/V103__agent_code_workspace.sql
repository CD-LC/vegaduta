-- Wires the code-intel write/execute tool surface (create/apply/run/diff/
-- delete_code_workspace - see CreateCodeWorkspaceToolCallback and friends
-- in core/codeintel) into AgentToolProvider#resolveCodeWorkspaceTools,
-- which was built earlier but deliberately left unwired (see that method's
-- own javadoc for the "NOT YET WIRED" list this migration + its
-- accompanying code changes complete): an explicit per-agent opt-in flag,
-- same "off by default" shape as knowledge_base_enabled/sarvam_enabled/
-- e2b_enabled, plus a per-agent repo_id allow-list, same Set-backed shape
-- as agent_allowed_knowledge_collection (V16) but String-keyed (repo ids
-- in code-intel are not UUIDs - see CodeIntelClient).
--
-- Deliberate semantic difference from allowedKnowledgeCollectionIds: there,
-- empty means "no restriction, every collection searchable" (safe, because
-- search is read-only). Here, empty means "no repo granted" (deny-all) -
-- because these tools can write files and run arbitrary shell commands
-- once approved, so a tenant-admin who flips code_workspace_enabled on but
-- forgets to also grant at least one repo should get "this agent can
-- create a workspace but nothing resolves to a permitted target," not
-- silently-unrestricted access to every repo the tenant has registered
-- with code-intel. AgentToolProvider/the tool callbacks enforce this
-- deny-by-default reading explicitly - they do not reuse
-- allowedKnowledgeCollectionIds' "empty = unrestricted" convention.
--
-- Both columns default/start empty so no pre-existing agent's behavior
-- changes on deploy: code_workspace_enabled false means
-- resolveCodeWorkspaceTools continues to contribute zero tools, exactly
-- today's (pre-migration) behavior.
ALTER TABLE agent ADD COLUMN code_workspace_enabled BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE agent_allowed_codeintel_repo (
    agent_id UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    repo_id  VARCHAR(255) NOT NULL,
    PRIMARY KEY (agent_id, repo_id)
);
