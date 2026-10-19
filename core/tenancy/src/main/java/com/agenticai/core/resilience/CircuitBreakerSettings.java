package com.agenticai.core.resilience;

import java.time.Duration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

/**
 * Tunables of one {@link CircuitBreaker}. Immutable; out-of-range values are
 * clamped rather than rejected, because a typo in an environment variable must
 * never stop core from booting (a breaker is a safety net, not a gate).
 *
 * <p>A breaker OPENS when EITHER trigger fires (each can be switched off with 0):
 * <ul>
 *   <li>{@code consecutiveFailureThreshold} failures in a row, or</li>
 *   <li>at least {@code minimumCalls} calls inside the sliding {@code window}
 *       (bounded to the last {@code windowSize} calls) and a failure share of
 *       {@code failureRatePercent} or more.</li>
 * </ul>
 * After {@code openDuration} it lets {@code halfOpenPermittedCalls} probe calls
 * through; all of them succeeding closes it, any failure re-opens it. A probe
 * that never reports back (thread killed, caller forgot) is reclaimed after
 * {@code halfOpenProbeTimeout} so a breaker can never stick half-open.
 */
public record CircuitBreakerSettings(
    int consecutiveFailureThreshold,
    int failureRatePercent,
    int minimumCalls,
    int windowSize,
    Duration window,
    Duration openDuration,
    int halfOpenPermittedCalls,
    Duration halfOpenProbeTimeout
) {

    public static final int MAX_WINDOW_SIZE = 500;

    public CircuitBreakerSettings {
        consecutiveFailureThreshold = Math.max(0, consecutiveFailureThreshold);
        failureRatePercent = Math.max(0, Math.min(100, failureRatePercent));
        minimumCalls = Math.max(1, minimumCalls);
        windowSize = Math.max(1, Math.min(MAX_WINDOW_SIZE, windowSize));
        window = positiveOr(window, Duration.ofSeconds(60));
        openDuration = positiveOr(openDuration, Duration.ofSeconds(30));
        halfOpenPermittedCalls = Math.max(1, halfOpenPermittedCalls);
        halfOpenProbeTimeout = positiveOr(halfOpenProbeTimeout, Duration.ofSeconds(30));
    }

    /** Conservative defaults: 5 consecutive failures or >=50% of >=10 calls in 60s; open 30s; 2 probes. */
    public static CircuitBreakerSettings defaults() {
        return new CircuitBreakerSettings(
            5, 50, 10, 20, Duration.ofSeconds(60), Duration.ofSeconds(30), 2, Duration.ofSeconds(30)
        );
    }

    /**
     * Per-dependency overrides: every key under {@code prefix} that is present replaces the matching
     * value of {@code base}; absent keys inherit. Keys: consecutive-failure-threshold,
     * failure-rate-percent, minimum-calls, window-size, window, open-duration,
     * half-open-permitted-calls, half-open-probe-timeout.
     */
    public static CircuitBreakerSettings bind(Binder binder, String prefix, CircuitBreakerSettings base) {
        return new CircuitBreakerSettings(
            binder.bind(prefix + ".consecutive-failure-threshold", Bindable.of(Integer.class))
                .orElse(base.consecutiveFailureThreshold()),
            binder.bind(prefix + ".failure-rate-percent", Bindable.of(Integer.class))
                .orElse(base.failureRatePercent()),
            binder.bind(prefix + ".minimum-calls", Bindable.of(Integer.class)).orElse(base.minimumCalls()),
            binder.bind(prefix + ".window-size", Bindable.of(Integer.class)).orElse(base.windowSize()),
            binder.bind(prefix + ".window", Bindable.of(Duration.class)).orElse(base.window()),
            binder.bind(prefix + ".open-duration", Bindable.of(Duration.class)).orElse(base.openDuration()),
            binder.bind(prefix + ".half-open-permitted-calls", Bindable.of(Integer.class))
                .orElse(base.halfOpenPermittedCalls()),
            binder.bind(prefix + ".half-open-probe-timeout", Bindable.of(Duration.class))
                .orElse(base.halfOpenProbeTimeout())
        );
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
