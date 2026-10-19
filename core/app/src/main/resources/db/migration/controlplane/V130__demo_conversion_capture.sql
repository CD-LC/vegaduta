-- Demo -> registration CONVERSION capture (core/tenancy/demo) - the two
-- columns the conversion-completion slice needs on control_plane.demo_session.
--
-- Version note: this repo's Flyway locations merge controlplane/ and tenant/
-- into one shared version sequence (see application.yml's flyway comment).
-- V126-V129 are taken by the sandbox workstreams, so this is V130.
--
-- Context: V126 created demo_session with the conversion seam
-- (converted_tenant_id / converted_at) already in place; this migration adds
-- nothing to that mechanism - the missing piece there was Java wiring
-- (SignupRequest/OnboardingRequest now carry an optional demoToken and both
-- registration paths call DemoConversionService#linkConversion). What DOES
-- need schema support is the two new capture features shipping alongside:
--
-- 1. persona - which of the fixed persona presets (kirana/clinic/tuition/
--    restaurant/salon) the visitor picked when starting the sandbox, or NULL
--    for the generic agent. Persisted so (a) the late/repair agent seed
--    (DemoSessionService#ensureSeededAgent) builds the SAME persona agent the
--    visitor asked for, and (b) the admin analytics can segment conversion
--    rate by persona later. VARCHAR, not an enum/CHECK, on purpose: the
--    persona list is code-owned (DemoPersona) and adding one should not need
--    a migration; an unknown value here is simply treated as "no persona".
--
-- 2. transcript_emails_sent - how many times POST /api/demo/transcript-email
--    has actually SENT this session's transcript (capped at 2 in
--    DemoTranscriptEmailService; a store-the-address-only call that sent no
--    mail does not count). Its own counter, same reasoning V127 gave
--    image_count: the caps it feeds must not be consumable by (or consume)
--    the chat/image allowances.
--
-- No new index: persona is only ever read via the session row already in
-- hand or full-scanned by analytics windows that idx_demo_session_created_at
-- (V129) already serves, and transcript_emails_sent is only ever read via the
-- primary key.

ALTER TABLE control_plane.demo_session
    ADD COLUMN persona VARCHAR(32),
    ADD COLUMN transcript_emails_sent INTEGER NOT NULL DEFAULT 0;
