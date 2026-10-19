package com.agenticai.core.chat.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link GovernanceDegradationSignal} exists to make a Redis blip that
 * disables every fail-open governance seam at once show up as ONE alertable
 * counter instead of scattered (or entirely silent) log lines - so this suite
 * pins the two things a dashboard/alert depends on: every fail-open event
 * lands in {@code governance_fail_open_total} tagged by component, and the
 * companion WARN is rate-limited per component (via the {@code claimWarnSlot}
 * seam - see its javadoc for why the log line itself is not asserted on).
 */
class GovernanceDegradationSignalTest {

    private SimpleMeterRegistry meterRegistry;
    private MutableClock clock;
    private GovernanceDegradationSignal signal;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        clock = new MutableClock();
        signal = new GovernanceDegradationSignal(meterRegistry, clock);
    }

    private double count(String component) {
        return meterRegistry.counter(GovernanceDegradationSignal.METRIC_NAME, "component", component).count();
    }

    @Test
    void everyFailOpenEventIsCountedTaggedByComponent() {
        signal.failOpen("tool-circuit-breaker", new RuntimeException("redis down"));
        signal.failOpen("tool-circuit-breaker", new RuntimeException("redis down"));
        signal.failOpen("llm-gateway-rate-limiter", new RuntimeException("redis down"));

        // The counter is deliberately NOT rate-limited - a sustained outage
        // must keep growing the series so alert rules can rate() over it.
        assertEquals(2.0, count("tool-circuit-breaker"));
        assertEquals(1.0, count("llm-gateway-rate-limiter"));
    }

    @Test
    void warnIsRateLimitedToOncePerComponentPerInterval() {
        // failOpen consumes the component's warn slot...
        signal.failOpen("tool-circuit-breaker", new RuntimeException("redis down"));
        // ...so within the interval the slot cannot be claimed again.
        assertFalse(signal.claimWarnSlot("tool-circuit-breaker"));

        clock.advance(GovernanceDegradationSignal.WARN_INTERVAL.plusSeconds(1));
        assertTrue(signal.claimWarnSlot("tool-circuit-breaker"));
        // And having warned again, the fresh window is consumed once more.
        assertFalse(signal.claimWarnSlot("tool-circuit-breaker"));
    }

    @Test
    void warnRateLimitIsPerComponentNotGlobal() {
        signal.failOpen("tool-circuit-breaker", new RuntimeException("redis down"));

        // A different component degrading at the same moment must still get
        // its own first WARN - one seam's noise must not hide another's.
        assertTrue(signal.claimWarnSlot("llm-gateway-rate-limiter"));
    }

    @Test
    void nullCauseIsAccepted() {
        // Some fail-open branches have no exception (e.g. Redis returning a
        // null INCR result) - the signal must not force callers to fabricate one.
        assertDoesNotThrow(() -> signal.failOpen("llm-gateway-rate-limiter", null));
        assertEquals(1.0, count("llm-gateway-rate-limiter"));
    }

    @Test
    void aFailingMeterRegistryNeverPropagatesOutOfFailOpen() {
        // The signal is invoked from paths that are ALREADY failing open - it
        // must never become the reason the underlying call breaks. A real
        // registry subclass (not a Mockito stub over Micrometer's convenience
        // methods) so the throw happens exactly where a broken backend would.
        MeterRegistry throwingRegistry = new SimpleMeterRegistry() {
            @Override
            protected io.micrometer.core.instrument.Counter newCounter(io.micrometer.core.instrument.Meter.Id id) {
                throw new IllegalStateException("registry broken");
            }
        };
        GovernanceDegradationSignal fragile = new GovernanceDegradationSignal(throwingRegistry, clock);

        assertDoesNotThrow(() -> fragile.failOpen("tool-circuit-breaker", new RuntimeException("redis down")));
    }

    /** Manually-advanced clock - the only sane way to test a wall-clock rate limit. */
    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-08-07T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
