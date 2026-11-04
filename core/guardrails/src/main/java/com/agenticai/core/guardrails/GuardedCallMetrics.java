package com.agenticai.core.guardrails;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Best-effort metrics for {@link GuardedToolCallback}; a {@code null} registry records nothing. */
final class GuardedCallMetrics {

    private static final Logger log = LoggerFactory.getLogger(GuardedCallMetrics.class);

    private final MeterRegistry meterRegistry;

    GuardedCallMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    void readOnlyAutoAllowed() {
        if (meterRegistry != null) {
            try {
                meterRegistry.counter(ReadOnlyAutoAllow.METRIC).increment();
            } catch (RuntimeException e) {
                log.debug("Could not record {}", ReadOnlyAutoAllow.METRIC, e);
            }
        }
    }

    void recordTiming(String toolName, String outcome, long elapsedNanos) {
        if (meterRegistry == null) {
            return;
        }
        try {
            Timer.builder(GuardedToolCallback.CALL_TIMER)
                .description("Agent tool calls through the guardrail wrapper, by tool and outcome")
                .tag("tool", toolName)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            log.debug("Could not record {} for tool '{}'", GuardedToolCallback.CALL_TIMER, toolName, e);
        }
    }

    void recordError(String toolName, RuntimeException error) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(
                    GuardedToolCallback.ERROR_COUNTER, "tool", toolName, "exception", error.getClass().getSimpleName())
                .increment();
        } catch (RuntimeException e) {
            log.debug("Could not record {} for tool '{}'", GuardedToolCallback.ERROR_COUNTER, toolName, e);
        }
    }
}
