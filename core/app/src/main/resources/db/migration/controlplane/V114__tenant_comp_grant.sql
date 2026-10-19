-- Academy Fast-Track Scholarship one-click fulfilment (CORE-COMP-GRANT-CONTRACT.md)
-- - a complimentary plan grant: the Academy admin marks a scholarship claim
-- fulfilled and core flips the tenant to a paid tier (UNLIMITED/PREMIUM) as a
-- COMP grant - NO Razorpay subscription, NO charge - that auto-expires. This is
-- deliberately distinct from the paid self-serve upgrade (V71's pending_plan/
-- pending_gateway_subscription_id + Tenant#confirmUpgrade), which always goes
-- through a real gateway subscription. A comp grant touches none of the
-- gateway_* columns for exactly that reason.
--
-- Three nullable columns, all unset for every existing row (no tenant has a
-- comp grant until the Academy issues one):
--   comp_plan_expires_at - when the grant lapses; CompGrantExpiryReverter
--     reverts plan -> FREE once now() passes this and clears all three columns.
--   comp_plan_granted_at - when it was issued (surfaced in the grant response).
--   comp_plan_reason      - free-text provenance, e.g. "academy-fast-track-scholarship".
--
-- Version 114: the tenant/ and controlplane/ folders share one combined Flyway
-- version sequence (see application.yml's spring.flyway block and V100's header),
-- and 114 is the first free slot past both folders' highest version at the time.
ALTER TABLE control_plane.tenant
    ADD COLUMN comp_plan_expires_at TIMESTAMPTZ,
    ADD COLUMN comp_plan_granted_at TIMESTAMPTZ,
    ADD COLUMN comp_plan_reason     VARCHAR(255);
