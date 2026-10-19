-- "Your own provider" could not be selected during the complimentary grant.
--
-- TenantLimits#usesPlatformPool treats an active comp grant as "hosted pool"
-- because use_platform_llm defaults false and a fresh signup never chose
-- anything. But that made false mean BOTH "never chose" and "chose BYOK", so a
-- grant tenant who activated a provider and picked "Your own provider" wrote
-- false over false and stayed on the pool. This flag records that the tenant
-- admin has made an explicit choice; once set, use_platform_llm alone decides.
--
-- Additive, defaulted, IF NOT EXISTS: safe under a rolling deploy (the old
-- revision does not map the column) and on re-run.
ALTER TABLE control_plane.tenant
    ADD COLUMN IF NOT EXISTS llm_source_explicit BOOLEAN NOT NULL DEFAULT FALSE;

-- A tenant already opted INTO the pool made an explicit choice.
UPDATE control_plane.tenant SET llm_source_explicit = TRUE WHERE use_platform_llm = TRUE;
