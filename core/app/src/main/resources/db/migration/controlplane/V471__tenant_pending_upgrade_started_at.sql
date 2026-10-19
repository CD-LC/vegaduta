-- Freemium billing hardening (2026-10-05) - timestamps when a self-serve
-- upgrade was INITIATED (Tenant.beginUpgrade), so the new
-- StalePendingUpgradeCleanup job can clear a pending upgrade whose gateway
-- checkout was abandoned: until now pending_plan / pending_gateway_subscription_id
-- had no timestamp at all, so "abandoned at checkout" rows could sit forever,
-- and a late-arriving gateway webhook could resurrect an upgrade the tenant
-- walked away from days earlier.
--
-- Backfill: rows already carrying a pending subscription get NOW() - the
-- cleanup window starts fresh for them rather than treating every
-- pre-existing pending upgrade as instantly stale mid-deploy (a customer can
-- legitimately be inside the checkout window as this migration runs; Razorpay
-- subscriptions get a 48h expire_by gateway-side anyway).
ALTER TABLE control_plane.tenant
    ADD COLUMN pending_upgrade_started_at TIMESTAMPTZ;

UPDATE control_plane.tenant
SET pending_upgrade_started_at = NOW()
WHERE pending_gateway_subscription_id IS NOT NULL;
