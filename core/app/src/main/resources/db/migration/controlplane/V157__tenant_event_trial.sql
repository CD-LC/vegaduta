-- Event-triggered 7-day Premium trial (docs/PRICING-MONETIZATION-AUDIT-2026-07-31.md
-- §5.4 "Trial strategy" / approval item B6): a FREE tenant that has never paid and
-- never trialled before can claim ONE 7-day PREMIUM comp at a moment of high intent
-- (first WhatsApp-connect attempt, first edit-lock collision). The grant itself
-- rides the existing comp-grant machinery (V114's comp_plan_* columns +
-- Tenant#grantComplimentaryPlan + CompGrantExpiryReverter reverting to FREE at
-- expiry) - these three columns exist ONLY to make the one-per-tenant claim
-- durable: CompGrantExpiryReverter nulls the comp_plan_* columns when the trial
-- lapses, so eligibility ("has this tenant EVER claimed a trial?") cannot be
-- answered from those. Written once by TrialGrantService#claim and never cleared.
--
--   event_trial_claimed_at - when the trial was claimed; non-null == claimed,
--     which permanently disqualifies this tenant from a second trial.
--   event_trial_expires_at - the claimed trial's expiry, kept after the comp
--     reverter clears comp_plan_expires_at so GET /api/tenant/trial/status can
--     still report when the (now lapsed) trial ended.
--   event_trial_trigger    - which product moment triggered the claim
--     (e.g. 'whatsapp-gate', 'edit-lock') - conversion analytics provenance;
--     also recorded in comp_plan_reason as 'event-trial:<trigger>'.
--
-- Version 157: the tenant/ and controlplane/ folders share ONE combined Flyway
-- version sequence (see V114's header); 156 (community_template) was the highest
-- across both folders when this was written. NOTE for the orchestrator: other
-- agents are working this same session - re-verify 157/158 are still free before
-- commit (duplicate version numbers = the V156 prod incident of 2026-07-31).
ALTER TABLE control_plane.tenant
    ADD COLUMN event_trial_claimed_at TIMESTAMPTZ,
    ADD COLUMN event_trial_expires_at TIMESTAMPTZ,
    ADD COLUMN event_trial_trigger    VARCHAR(64);
