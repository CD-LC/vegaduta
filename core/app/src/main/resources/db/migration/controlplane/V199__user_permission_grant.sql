-- Fine-grained, tenant-admin-mixable permissions layered on top of the four
-- fixed Keycloak realm roles (Permission.java / TenantAssignablePermissions.java
-- in core/admin). Additive-only: does not touch app_user, tenant, or any
-- Keycloak realm data.
--
-- tenant_id/keycloak_user_id are VARCHAR, not UUID/FK - matches how
-- TenantContext/KeycloakAdminClient already treat both values as opaque
-- strings compared against the JWT's tenant_id claim and Keycloak's own user
-- store, never joined against control_plane.tenant or control_plane.app_user
-- (app_user rows only exist for the original signup admin, per its own
-- javadoc - a FK there would silently fail to grant permissions to anyone
-- an admin later invites).
CREATE TABLE control_plane.user_permission_grant (
    id                UUID PRIMARY KEY,
    tenant_id         VARCHAR(255) NOT NULL,
    keycloak_user_id  VARCHAR(255) NOT NULL,
    permission        VARCHAR(64)  NOT NULL,
    granted_by        VARCHAR(255) NOT NULL,
    granted_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_user_permission_grant UNIQUE (keycloak_user_id, permission)
);

CREATE INDEX idx_user_permission_grant_tenant_user
    ON control_plane.user_permission_grant (tenant_id, keycloak_user_id);
