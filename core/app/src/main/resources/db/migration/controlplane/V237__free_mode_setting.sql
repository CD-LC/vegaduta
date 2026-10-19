-- Free mode (2026-09-22, owner's instruction as VegaDuta moves to an
-- open-source posture): when 'true', every tenant resolves as
-- TenantPlan.UNLIMITED for FEATURE gates (plan caps, BYOK, WhatsApp,
-- white-label, edit locks, guardrail tiers). Pricing logic, stored plans and
-- the billing machinery are untouched - flipping this row to 'false' restores
-- normal enforcement with nothing to repair. The platform-hosted-LLM
-- anti-abuse caps (FreeTierUsageLimiterService, V194's settings) stay ON in
-- free mode - they bound real provider spend, not revenue.
--
-- Seeded 'true' deliberately: the flag exists to be on for the open-source
-- release. A self-hoster who wants paid plans sets it 'false' from the admin
-- platform-settings surface or with an UPDATE - no redeploy needed, readers
-- pick the change up within FreeModeService's ~10s cache.
--
-- Plain row in the existing control_plane.platform_setting table (V91),
-- identical posture to V117/V194. FreeModeService falls back to 'false'
-- in-code if the row is absent, so a database that predates this migration
-- keeps enforcing plans.
--
-- Version V237: the tenant + control_plane migration folders share ONE
-- Flyway sequence; confirmed free by listing every V*.sql under both
-- db/migration/controlplane (max V236) and db/migration/tenant (max V235).
INSERT INTO control_plane.platform_setting (key, value, description) VALUES
    ('platform.free-mode.enabled', 'true', 'When true, every tenant resolves as UNLIMITED for feature gates (open-source / free-for-everyone mode). Stored plans and billing logic are untouched; hosted-LLM anti-abuse caps still apply. Set to false to re-enable paid-tier enforcement.')
ON CONFLICT (key) DO NOTHING;
