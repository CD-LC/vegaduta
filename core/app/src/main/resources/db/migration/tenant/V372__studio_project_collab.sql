-- Team collaboration on Phone-App-Studio projects: members, comments, and
-- who authored each version.
--
-- Until now a studio project was single-owner (studio_project.owner_subject).
-- The owner can now share a project with other members of the SAME tenant as
-- VIEWER (read + comment) or EDITOR (also add versions). The owner stays in
-- studio_project.owner_subject; only additional members live here. Subjects
-- are Keycloak user ids (the JWT sub), the same value as owner_subject.

CREATE TABLE studio_project_member (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id     VARCHAR(64) NOT NULL REFERENCES studio_project(id) ON DELETE CASCADE,
    member_subject VARCHAR(128) NOT NULL,
    role           VARCHAR(16) NOT NULL,
    invited_by     VARCHAR(128) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_studio_project_member UNIQUE (project_id, member_subject),
    CONSTRAINT ck_studio_project_member_role CHECK (role IN ('VIEWER', 'EDITOR'))
);

-- "Shared with me" lookup.
CREATE INDEX ix_studio_project_member_subject ON studio_project_member (member_subject);

CREATE TABLE studio_project_comment (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id     VARCHAR(64) NOT NULL REFERENCES studio_project(id) ON DELETE CASCADE,
    -- Optional anchor: the version (studio_project_version.at_ms) the comment is about.
    version_at_ms  BIGINT,
    author_subject VARCHAR(128) NOT NULL,
    author_name    VARCHAR(160) NOT NULL,
    body           VARCHAR(2000) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved       BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX ix_studio_project_comment_project ON studio_project_comment (project_id, created_at);

-- Who saved a version (NULL for versions written before collaboration).
ALTER TABLE studio_project_version ADD COLUMN author_subject VARCHAR(128);
