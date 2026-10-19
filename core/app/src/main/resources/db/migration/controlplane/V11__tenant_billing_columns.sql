-- Billing Build Plan Stage 2. control_plane-qualified, global, run once -
-- same reasoning as V8/V9 (this is platform-level tenant metadata, not
-- per-tenant-schema data).
--
-- All nullable: existing tenants (tenant_b, verify_org_a, verify_org_b, and
-- whatever else predates this migration) have no gateway relationship yet
-- and must not break on this migration or on subsequent reads.
--
-- gateway_customer_id is nullable and populated later than
-- gateway_subscription_id - Razorpay's Subscriptions flow creates the
-- subscription first and only creates+links its own customer record once
-- the tenant's admin completes checkout (see PaymentGatewayClient's javadoc
-- for why this differs from a Stripe-shaped create-Customer-then-Subscription
-- flow). A row can legitimately have gateway_subscription_id set and
-- gateway_customer_id still null for the entire trial-not-yet-authenticated
-- window.
--
-- subscription_status intentionally stores the gateway's own status strings
-- (Razorpay's: created/authenticated/active/pending/halted/paused/cancelled/
-- completed) rather than a parallel app-level enum that could quietly drift
-- from what the gateway actually reports.

ALTER TABLE control_plane.tenant
    ADD COLUMN payment_gateway VARCHAR(32),
    ADD COLUMN gateway_customer_id VARCHAR(64),
    ADD COLUMN gateway_subscription_id VARCHAR(64),
    ADD COLUMN subscription_status VARCHAR(32),
    ADD COLUMN trial_ends_at TIMESTAMPTZ,
    ADD COLUMN current_period_end TIMESTAMPTZ;
