-- SECURITY FIX (2026-07-29) - backfills the `created_by` column that
-- tenant/V151__agent_workflow_owner.sql deliberately left NULL on every
-- pre-existing row.
--
-- WHY THIS REVERSES V151's DECISION
-- ---------------------------------
-- V151's header argues at length that "nullable, no backfill, no breaking
-- change" is safe because ResourceAccessService#requireAccess treats
-- createdBy == null as AccessCheckResult.LEGACY_OPEN. What that reasoning
-- missed is that LEGACY_OPEN was returned *regardless of the required
-- permission level* and before any caller identity was even looked at - so
-- "preserves today's behavior" actually meant "the entire new permission
-- model is inert". A live check of this database on 2026-07-29 found 38
-- agents, 38 of them with created_by IS NULL: every requireAccess call in
-- the product - RUNNER, VIEWER and EDITOR alike - was a no-op. The feature
-- shipped; nothing it gates was gated.
--
-- The fix has two halves and BOTH are required:
--   1. This migration, which gives every legacy row whose owner is
--      unambiguously resolvable a real owner.
--   2. ResourceAccessService#requireAccess, which no longer lets
--      LEGACY_OPEN bypass `required` - a row this migration cannot resolve
--      is now runnable by anyone but readable/editable only by a
--      tenant-admin or an explicit grantee. See that method's javadoc.
--
-- VERSION NUMBER
-- --------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into
-- ONE version stream (see V151's own note, and application.yml's list of the
-- three times a collision has already broken this build). Highest version
-- present in EITHER folder as of this writing: V154
-- (tenant/V154__knowledge_dedup_and_fts_language.sql; controlplane/'s
-- highest is V153__smart_ai_budget_settings.sql). Both folders were checked;
-- no V155 exists in either. This lives in tenant/ because created_by is a
-- per-tenant-schema column - same folder as V151, which added it.
--
-- WHICH USER GETS THE ROWS, AND WHY THAT IS RESOLVABLE AT ALL
-- ----------------------------------------------------------
-- A tenant schema itself has no user table - the schema inspection behind
-- this migration confirmed there is no `user`/`tenant_user`/`member` table
-- anywhere in a tenant schema, and audit_log_entry.actor_id (the only other
-- per-schema subject id) is empty in the affected tenants. But user identity
-- is NOT Keycloak-only: control_plane.app_user mirrors it into Postgres with
-- (tenant_id, keycloak_user_id, role, created_at), so a tenant migration can
-- resolve its own tenant's members by joining control_plane.tenant on
-- schema_name = current_schema(). Referencing control_plane.* from a tenant/
-- migration is established practice here (see tenant/V104 and tenant/V141).
--
-- The owner picked is the tenant's EARLIEST TENANT_ADMIN, falling back to
-- its earliest member of any role. Rationale: the earliest admin is the
-- account that provisioned the tenant and its schema, so every row that
-- predates ownership tracking was created under it. It also grants nothing
-- new - a tenant-admin already reaches every resource in their tenant via
-- AccessCheckResult.ADMIN_OVERRIDE - it only records WHO is accountable for
-- resources that currently have no owner at all. In this database every
-- non-demo tenant has exactly one app_user row and that row is TENANT_ADMIN,
-- so "earliest admin" and "the only member" are the same person today; the
-- ORDER BY exists so the rule stays defined once tenants have several.
--
-- WHAT IS DELIBERATELY LEFT UNOWNED
-- ---------------------------------
-- A tenant with ZERO rows in control_plane.app_user gets NO backfill. That
-- is not a gap to close later - it is the correct answer. 29 of the 38
-- legacy agents live in demo_* sandbox schemas, which have no app_user rows
-- because an anonymous demo session has no user identity to record. There is
-- no true owner to find, so this migration invents none; those rows stay
-- created_by IS NULL and are governed by the narrowed LEGACY_OPEN rule
-- instead (runnable anonymously, which is exactly what the demo funnel
-- needs; not readable/editable by an arbitrary console user).
--
-- SAFE WHERE IT IS A NO-OP
-- ------------------------
-- The startup Flyway run applies tenant/ against the `public` schema too
-- (that is why public.agent exists). current_schema() = 'public' matches no
-- control_plane.tenant row, the owner subquery is empty, and the guarded
-- UPDATE does nothing. Same for any schema whose tenant has no members.
-- Idempotent: WHERE created_by IS NULL means a re-run never reassigns a row
-- that already has an owner.
--
-- The to_regclass guard + EXECUTE is not decoration. A plain statement
-- naming control_plane.app_user is parsed when the migration is planned, so
-- it would hard-fail on any ordering where the control plane is not up yet
-- (TenantSchemaMigrator runs the tenant/ location alone, against one schema,
-- outside the merged startup run). Inside EXECUTE the reference is resolved
-- only if the guard passes.
DO $$
DECLARE
    owner_subject VARCHAR(255);
BEGIN
    IF to_regclass('control_plane.app_user') IS NULL
        OR to_regclass('control_plane.tenant') IS NULL THEN
        RAISE NOTICE 'V155: control plane tables not present for schema %, leaving created_by untouched', current_schema();
        RETURN;
    END IF;

    EXECUTE $q$
        SELECT u.keycloak_user_id
        FROM control_plane.app_user u
        JOIN control_plane.tenant t ON t.id = u.tenant_id
        WHERE t.schema_name = current_schema()
        -- FALSE sorts before TRUE, so admins come first; then oldest
        -- account; keycloak_user_id only as a deterministic tiebreak.
        ORDER BY (u.role IS DISTINCT FROM 'TENANT_ADMIN'), u.created_at, u.keycloak_user_id
        LIMIT 1
    $q$ INTO owner_subject;

    IF owner_subject IS NULL THEN
        RAISE NOTICE 'V155: no control_plane.app_user row for schema % - leaving legacy rows unowned by design', current_schema();
        RETURN;
    END IF;

    EXECUTE 'UPDATE agent SET created_by = $1 WHERE created_by IS NULL' USING owner_subject;
    EXECUTE 'UPDATE workflow_definition SET created_by = $1 WHERE created_by IS NULL' USING owner_subject;
END
$$;
