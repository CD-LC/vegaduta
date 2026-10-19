-- Phase 0 Stage 2: control-plane schema - Tenant and AppUser, the two new
-- entities this stage adds. Kept in its own `control_plane` schema,
-- deliberately separate from any future per-tenant schema (Stage 4/5), so
-- "which tenant owns this row" data and "this tenant's actual agents/
-- providers/knowledge" data never live side by side in the same namespace.
--
-- No password/credential column anywhere below - Keycloak (Phase 0 Stage 1)
-- owns authentication entirely; app_user.keycloak_user_id is a foreign
-- reference to a Keycloak user record, not a local credential store.

CREATE SCHEMA IF NOT EXISTS control_plane;

CREATE TABLE control_plane.tenant (
    id             UUID PRIMARY KEY,
    name           VARCHAR(255) NOT NULL,
    plan           VARCHAR(32)  NOT NULL,
    schema_name    VARCHAR(63)  NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    keycloak_realm VARCHAR(255) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_tenant_schema_name    UNIQUE (schema_name),
    CONSTRAINT uq_tenant_keycloak_realm UNIQUE (keycloak_realm)
);

CREATE TABLE control_plane.app_user (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL REFERENCES control_plane.tenant (id),
    keycloak_user_id  VARCHAR(255) NOT NULL,
    email             VARCHAR(255) NOT NULL,
    role              VARCHAR(32)  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_app_user_keycloak_user_id UNIQUE (keycloak_user_id),
    CONSTRAINT uq_app_user_tenant_email     UNIQUE (tenant_id, email)
);

CREATE INDEX idx_app_user_tenant_id ON control_plane.app_user (tenant_id);
