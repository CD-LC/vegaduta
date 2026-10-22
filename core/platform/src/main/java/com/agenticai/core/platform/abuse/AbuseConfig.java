package com.agenticai.core.platform.abuse;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.PlatformSettingsService;
import org.springframework.stereotype.Component;

/**
 * Phase P5 (Fraud & Anomaly) - resolves the {@link AbuseThresholds} snapshot
 * from admin-editable {@code control_plane.platform_setting} rows, using the
 * exact typed-getter pattern the rest of the platform uses (see {@link
 * PlatformSettingsService} - {@code getInt}/{@code getLong}/{@code getDouble}/
 * {@code getBoolean}, each with a hardcoded default used only when the row is
 * missing or unparseable). NOT {@code @Value}-injected: every value is
 * read fresh so an edit in Platform Admin -&gt; Settings takes effect on the
 * very next scoring pass, no redeploy (migration V120 seeds the rows so they
 * show up in that UI from day one, but the defaults here are the real source
 * of truth if a row is ever absent).
 *
 * <p>The default values below are intentionally conservative (a real spend
 * spike is a multiple of baseline; flood/burn thresholds sit well above normal
 * interactive use) so the detector does not false-positive legitimate power
 * users. They are the same numbers seeded by V120.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Component
public class AbuseConfig {

    // Key prefix mirrors the "signup.rate-limit.*" convention already in platform_setting.
    static final String K_RECENT_WINDOW_MIN = "abuse.window.recent-minutes";
    static final String K_BASELINE_WINDOW_MIN = "abuse.window.baseline-minutes";
    static final String K_SPEND_MULT = "abuse.spend-spike.multiplier";
    static final String K_SPEND_MIN_USD = "abuse.spend-spike.min-usd";
    static final String K_SPEND_WEIGHT = "abuse.spend-spike.weight";
    static final String K_TOKEN_BURN_THRESH = "abuse.token-burn.per-minute-threshold";
    static final String K_TOKEN_BURN_WEIGHT = "abuse.token-burn.weight";
    static final String K_FLOOD_THRESH = "abuse.message-flood.per-minute-threshold";
    static final String K_FLOOD_WEIGHT = "abuse.message-flood.weight";
    static final String K_SIGNUP_THRESH = "abuse.signup-velocity.per-hour-threshold";
    static final String K_SIGNUP_WEIGHT = "abuse.signup-velocity.weight";
    static final String K_TOOLERR_RATIO = "abuse.tool-error.ratio-threshold";
    static final String K_TOOLERR_MINSAMPLE = "abuse.tool-error.min-sample";
    static final String K_TOOLERR_WEIGHT = "abuse.tool-error.weight";
    static final String K_GEO_WEIGHT = "abuse.geo-velocity.weight";
    static final String K_WATCH = "abuse.tier.watch-threshold";
    static final String K_THROTTLE = "abuse.tier.throttle-threshold";
    static final String K_SUSPEND = "abuse.tier.suspend-threshold";
    static final String K_THROTTLE_TTL_MIN = "abuse.throttle.ttl-minutes";
    static final String K_AUTO_SUSPEND = "abuse.auto-suspend.enabled";
    static final String K_SCAN_ENABLED = "abuse.scan.enabled";

    private final PlatformSettingsService settings;

    public AbuseConfig(PlatformSettingsService settings) {
        this.settings = settings;
    }

    /** Read every knob fresh into one immutable snapshot for a single scoring pass. */
    public AbuseThresholds current() {
        return new AbuseThresholds(
            settings.getLong(K_RECENT_WINDOW_MIN, 10),
            settings.getLong(K_BASELINE_WINDOW_MIN, 1440),
            settings.getDouble(K_SPEND_MULT, 4.0),
            settings.getDouble(K_SPEND_MIN_USD, 0.05),
            settings.getDouble(K_SPEND_WEIGHT, 0.30),
            settings.getDouble(K_TOKEN_BURN_THRESH, 200_000),
            settings.getDouble(K_TOKEN_BURN_WEIGHT, 0.25),
            settings.getDouble(K_FLOOD_THRESH, 60),
            settings.getDouble(K_FLOOD_WEIGHT, 0.20),
            settings.getDouble(K_SIGNUP_THRESH, 20),
            settings.getDouble(K_SIGNUP_WEIGHT, 0.10),
            settings.getDouble(K_TOOLERR_RATIO, 0.5),
            settings.getLong(K_TOOLERR_MINSAMPLE, 5),
            settings.getDouble(K_TOOLERR_WEIGHT, 0.15),
            settings.getDouble(K_GEO_WEIGHT, 0.20),
            settings.getDouble(K_WATCH, 40),
            settings.getDouble(K_THROTTLE, 65),
            settings.getDouble(K_SUSPEND, 85),
            settings.getLong(K_THROTTLE_TTL_MIN, 60),
            settings.getBoolean(K_AUTO_SUSPEND, false),
            settings.getBoolean(K_SCAN_ENABLED, false)
        );
    }
}
