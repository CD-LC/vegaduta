-- 2026-08-08 hardening: makes the platform-hosted-LLM-pool FREE-tier TOKEN
-- quota admin-tunable, same "add one migration row, add one typed getter"
-- pattern V117 already established for PerUserBudgetService's dollar cap
-- (see that migration's own header comment for the full rationale).
--
-- Prior to this migration, FreeTierUsageLimiterService's daily/monthly token
-- limits and the PREMIUM/UNLIMITED plan multipliers were configurable only
-- via @Value properties (app.free-tier.daily-token-limit,
-- app.platform-llm.premium-multiplier / unlimited-multiplier) - build-time/
-- redeploy-only, unlike the per-user dollar budget's live admin surface.
-- Direct feedback (2026-07-26) set the multipliers at 2x (PREMIUM) / 3x
-- (UNLIMITED) of the FREE base, so with the 40,000/day default below the
-- effective UNLIMITED-plan daily token cap is 40,000 * 3 = 120,000 tokens/day.
--
-- These are plain rows in the existing generic control_plane.platform_setting
-- table (V91), NOT a new table - identical posture to V117.
-- FreeTierUsageLimiterService reads them via PlatformSettingsService and
-- falls back to these exact same defaults in-code if a row is absent, so the
-- service works even against a database that predates this migration.
--
-- Version V194: the tenant + control_plane migration folders share ONE
-- Flyway sequence scanned recursively; confirmed V194 is the global
-- next-free number by listing every V*.sql file under both
-- db/migration/controlplane and db/migration/tenant (highest existing was
-- V193__workflow_share.sql, under tenant/) - do not renumber below the
-- current global max. control_plane-scoped because platform_setting lives
-- in the control_plane schema.
--
-- Units: daily/monthly token limits are plain integer TOKEN counts (prompt +
-- completion, per FreeTierUsageLimiterService's javadoc); the two
-- multipliers are plain integers applied to the FREE base for PREMIUM /
-- UNLIMITED tenants respectively.
INSERT INTO control_plane.platform_setting (key, value, description) VALUES
    ('platform.hosted-llm-quota.daily-token-limit', '40000', 'Base (FREE-plan) daily token cap per tenant on the platform-hosted LLM pool. PREMIUM/UNLIMITED multiply this by their respective multiplier settings.'),
    ('platform.hosted-llm-quota.monthly-token-limit', '400000', 'Base (FREE-plan) monthly token cap per tenant on the platform-hosted LLM pool. PREMIUM/UNLIMITED multiply this by their respective multiplier settings.'),
    ('platform.hosted-llm-quota.premium-multiplier', '2', 'Multiplier applied to the base daily/monthly token limits (and message limits) for PREMIUM-plan tenants using the platform-hosted LLM pool.'),
    ('platform.hosted-llm-quota.unlimited-multiplier', '3', 'Multiplier applied to the base daily/monthly token limits (and message limits) for UNLIMITED-plan tenants using the platform-hosted LLM pool. With the default 40,000/day base this yields a 120,000 tokens/day effective cap.')
ON CONFLICT (key) DO NOTHING;
