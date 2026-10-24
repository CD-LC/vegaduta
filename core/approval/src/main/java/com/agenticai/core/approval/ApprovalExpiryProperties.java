package com.agenticai.core.approval;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.approvals.expiry.*}: how long a request may wait for a human before it expires.
 *
 * <p>Until this setting existed a PENDING request waited forever: the agent that asked had ended its
 * turn, the person never came, and the row stayed approvable indefinitely - so approving it weeks
 * later replayed a stale, possibly non-idempotent action. With a TTL set, {@link ApprovalExpiryJob}
 * closes requests older than it: the request is decided as REJECTED by {@link
 * ApprovalRequest#EXPIRY_ACTOR} with {@link #getExpiredReason()} as the reason (no new
 * {@link ApprovalStatus} value, so the API, SDK and UI contracts are unchanged; {@link
 * ApprovalRequest#isExpired()} tells the two kinds of rejection apart), the audit trail records {@code
 * EXPIRED}, a late approve is refused as already decided, and whatever was waiting on the decision (a
 * parked agent job, a halted workflow node) is told the request was rejected with that reason.
 *
 * <p>The default is OFF ({@link #getTtl()} zero): behaviour is unchanged until an operator opts in.
 * Registered by {@link ApprovalExpiryConfiguration}.
 */
@ConfigurationProperties(prefix = "app.approvals.expiry")
public class ApprovalExpiryProperties {

    private static final String DEFAULTS_RESOURCE = "/approval/approval-expiry.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /**
     * How long a request may stay PENDING, measured from its creation. {@code 0} (the default) or
     * negative: requests never expire. A job parked on an approval has its own, separate park timeout
     * ({@code app.agent-jobs.approval.park-timeout}); keep this at least as long as that one if the
     * job should be the one to time out.
     */
    private Duration ttl = Duration.ZERO;

    /** How often the sweep looks for expired requests. */
    private Duration sweepInterval = Duration.ofMinutes(5);

    /** How long after startup the first sweep runs. */
    private Duration initialDelay = Duration.ofMinutes(1);

    /** The most requests one sweep expires per tenant; the rest wait for the next tick. */
    private int batchSize = 200;

    /** Reason recorded on an expired request; {@code {ttl}} is filled in. Model-facing. */
    private String expiredReason = DEFAULTS.getProperty("expired-reason");

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = ApprovalExpiryProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the approval module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    /** Whether requests expire at all. */
    public boolean isEnabled() {
        return ttl != null && !ttl.isZero() && !ttl.isNegative();
    }

    /** {@link #getExpiredReason()} with {@code {ttl}} replaced by a readable duration. */
    public String reason() {
        return reason(ttl);
    }

    /** {@link #getExpiredReason()} for a TTL other than the platform's (a workspace's own, from the settings catalog). */
    public String reason(Duration forTtl) {
        return expiredReason.replace("{ttl}", describe(forTtl));
    }

    /** {@code PT90M} as "90 minutes", {@code PT24H} as "24 hours", {@code P3D} as "3 days" (whole days only from 3). */
    static String describe(Duration duration) {
        if (duration == null) {
            return "the allowed time";
        }
        long seconds = duration.getSeconds();
        if (seconds >= 3 * 86_400 && seconds % 86_400 == 0) {
            return unit(seconds / 86_400, "day");
        }
        if (seconds > 0 && seconds % 3_600 == 0) {
            return unit(seconds / 3_600, "hour");
        }
        if (seconds > 0 && seconds % 60 == 0) {
            return unit(seconds / 60, "minute");
        }
        return unit(seconds, "second");
    }

    private static String unit(long amount, String name) {
        return amount + " " + name + (amount == 1 ? "" : "s");
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl == null ? Duration.ZERO : ttl;
    }

    public Duration getSweepInterval() {
        return sweepInterval;
    }

    public void setSweepInterval(Duration sweepInterval) {
        if (sweepInterval != null && !sweepInterval.isZero() && !sweepInterval.isNegative()) {
            this.sweepInterval = sweepInterval;
        }
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        if (initialDelay != null && !initialDelay.isNegative()) {
            this.initialDelay = initialDelay;
        }
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize > 0) {
            this.batchSize = batchSize;
        }
    }

    public String getExpiredReason() {
        return expiredReason;
    }

    public void setExpiredReason(String expiredReason) {
        if (expiredReason != null && !expiredReason.isBlank()) {
            this.expiredReason = expiredReason;
        }
    }
}
