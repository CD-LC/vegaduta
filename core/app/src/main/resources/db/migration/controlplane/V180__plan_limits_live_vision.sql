-- Program S (Live Vision / "Sight") - plan-gated entitlement for the
-- continuous camera/screen modes (WATCH/LIVE sessions, see
-- docs/LIVE-VISION-SENSE-RESEARCH-2026-08-05.md section 4.4): one boolean on
-- the data-driven plan_limits table, same shape as byok_allowed/
-- whatsapp_allowed/white_label in V101__plan_limits.sql. Read through
-- PlanLimitsService -> TenantLimits#liveVisionAllowed and enforced by
-- core/agents' VisionSessionService when a WATCH or LIVE vision session is
-- started (SNAPSHOT - one user-gesture frame per turn - is deliberately NOT
-- gated by this column; it rides the per-user frame quota only).
--
-- FREE seeds FALSE (the FREE tier's continuous-vision story is edge-only,
-- frames analyzed on-device - Program S-edge); every non-FREE plan seeds
-- TRUE, matching the research plan's "PREMIUM+, minute-metered" ladder.
-- Runtime-editable afterwards via the web-admin plan-limits editor
-- (PUT /api/platform/plan-limits/{plan}), same as every other column here.
ALTER TABLE control_plane.plan_limits
    ADD COLUMN live_vision_allowed BOOLEAN NOT NULL DEFAULT FALSE;

-- Plan key values are the TenantPlan enum names - exactly FREE/PREMIUM/
-- UNLIMITED, one row each (seeded by V101, renamed by V57's predecessor
-- history). "everything except FREE" rather than an explicit IN-list so a
-- future fourth paid tier added by a later seed inherits the paid default
-- only if its own migration says so - this UPDATE touches only rows that
-- exist today.
UPDATE control_plane.plan_limits SET live_vision_allowed = TRUE WHERE plan <> 'FREE';
