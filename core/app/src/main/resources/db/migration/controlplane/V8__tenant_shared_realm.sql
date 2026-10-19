-- Phase 0 Stage 5 prerequisite fix: Stage 1's control_plane.tenant table was
-- created with a UNIQUE constraint on keycloak_realm, under the assumption
-- (an open design question at the time) that each tenant would get its own
-- Keycloak realm. That question was resolved during Stage 4's follow-up work
-- toward a single shared "agentic-ai" realm instead - tenant identity comes
-- from a per-user tenant_id attribute/claim, not a separate realm per tenant.
--
-- Left as-is, the very first Stage 5 signup after the first tenant would
-- violate this constraint (every row storing the same "agentic-ai" value) -
-- caught here, before Stage 5's provisioning code was even written, rather
-- than as a runtime surprise on tenant #2's signup.
--
-- The column itself is kept (not dropped) - see Tenant.java's javadoc for
-- why: it remains meaningful for a possible future Enterprise-plan tenant
-- with its own dedicated realm, just no longer required to be unique today.

ALTER TABLE control_plane.tenant DROP CONSTRAINT uq_tenant_keycloak_realm;
