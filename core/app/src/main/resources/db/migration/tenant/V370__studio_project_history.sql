-- Server-side Phone-App-Studio projects + version history.
--
-- Until now a studio project (and every undo point it had) lived only in the
-- browser's localStorage: clearing site data, switching device or hitting the
-- ~5 MB quota silently lost the app. These two tables let the studio mirror
-- each project and its versions to the tenant so history follows the owner
-- across devices. The single-file HTML is stored verbatim per version.
--
-- Tenant schema: this is tenant data. Rows are scoped to the member that owns
-- them (owner_subject); tenant members share the schema but not each other's
-- drafts. The client-generated project id ("sa-...") is the primary key, and
-- the service refuses to touch a row owned by someone else.

CREATE TABLE studio_project (
    id            VARCHAR(64) PRIMARY KEY,
    owner_subject VARCHAR(128) NOT NULL,
    name          VARCHAR(120) NOT NULL,
    emoji         VARCHAR(16),
    -- Opaque JSON the studio owns: connections, auth mode, hosted-AI block,
    -- publish info. The server never interprets it.
    settings      TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_studio_project_owner ON studio_project (owner_subject, updated_at DESC);

CREATE TABLE studio_project_version (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id VARCHAR(64) NOT NULL REFERENCES studio_project(id) ON DELETE CASCADE,
    -- Client timestamp (epoch ms). With project_id it is the natural key, so
    -- re-syncing the same version from two devices is idempotent.
    at_ms      BIGINT NOT NULL,
    note       VARCHAR(400),
    source     VARCHAR(16) NOT NULL,
    html       TEXT NOT NULL,
    CONSTRAINT uq_studio_project_version UNIQUE (project_id, at_ms)
);
