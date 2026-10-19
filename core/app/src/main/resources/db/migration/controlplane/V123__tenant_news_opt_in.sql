-- Signup consent (web signup wizard, DetailsForm's two consent checkboxes) -
-- the OPTIONAL "Send me product updates & news about VegaDuta" opt-in,
-- default-off, captured once at signup time (TenantProvisioningRecorder
-- #createProvisioningTenant) and never changed by any settings-page toggle
-- today. Defaults false so an existing/backfilled tenant row is correctly
-- opted OUT, matching the checkbox's own default-unchecked state.
--
-- The paired "I agree to the Terms of Service and Privacy Policy" consent on
-- the same screen is UI-gating only (blocks submission client-side) and is
-- deliberately NOT persisted here or anywhere else - there is nothing to
-- store beyond "the form would not submit without it".
ALTER TABLE control_plane.tenant
    ADD COLUMN news_opt_in BOOLEAN NOT NULL DEFAULT FALSE;
