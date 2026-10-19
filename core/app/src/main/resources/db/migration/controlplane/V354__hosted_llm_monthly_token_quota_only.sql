-- 2026-09-26 quota simplification (product-owner direction): the
-- platform-hosted-LLM-pool quota collapsed to a single rolling-30-day token
-- window. The daily token window and BOTH message-count windows (30/day,
-- 300/mo plan-scaled) were removed from FreeTierUsageLimiterService - the
-- monthly token counter alone bounds the platform's hosted-provider spend,
-- matching how competitors meter hosted usage (one unit, one window).
--
-- This migration deletes the now-unread daily-token-limit setting row and
-- refreshes the remaining rows' descriptions, which still referenced the
-- retired daily/message windows. monthly-token-limit / premium-multiplier /
-- unlimited-multiplier stay live and admin-tunable via
-- GET/PUT /api/platform/free-tier-quota.
--
-- Version V354: the tenant + control_plane migration folders share ONE
-- Flyway sequence scanned recursively; confirmed V354 is the global
-- next-free number by listing every V*.sql file under both
-- db/migration/controlplane and db/migration/tenant (highest existing was
-- V353__demo_whatsapp_channel.sql, under controlplane/) - do not renumber
-- below the current global max.

DELETE FROM control_plane.platform_setting
WHERE key = 'platform.hosted-llm-quota.daily-token-limit';

UPDATE control_plane.platform_setting SET description =
    'Base (FREE-plan) rolling-30-day token cap per tenant on the platform-hosted LLM pool. PREMIUM/UNLIMITED multiply this by their respective multiplier settings.'
WHERE key = 'platform.hosted-llm-quota.monthly-token-limit';

UPDATE control_plane.platform_setting SET description =
    'Multiplier applied to the base monthly token limit for PREMIUM-plan tenants using the platform-hosted LLM pool.'
WHERE key = 'platform.hosted-llm-quota.premium-multiplier';

UPDATE control_plane.platform_setting SET description =
    'Multiplier applied to the base monthly token limit for UNLIMITED-plan tenants using the platform-hosted LLM pool. With the default 400,000/month base this yields a 1,200,000 tokens/30-day effective cap.'
WHERE key = 'platform.hosted-llm-quota.unlimited-multiplier';
