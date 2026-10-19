-- Phase 0 Stage 5 (tenant signup flow): control_plane.tenant.name had no
-- uniqueness guarantee at the database level - only an application-level
-- TenantRepository.existsByName() check in TenantProvisioningService,
-- called before any provisioning side effect. That's a real TOCTOU gap:
-- two concurrent signups for the same org name could both pass the
-- pre-check and both proceed, and in the worst case one signup's
-- compensating-rollback cleanup could drop the Postgres schema the OTHER,
-- successful signup just created (see Tenant.java's updated javadoc).
--
-- This constraint makes the database the actual arbiter - the losing
-- concurrent insert now fails immediately, before either request has
-- touched Keycloak or created a schema, closing the race at its source
-- rather than papering over it with a retry or a distributed lock.

ALTER TABLE control_plane.tenant ADD CONSTRAINT uq_tenant_name UNIQUE (name);
