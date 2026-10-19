-- Phase P5 (Fraud & Anomaly) - seed the admin-editable knobs the abuse/anomaly
-- detector reads via PlatformSettingsService's typed getters (see AbuseConfig).
-- No new table: this reuses the generic control_plane.platform_setting KV
-- surface established by V91 (same pattern as the signup rate-limit rows), so
-- these values show up in Platform Admin -> Settings and can be tuned at
-- runtime with no redeploy. Risk scores, recent anomalies and throttles are
-- served from computed signals (llm_call_log / audit_log_entry), fingerprinted
-- ABUSE_ANOMALY platform_issue rows, and an in-memory TTL throttle set
-- respectively - none of which need their own table (governance doc §5/§7:
-- "prefer Redis counters + audit entries over a table").
--
-- Values below are identical to AbuseConfig's hardcoded defaults (the real
-- fallback if a row is ever absent) - this migration changes WHERE the value
-- lives (admin-editable DB row) not WHAT it is, the same "minimum required
-- change" standard V91 followed.
--
-- V120 per the shared tenant+controlplane migration sequence (both folders are
-- scanned into one combined ordering - see application.yml flyway.locations and
-- V91's own renumbering note): next free global number reserved for this phase.
INSERT INTO control_plane.platform_setting (key, value, description) VALUES
    ('abuse.window.recent-minutes', '10', 'Fraud/anomaly: recent activity window (minutes) burst signals are measured over.'),
    ('abuse.window.baseline-minutes', '1440', 'Fraud/anomaly: baseline window (minutes) recent spend is compared against for the spend-spike ratio.'),
    ('abuse.spend-spike.multiplier', '4.0', 'Fraud/anomaly: how many times the baseline-rate spend in the recent window scores as a maximal spend spike.'),
    ('abuse.spend-spike.min-usd', '0.05', 'Fraud/anomaly: minimum recent spend (USD) before the spend-spike signal scores at all (suppresses noise on near-idle tenants).'),
    ('abuse.spend-spike.weight', '0.30', 'Fraud/anomaly: relative weight of the spend-spike signal in the aggregate risk score.'),
    ('abuse.token-burn.per-minute-threshold', '200000', 'Fraud/anomaly: tokens/minute that scores as maximal token-burn.'),
    ('abuse.token-burn.weight', '0.25', 'Fraud/anomaly: relative weight of the token-burn signal.'),
    ('abuse.message-flood.per-minute-threshold', '60', 'Fraud/anomaly: audited actions/minute by one user that scores as maximal message/action flooding.'),
    ('abuse.message-flood.weight', '0.20', 'Fraud/anomaly: relative weight of the message-flood signal.'),
    ('abuse.signup-velocity.per-hour-threshold', '20', 'Fraud/anomaly: signup-type actions/hour by one actor that scores as maximal signup velocity.'),
    ('abuse.signup-velocity.weight', '0.10', 'Fraud/anomaly: relative weight of the signup-velocity signal.'),
    ('abuse.tool-error.ratio-threshold', '0.5', 'Fraud/anomaly: failure/total action ratio (0-1) that scores as maximal tool-error abuse.'),
    ('abuse.tool-error.min-sample', '5', 'Fraud/anomaly: minimum actions in the window before the tool-error ratio is scored (avoids 1/1 = 100%).'),
    ('abuse.tool-error.weight', '0.15', 'Fraud/anomaly: relative weight of the tool-error-abuse signal.'),
    ('abuse.geo-velocity.weight', '0.20', 'Fraud/anomaly: relative weight of the geo-velocity (impossible-travel) signal; only applies when a GeoVelocityProvider is wired.'),
    ('abuse.tier.watch-threshold', '40', 'Fraud/anomaly: aggregate score (0-100) at/above which a principal enters WATCH (record issue only).'),
    ('abuse.tier.throttle-threshold', '65', 'Fraud/anomaly: aggregate score at/above which a principal is THROTTLED (reversible, TTL-bounded).'),
    ('abuse.tier.suspend-threshold', '85', 'Fraud/anomaly: aggregate score at/above which SUSPEND tier fires (throttle + ERROR issue; tenant emergency-stop only if abuse.auto-suspend.enabled).'),
    ('abuse.throttle.ttl-minutes', '60', 'Fraud/anomaly: how long (minutes) an automatic throttle lasts before self-expiring.'),
    ('abuse.auto-suspend.enabled', 'false', 'Fraud/anomaly: when true, SUSPEND tier auto emergency-stops the whole tenant; when false (default, safe) it throttles + raises an ERROR issue for operator review.'),
    ('abuse.scan.enabled', 'false', 'Fraud/anomaly: master switch for the background scoring sweep. Off by default; on-demand scoring via the admin API works regardless.')
ON CONFLICT (key) DO NOTHING;
