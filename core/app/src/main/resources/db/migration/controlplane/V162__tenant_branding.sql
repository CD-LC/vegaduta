-- White-label minimum-real build (PRICING-MONETIZATION-AUDIT-2026-07-31.md
-- item C2, section 6.2): a tenant's own brand identity for the PUBLIC surfaces
-- that today always render VegaDuta branding (the /agent/{shareId} share
-- viewer). Written via PUT /api/tenant/settings, gated there on the plan's
-- white_label entitlement (plan_limits.white_label - UNLIMITED only today);
-- read on the public share meta endpoint through TenantLimits, which only
-- reports a value while white_label is true, so a downgrade automatically
-- restores VegaDuta branding without touching these columns.
--
-- Both nullable, no default: null means "not set", and every existing tenant
-- keeps platform branding. Same tenant-row storage pattern as
-- use_platform_llm (V100) - tenant-scoped settings live as columns on
-- control_plane.tenant, not in a key-value store.
--
-- V162: controlplane/ and tenant/ share ONE combined Flyway version sequence
-- (see V100's header for the same collision class); V158 was the highest in
-- either folder at the time, and V159-V161 are reserved by parallel
-- in-flight work.
ALTER TABLE control_plane.tenant
    ADD COLUMN brand_display_name VARCHAR(64),
    ADD COLUMN brand_logo_url VARCHAR(512);
