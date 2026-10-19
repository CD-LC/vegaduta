package com.agenticai.core.chat.gateway;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Makes governance fail-open OBSERVABLE. Several governance seams in this
 * codebase deliberately degrade open when their Redis/lookup infrastructure
 * hiccups ({@link LlmGatewayRateLimiterService}, {@code
 * ToolCircuitBreakerService}, and siblings) - the right availability
 * trade-off per each class's own javadoc, but until now the only trace was a
 * scattered per-call log line (or nothing at all in the silent {@code catch
 * (RuntimeException ignored)} blocks). A single Redis blip disables ALL of
 * them at once, and nobody can alert on that.
 *
 * <p>Every fail-open event now lands in one Micrometer counter,
 * {@value #METRIC_NAME}, tagged by {@code component} - so "governance is
 * currently degraded" is a dashboard/alert query instead of a log grep - plus
 * a WARN rate-limited to once per component per {@link #WARN_INTERVAL}, so a
 * sustained outage stays visible in logs without a line per skipped check.
 *
 * <p>Lives in {@code core.chat} (not next to {@code ToolCircuitBreakerService}
 * in {@code core.agents}) because both wired callers must reach it without a
 * new module edge: {@code core.agents} already depends on {@code core.chat},
 * the reverse does not hold.
 *
 * <p>Never throws: this is a side-channel invoked from paths that are already
 * failing open - the signal itself must not become the reason a call breaks.
 */
@Component
public class GovernanceDegradationSignal {

    private static final Logger log = LoggerFactory.getLogger(GovernanceDegradationSignal.class);

    /** Prometheus-style name, matching the alerting rules' naming convention. */
    public static final String METRIC_NAME = "governance_fail_open_total";
    static final Duration WARN_INTERVAL = Duration.ofMinutes(1);

    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final ConcurrentHashMap<String, Long> lastWarnedAtMillis = new ConcurrentHashMap<>();

    @Autowired
    public GovernanceDegradationSignal(MeterRegistry meterRegistry) {
        this(meterRegistry, Clock.systemUTC());
    }

    /** Test seam - a mutable clock is the only sane way to test the warn rate limit. */
    GovernanceDegradationSignal(MeterRegistry meterRegistry, Clock clock) {
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * Records that {@code component} just failed open (skipped a governance
     * check it could not perform). Increments {@value #METRIC_NAME} for every
     * call; WARNs at most once per component per {@link #WARN_INTERVAL}.
     *
     * @param cause nullable - some fail-open branches have no exception (e.g.
     *     a reachable-but-odd Redis returning a null INCR result)
     */
    public void failOpen(String component, Throwable cause) {
        try {
            meterRegistry.counter(METRIC_NAME, "component", component).increment();
            if (claimWarnSlot(component)) {
                log.warn(
                    "Governance component '{}' is FAILING OPEN - its checks are being skipped, not enforced."
                        + " Further occurrences are counted in metric '{}' but only logged once per {}.",
                    component, METRIC_NAME, WARN_INTERVAL, cause
                );
            }
        } catch (RuntimeException signalFailure) {
            // See class javadoc - the signal must never break the (already
            // degraded) path that invoked it.
            log.debug("Governance degradation signal itself failed for component '{}'", component, signalFailure);
        }
    }

    /**
     * True when this call may WARN for {@code component} - i.e. no other call
     * warned for it within the last {@link #WARN_INTERVAL}. {@code compute()}
     * is atomic per key, so exactly one caller wins each window even under
     * concurrent fail-open storms (the common shape: every in-flight request
     * hits the same dead Redis at once). Package-private as the rate-limit
     * test seam - asserting on emitted log lines has no precedent in this
     * codebase's tests.
     */
    boolean claimWarnSlot(String component) {
        long now = clock.millis();
        boolean[] claimed = new boolean[1];
        lastWarnedAtMillis.compute(component, (key, lastWarnedAt) -> {
            if (lastWarnedAt == null || now - lastWarnedAt >= WARN_INTERVAL.toMillis()) {
                claimed[0] = true;
                return now;
            }
            return lastWarnedAt;
        });
        return claimed[0];
    }
}
