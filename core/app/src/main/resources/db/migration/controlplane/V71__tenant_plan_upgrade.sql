-- #811: "Wire tenant-console 'Upgrade your plan' text to a real flow" -
-- until now there was no way for a tenant-admin to actually change their own
-- plan; the only real write path to Tenant.plan was the platform super-admin
-- override (PlatformTenantPlanService). These two columns let a tenant-admin
-- self-serve: pending_plan/pending_gateway_subscription_id record an
-- in-flight Razorpay subscription created for an upgrade (Tenant.plan itself
-- stays unchanged until the subscription actually activates - Tenant#confirmUpgrade,
-- called from the new /webhook/razorpay handler, is what flips it). Both
-- nullable and unset for every existing row - most tenants have never
-- attempted an upgrade.
--
-- Deliberately separate from the existing gateway_subscription_id/
-- subscription_status/trial_ends_at columns (added by V11 for the
-- signup-time trial subscription) rather than overwriting them: those three
-- describe whichever subscription TenantProvisioningService created at
-- signup (every tenant gets one, even FREE-plan tenants - a known, separate
-- quirk, not fixed here). An in-flight upgrade attempt must not clobber that
-- row's own state while it's still pending authorization.
ALTER TABLE control_plane.tenant
    ADD COLUMN pending_plan VARCHAR(32),
    ADD COLUMN pending_gateway_subscription_id VARCHAR(64);
