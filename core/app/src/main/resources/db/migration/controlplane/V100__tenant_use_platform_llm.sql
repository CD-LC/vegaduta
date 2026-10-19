-- Direct feedback (issue report): "every non free tenant should get an
-- option to select use platform llm" - a PREMIUM/UNLIMITED tenant's own
-- opt-in to route chat calls through the platform-hosted pool instead of
-- BYOK. Defaults false - BYOK stays the default for a paying tenant unless
-- they deliberately opt in via PUT /api/tenant/settings.
--
-- Renumbered from V97 to V100 - the tenant/ folder had independently grown
-- through V99 by the time this was written, and this project's Flyway setup
-- merges controlplane/ and tenant/ into one combined version sequence (see
-- V79's javadoc for the same class of collision). V100 is the first free
-- slot past both folders' highest version at the time.
ALTER TABLE control_plane.tenant
    ADD COLUMN use_platform_llm BOOLEAN NOT NULL DEFAULT FALSE;
