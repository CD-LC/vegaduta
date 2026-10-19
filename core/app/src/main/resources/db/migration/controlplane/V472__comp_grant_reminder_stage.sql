-- Complimentary-plan expiry reminders (2026-10-08). A signup/promo/trial comp grant ends by itself (the hourly
-- CompGrantExpiryReverter drops the tenant back to FREE), but nothing told the customer it was coming - so there was
-- no nudge to subscribe. CompGrantReminderJob now emails at ~14 days and ~3 days before the end; this column makes
-- each reminder fire ONCE per grant and survive a missed tick or a restart:
--   0 = none sent, 1 = the 14-day reminder sent, 2 = the 3-day reminder sent.
-- Reset to 0 by Tenant.grantComplimentaryPlan (a re-grant restarts the sequence) and Tenant.revertComplimentaryGrant.
-- INTEGER (not SMALLINT) so the entity's int maps without a Hibernate schema-validation type mismatch.
ALTER TABLE control_plane.tenant
    ADD COLUMN comp_plan_reminder_stage INTEGER NOT NULL DEFAULT 0;
