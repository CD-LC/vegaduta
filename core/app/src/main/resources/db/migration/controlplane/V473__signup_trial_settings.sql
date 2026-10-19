-- Admin-configurable signup trial (2026-10-08). New self-service signups get a complimentary paid plan for a while,
-- then drop to FREE (CompGrantExpiryReverter) and must subscribe to keep the paid features. The length, plan and an
-- optional fixed end date used to be hardcoded in TenantProvisioningRecorder (3 months of UNLIMITED, floor 2027-01-01);
-- they are now platform_setting rows, editable on the admin Settings page and read on every signup (no restart).
--   platform.signup-trial.enabled     'true' | 'false'  - false = new signups start on FREE, no bonus
--   platform.signup-trial.months      whole calendar months (0-36); 0 behaves like disabled
--   platform.signup-trial.plan        'UNLIMITED' | 'PREMIUM'
--   platform.signup-trial.free-until  optional ISO date/instant (e.g. 2027-01-01); blank = none. When set, a signup
--                                     gets the LATER of (signup + months) and this date (a launch promotion floor).
-- Seeded with today's standing behaviour minus the 2027-01-01 floor (it no longer binds for new signups, since
-- 3 months from now is already later). Existing grants are untouched.
INSERT INTO control_plane.platform_setting (key, value, description) VALUES
    ('platform.signup-trial.enabled', 'true', 'When true, every new self-service signup gets a complimentary paid plan (see months/plan) before landing on FREE. Set false to start new signups on FREE with no bonus.'),
    ('platform.signup-trial.months', '3', 'Calendar months of complimentary access a new signup gets (0-36). 0 = no bonus.'),
    ('platform.signup-trial.plan', 'UNLIMITED', 'Plan granted during the signup trial: UNLIMITED or PREMIUM.'),
    ('platform.signup-trial.free-until', '', 'Optional ISO date (e.g. 2027-01-01): signups get the LATER of (signup + months) and this date. Leave blank for none.')
ON CONFLICT (key) DO NOTHING;
