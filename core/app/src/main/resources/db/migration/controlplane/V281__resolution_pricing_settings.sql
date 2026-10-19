-- Outcome-based pricing substrate (Tier-4 structural batch), control-plane
-- half: seed the platform_setting rows the resolution meter reads. All
-- three are OPTIONAL (ResolutionPricingService falls back to the same
-- defaults when a row is absent) - seeding them is what makes the knobs
-- discoverable/tunable in the admin settings UI instead of being invisible
-- code constants. See tenant/V280__resolution_event.sql for the table this
-- prices.
--
-- VERSION NUMBER: shared Flyway stream with tenant/ - V280 is the tenant
-- half; V281 is the next free slot on both sides (see V280's header).

-- Minor units (cents for USD, paise for INR) charged per billable
-- RESOLVED_AI event. 0 = metering-only posture: events are recorded and
-- reported, nothing is priced - the honest default until an outcome-priced
-- plan actually exists to charge it.
INSERT INTO control_plane.platform_setting (key, value, description, updated_at) VALUES
    ('platform.resolution.unit-price-minor', '0',
     'Minor units charged per billable RESOLVED_AI resolution event (0 = meter and report only, charge nothing)',
     now()),
    ('platform.resolution.currency', 'USD',
     'ISO 4217 currency the resolution unit price is denominated in',
     now()),
    ('platform.resolution.bill-idle-close', 'true',
     'Whether NO_ESCALATION_IDLE quiet-close resolutions are billable (false = record but never charge the heuristic''s calls)',
     now()),
    ('platform.resolution.idle-close-hours', '72',
     'Hours of silence after which an agent-handled channel conversation is closed as a resolution event',
     now())
ON CONFLICT (key) DO NOTHING;
