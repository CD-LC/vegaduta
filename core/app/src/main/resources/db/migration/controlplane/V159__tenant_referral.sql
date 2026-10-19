-- Referral program (docs/PRICING-MONETIZATION-AUDIT-2026-07-31.md §8.5 item D3):
-- give-1-month-get-1-month PREMIUM. The grant itself rides the existing
-- comp-grant machinery (V114's comp_plan_* columns + Tenant#grantComplimentaryPlan
-- + CompGrantExpiryReverter reverting to FREE at expiry) exactly like the V157
-- event trial does - these three columns exist only to make the referral graph
-- durable, because the reverter nulls the comp_plan_* columns at expiry:
--
--   referral_code          - this tenant's own shareable code, lazily generated
--     (uppercase, collision-checked) by ReferralService on the first
--     GET /api/tenant/referral and never changed after. UNIQUE because redemption
--     resolves referrer BY code; NULL until first read.
--   referred_by_tenant_id  - whose code THIS tenant redeemed (control_plane.tenant
--     id). Written once by ReferralService#redeem, never cleared - non-null means
--     this tenant has used its one redemption, forever, even after the comp lapses.
--     Deliberately no FK: the referrer row can be erased (TenantStatus.ERASED keeps
--     the row today, but nothing should couple referral bookkeeping to that).
--   referral_redeemed_at   - when the redemption happened; also what the referrer's
--     rolling-365-day 12-grant anti-abuse cap counts.
--
-- Version 159: the tenant/ and controlplane/ folders share ONE combined Flyway
-- version sequence (see V114's header); 158 (premium_kb_collections_uncapped) was
-- the highest across both folders when this was written. NOTE for the
-- orchestrator: other agents work this repo in parallel - re-verify 159 is still
-- free before commit (duplicate version numbers = the V156 prod incident of
-- 2026-07-31).
ALTER TABLE control_plane.tenant
    ADD COLUMN referral_code         VARCHAR(16),
    ADD COLUMN referred_by_tenant_id UUID,
    ADD COLUMN referral_redeemed_at  TIMESTAMPTZ;

ALTER TABLE control_plane.tenant
    ADD CONSTRAINT uq_tenant_referral_code UNIQUE (referral_code);

-- ReferralService counts a referrer's redemptions by referred_by_tenant_id (the
-- rolling-year cap check on every redeem + the all-time count on every status
-- read); partial index keeps that cheap without indexing the NULL majority.
CREATE INDEX idx_tenant_referred_by_tenant_id
    ON control_plane.tenant (referred_by_tenant_id)
    WHERE referred_by_tenant_id IS NOT NULL;
