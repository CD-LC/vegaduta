-- Repurposes control_plane.tenant.plan (backed by TenantPlan, moved from
-- com.agenticai.core.tenancy to com.agenticai.core.security in this same
-- change - see that enum's javadoc) from its old, dormant values
-- (FREE_SELF_HOSTED/STARTER/BUSINESS/ENTERPRISE - written at signup, never
-- once read to gate anything, per the "universal tier" product decision) to
-- the real Free/Premium/Unlimited tiers described in
-- Agentic-AI-Platform-Subscription-Plan-and-GTM-Strategy.md.
--
-- Must run BEFORE the new Java enum constants are live: TenantPlan is
-- @Enumerated(EnumType.STRING) with no DB CHECK constraint (same "plain
-- VARCHAR, no migration needed for new values" posture as ProviderName), so
-- any existing row still holding the literal text 'STARTER' etc. would fail
-- to deserialize the instant the enum's Java constant of that name no longer
-- exists. Every row gets rewritten here first so no such row can ever be
-- read by the new code.
UPDATE control_plane.tenant SET plan = 'FREE' WHERE plan IN ('FREE_SELF_HOSTED', 'STARTER');
UPDATE control_plane.tenant SET plan = 'PREMIUM' WHERE plan = 'BUSINESS';
UPDATE control_plane.tenant SET plan = 'UNLIMITED' WHERE plan = 'ENTERPRISE';
