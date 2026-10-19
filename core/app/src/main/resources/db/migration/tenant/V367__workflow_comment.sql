-- Workflow canvas comments (phase 4, "Guidance and collaboration").
--
-- Threads anchored to a canvas node (node_id) or a free canvas point
-- (anchor_x/anchor_y); one level of replies via parent_id. A comment outlives
-- its node: node_id is plain text, NOT a foreign key into graph JSON, and the
-- API reports such a thread as orphaned instead of cascading it away.
-- Deleting the WORKFLOW does cascade (the comments have nothing left to
-- annotate). mentions is a JSON array of user subject ids; author_name is the
-- display name captured from the author's token at write time, because the
-- tenant schema holds no user directory.
CREATE TABLE IF NOT EXISTS workflow_comment (
    id           UUID PRIMARY KEY,
    workflow_id  UUID         NOT NULL REFERENCES workflow_definition(id) ON DELETE CASCADE,
    parent_id    UUID         REFERENCES workflow_comment(id) ON DELETE CASCADE,
    node_id      VARCHAR(128),
    anchor_x     DOUBLE PRECISION,
    anchor_y     DOUBLE PRECISION,
    body         TEXT,
    mentions     TEXT,
    author_id    VARCHAR(255) NOT NULL,
    author_name  VARCHAR(255),
    resolved     BOOLEAN      NOT NULL DEFAULT FALSE,
    resolved_by  VARCHAR(255),
    resolved_at  TIMESTAMPTZ,
    deleted_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_workflow_comment_workflow ON workflow_comment(workflow_id, created_at);
CREATE INDEX IF NOT EXISTS idx_workflow_comment_parent ON workflow_comment(parent_id);
