-- Chat branching (edit / regenerate / switch between alternative replies).
--
-- Session history lives in Redis (SessionMemoryService, a plain list with no
-- message ids and a 24h TTL), so there is no messages table to add a parent
-- column to. These two tables hold the conversation TREE beside it, and only
-- exist to be written when app.chat.branching.enabled is true (default false):
-- with the flag off nothing reads or writes them and the linear chat path is
-- untouched. Redis always holds exactly the ACTIVE path of the tree, so every
-- consumer of history (prompt assembly, rolling summary, semantic cache keys)
-- keeps reading one linear list and never has to know branches exist.
--
-- chat_branch_node: one row per user or assistant message.
--   parent_message_id  NULL = a root message (the first turn, or a sibling of
--                      it created by editing the first turn). Not a foreign key
--                      on purpose: it is a self-reference inside one session
--                      and a node must never fail to insert on a race.
--   sibling_index      0-based creation order among the children of one parent
--                      (children of one parent share a role). The unique index
--                      below is the concurrency guard: two requests computing the
--                      same next index cannot both win, the loser re-reads.
--
-- chat_branch_session: one row per (agent, session). active_leaf_message_id is
-- the tip of the path currently shown and replayed (NULL = empty path);
-- reuse_user_message_id is a one-shot hint set by "regenerate" so the user
-- message the chat call re-appends is mapped onto the existing node instead of
-- becoming a duplicate sibling. owner_subject binds the tree to the first
-- subject that used it so another user with RUNNER access cannot read it by
-- guessing a session id.
--
-- Existing sessions have no rows: they stay linear. The tree is seeded lazily,
-- from Redis, the first time a branching endpoint touches a session.
CREATE TABLE IF NOT EXISTS chat_branch_node (
    id                 UUID PRIMARY KEY,
    agent_id           UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    session_id         VARCHAR(128) NOT NULL,
    parent_message_id  UUID,
    role               VARCHAR(16) NOT NULL,
    content            TEXT NOT NULL,
    sibling_index      INT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chat_branch_node_session
    ON chat_branch_node (agent_id, session_id, created_at);

CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_branch_node_sibling
    ON chat_branch_node (agent_id, session_id,
                         COALESCE(parent_message_id, '00000000-0000-0000-0000-000000000000'::uuid),
                         sibling_index);

CREATE TABLE IF NOT EXISTS chat_branch_session (
    id                      UUID PRIMARY KEY,
    agent_id                UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    session_id              VARCHAR(128) NOT NULL,
    owner_subject           VARCHAR(255),
    active_leaf_message_id  UUID,
    reuse_user_message_id   UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_branch_session
    ON chat_branch_session (agent_id, session_id);
