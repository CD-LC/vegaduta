-- Local single-user auth (app.auth.mode=local; see core/admin .../localauth). Core is its own
-- identity provider in that mode, so the user directory Keycloak would hold lives here. The tables
-- are created in every install and stay EMPTY unless the mode is switched on, so keycloak-mode
-- deployments are unaffected.
--
-- local_account       one row per user. password_hash is a Spring DelegatingPasswordEncoder value
--                     ({bcrypt}... today; the id prefix lets a stronger encoder be introduced without
--                     a migration). tenant_id is the tenant SCHEMA name, the same value Keycloak's
--                     tenant_id attribute carries. roles is a comma-separated list of realm-role
--                     names. attributes holds the remaining identity attributes as JSON.
-- local_refresh_token rotating refresh tokens; only a SHA-256 of the token is stored. family_id ties
--                     together every token descended from one login, so presenting an already-used
--                     token (theft) can revoke the whole login.
-- local_setup_token   one-time tokens that let a person set their first password (first run, invite).
CREATE TABLE control_plane.local_account (
    id                   UUID PRIMARY KEY,
    email                VARCHAR(320) NOT NULL,
    email_lower          VARCHAR(320) NOT NULL,
    first_name           VARCHAR(255),
    last_name            VARCHAR(255),
    password_hash        VARCHAR(255),
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    enabled              BOOLEAN NOT NULL DEFAULT TRUE,
    setup_pending        BOOLEAN NOT NULL DEFAULT FALSE,
    tenant_id            VARCHAR(63),
    roles                VARCHAR(1000) NOT NULL DEFAULT '',
    attributes           TEXT NOT NULL DEFAULT '{}',
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,
    password_changed_at  TIMESTAMPTZ,
    last_login_at        TIMESTAMPTZ,
    CONSTRAINT uq_local_account_email UNIQUE (email_lower)
);

CREATE INDEX idx_local_account_tenant ON control_plane.local_account (tenant_id);

CREATE TABLE control_plane.local_refresh_token (
    id                 UUID PRIMARY KEY,
    account_id         UUID NOT NULL REFERENCES control_plane.local_account (id) ON DELETE CASCADE,
    family_id          UUID NOT NULL,
    token_hash         CHAR(64) NOT NULL,
    auth_method        VARCHAR(32) NOT NULL DEFAULT 'pwd',
    auth_time          TIMESTAMPTZ NOT NULL,
    issued_at          TIMESTAMPTZ NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    family_expires_at  TIMESTAMPTZ NOT NULL,
    used_at            TIMESTAMPTZ,
    revoked_at         TIMESTAMPTZ,
    CONSTRAINT uq_local_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_local_refresh_token_family ON control_plane.local_refresh_token (family_id);
CREATE INDEX idx_local_refresh_token_account ON control_plane.local_refresh_token (account_id);
CREATE INDEX idx_local_refresh_token_expiry ON control_plane.local_refresh_token (expires_at);

CREATE TABLE control_plane.local_setup_token (
    id           UUID PRIMARY KEY,
    account_id   UUID NOT NULL REFERENCES control_plane.local_account (id) ON DELETE CASCADE,
    token_hash   CHAR(64) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ,
    CONSTRAINT uq_local_setup_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_local_setup_token_account ON control_plane.local_setup_token (account_id);
