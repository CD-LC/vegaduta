-- Agentic UPI Payments (Build Specs §E1) - seed the npci_uap feature flag.
--
-- The flag gates readiness for NPCI's Unified Agent Protocol: when the spec
-- lands, an NpciUapAdapter plugs in next to RazorpayAdapter behind
-- UpiAgentProtocolAdapter and this flag flips routing per tenant
-- (FeatureFlagService's deterministic rollout bucketing). Off and 0% by
-- default - it is a readiness surface, not a live path, until a UAP adapter
-- actually exists. The web-admin Feature Flags section reads/writes
-- control_plane.feature_flag generically, so seeding the row is all that is
-- needed to surface it in Settings.

INSERT INTO control_plane.feature_flag (
    id, flag_key, description, enabled, rollout_percentage, created_at, updated_at
) VALUES (
    gen_random_uuid(),
    'npci_uap',
    'NPCI Unified Agent Protocol readiness: when on, mandates route to the UAP adapter (when implemented) instead of Razorpay for eligible tenants.',
    FALSE,
    0,
    now(),
    now()
)
ON CONFLICT (flag_key) DO NOTHING;
