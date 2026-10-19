package com.agenticai.core.agents.vision;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code app.vision.*} config block (see {@code
 * application.yml}): the platform-wide enable flag plus the per-user-per-day allowances {@link
 * VisionUsageService} enforces. All numbers are admin-tunable config, deliberately never copied
 * into UI strings (the shared contract's "no hardcoded UI copies" rule) - error messages
 * interpolate the live values instead.
 *
 * <p>Registered via {@link VisionConfiguration}'s {@code @EnableConfigurationProperties} - same
 * per-module pattern as {@code RazorpayPlanCatalog}/{@code RazorpayBillingConfiguration} (there is
 * no application-wide {@code @ConfigurationPropertiesScan}).
 */
@ConfigurationProperties(prefix = "app.vision")
public class VisionProperties {

    /**
     * Platform-wide master switch for server-side vision (snapshot frames AND sessions). {@code
     * true} by default per the shared contract; off maps to 503 at {@code POST
     * /api/vision/sessions} - "the platform isn't serving this right now", not a client mistake.
     */
    private boolean enabled = true;

    private final Frames frames = new Frames();
    private final Watch watch = new Watch();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Frames getFrames() {
        return frames;
    }

    public Watch getWatch() {
        return watch;
    }

    /** {@code app.vision.frames.*} - per-user-per-day camera/screen frame allowances. */
    public static class Frames {

        /**
         * FREE tier's daily server-analyzed frame allowance (its real vision tier is on-device).
         */
        private int freeDaily = 10;

        /** Paid tiers' daily server-analyzed frame allowance. */
        private int premiumDaily = 500;

        public int getFreeDaily() {
            return freeDaily;
        }

        public void setFreeDaily(int freeDaily) {
            this.freeDaily = freeDaily;
        }

        public int getPremiumDaily() {
            return premiumDaily;
        }

        public void setPremiumDaily(int premiumDaily) {
            this.premiumDaily = premiumDaily;
        }
    }

    /** {@code app.vision.watch.*} - per-user-per-day continuous-session minute allowances. */
    public static class Watch {

        /**
         * Paid tiers' daily WATCH/LIVE minutes. FREE has no configurable value on purpose:
         * continuous modes are plan-gated off for FREE via {@code plan_limits.live_vision_allowed}
         * (V180), so a FREE minute allowance would be dead config.
         */
        private int minutesPremiumDaily = 120;

        public int getMinutesPremiumDaily() {
            return minutesPremiumDaily;
        }

        public void setMinutesPremiumDaily(int minutesPremiumDaily) {
            this.minutesPremiumDaily = minutesPremiumDaily;
        }
    }
}
