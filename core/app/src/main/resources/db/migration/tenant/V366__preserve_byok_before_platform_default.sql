-- Platform pool becomes the DEFAULT LLM source for every tenant
-- (TenantLimitsServiceImpl#usesPlatformPool now returns true unless the tenant
-- admin made an explicit choice - llm_source_explicit).
--
-- Before this change a PAID tenant with no comp grant and no opt-in resolved to
-- BYOK by fall-through. Under the new default that fall-through means "pool",
-- which would silently move tenants that already run their own provider key off
-- that key and onto platform-paid spend. So: every tenant that (a) is on a paid
-- plan, (b) is outside a live complimentary grant, (c) never chose explicitly and
-- (d) HAS an ACTIVE own provider credential in its schema is recorded as an
-- explicit BYOK choice. Nothing changes for them. Tenants with no key, FREE
-- tenants, grant tenants and everyone who already chose keep their current
-- answer (pool) - a no-key paid tenant used to fail with "No active LLM
-- provider" and now simply works.
--
-- Same shape as tenant/V155: this runs once per schema, joins
-- control_plane.tenant on schema_name = current_schema(), and is a no-op where
-- there is no matching tenant row (the `public` schema), no credential table, or
-- the control plane is not present. The to_regclass guard + EXECUTE keep the
-- control_plane reference from being resolved at plan time. Idempotent: it only
-- touches rows where llm_source_explicit is still FALSE.
DO $$
BEGIN
    IF to_regclass('control_plane.tenant') IS NULL
        OR to_regclass('llm_provider_credential') IS NULL THEN
        RAISE NOTICE 'V366: control plane or credential table not present for schema %, skipping', current_schema();
        RETURN;
    END IF;

    EXECUTE $q$
        UPDATE control_plane.tenant t
           SET llm_source_explicit = TRUE,
               use_platform_llm = FALSE
         WHERE t.schema_name = current_schema()
           AND t.llm_source_explicit = FALSE
           AND t.plan <> 'FREE'
           AND (t.comp_plan_expires_at IS NULL OR t.comp_plan_expires_at <= now())
           AND EXISTS (SELECT 1 FROM llm_provider_credential c WHERE c.status = 'ACTIVE')
    $q$;
END
$$;
