-- Subscription cancellation + failed-payment handling (docs/PRICING-MONETIZATION-
-- AUDIT-2026-07-31.md §8.7, item D5). Two lifecycle columns on the tenant row,
-- both meaningless (and NULL/FALSE) unless the tenant is on a paid plan with a
-- live gateway subscription:
--
--   past_due_since   - when Razorpay first reported subscription.halted (every
--     charge retry exhausted). Non-null == the failed-payment grace window is
--     open: the tenant KEEPS its paid plan while the customer fixes their
--     payment method (Tenant#markPastDue); a later successful charge/resume
--     clears it (Tenant#clearPastDue); subscription.cancelled/completed ends it
--     with the actual FREE downgrade (Tenant#downgradeToFree). First halt wins -
--     re-delivered halted events never reset the clock.
--   cancel_scheduled - the tenant-admin cancelled via POST
--     /api/tenant/subscription/cancel (always cancel_at_cycle_end=1, they keep
--     what they paid for) and Razorpay accepted. Intent bookkeeping only - the
--     plan stays paid until the gateway's subscription.cancelled webhook lands
--     at cycle end, which is what actually flips plan to FREE and resets this.
--
-- Version 161: the tenant/ and controlplane/ folders share ONE combined Flyway
-- version sequence (see V114's header; duplicate numbers = the V156 prod
-- incident of 2026-07-31). 159 (tenant_referral), 160 (billing_invoice) and 162
-- (tenant_branding) are claimed by parallel agents this session - 161 verified
-- free across BOTH folders immediately before this file was written.
ALTER TABLE control_plane.tenant
    ADD COLUMN past_due_since   TIMESTAMPTZ,
    ADD COLUMN cancel_scheduled BOOLEAN NOT NULL DEFAULT FALSE;
