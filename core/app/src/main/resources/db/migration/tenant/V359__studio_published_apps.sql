-- Phone App Studio "published apps": the server-side anchor that gives a
-- generated single-file app a stable public slug, its own end-user accounts
-- (the builder's customers - NOT console/Keycloak users), configurable
-- agent/workflow connections, and an optional hosted-AI block.
--
-- Tenant schema, not control_plane: the app and its end users are tenant
-- data. Cross-tenant slug -> schema resolution rides the existing
-- control_plane.channel_account_link registry (ExternalAccountProvider
-- STUDIO_APP), exactly like AGENT_SHARE / WORKFLOW_SHARE already do.

CREATE TABLE studio_app (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            VARCHAR(24) NOT NULL,
    name            VARCHAR(120) NOT NULL,
    html            TEXT NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'LIVE',   -- LIVE | DISABLED
    auth_mode       VARCHAR(16) NOT NULL DEFAULT 'NONE',   -- NONE | OPTIONAL | REQUIRED
    ai_enabled      BOOLEAN NOT NULL DEFAULT FALSE,
    ai_system_prompt TEXT,
    ai_max_tokens   INTEGER NOT NULL DEFAULT 700,
    -- HS256 secret for end-user session tokens, generated at publish time.
    -- Per-app (not the global encryption key) so "unpublish + republish" can
    -- rotate it and kill every issued token at once.
    token_secret    VARCHAR(64) NOT NULL,
    created_by      VARCHAR(128),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_version INTEGER NOT NULL DEFAULT 1,
    CONSTRAINT uq_studio_app_slug UNIQUE (slug)
);

-- A wired capability inside the app: "when the app calls alias X, run THIS
-- agent/workflow with THIS payload template and show THIS part of the
-- response". share_id is kept so the call dies when the owner revokes the
-- underlying public share - same revocation story as the share pages.
CREATE TABLE studio_app_connection (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id           UUID NOT NULL REFERENCES studio_app(id) ON DELETE CASCADE,
    alias            VARCHAR(40) NOT NULL,
    kind             VARCHAR(16) NOT NULL,                  -- AGENT | WORKFLOW
    target_id        UUID NOT NULL,
    share_id         VARCHAR(64),
    display_name     VARCHAR(120),
    request_template TEXT,           -- {{input}} / {{user.name}} placeholders; null = input verbatim
    response_path    VARCHAR(160),   -- e.g. "reply", "resultText"; null = auto
    requires_auth    BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order       INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_studio_app_connection_alias UNIQUE (app_id, alias)
);

-- End users of ONE published app - the builder's customers. Platform-native
-- (Build-Spec D1 visitor model): deliberately NOT Keycloak users, so they
-- cannot reach the console, consume no seat licence, and are scoped to this
-- single app by construction.
CREATE TABLE studio_app_user (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id        UUID NOT NULL REFERENCES studio_app(id) ON DELETE CASCADE,
    email         VARCHAR(254) NOT NULL,
    display_name  VARCHAR(120),
    password_hash VARCHAR(128) NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',    -- ACTIVE | DISABLED
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ,
    CONSTRAINT uq_studio_app_user_email UNIQUE (app_id, email)
);

CREATE INDEX ix_studio_app_user_app ON studio_app_user(app_id);
CREATE INDEX ix_studio_app_connection_app ON studio_app_connection(app_id);
