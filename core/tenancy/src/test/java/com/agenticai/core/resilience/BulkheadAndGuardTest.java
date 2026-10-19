package com.agenticai.core.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BulkheadAndGuardTest {

    private final ManualClock clock = new ManualClock();

    @Test
    void bulkheadShedsBeyondTheCapAndReleasesOnClose() {
        Bulkhead bulkhead = new Bulkhead("b", 2, Duration.ZERO, null);
        Bulkhead.Ticket first = bulkhead.tryAcquire();
        Bulkhead.Ticket second = bulkhead.tryAcquire();
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(bulkhead.available()).isZero();
        assertThat(bulkhead.tryAcquire()).isNull();
        first.close();
        first.close(); // idempotent: must not hand out a phantom slot
        assertThat(bulkhead.available()).isEqualTo(1);
        assertThat(bulkhead.tryAcquire()).isNotNull();
        assertThat(bulkhead.tryAcquire()).isNull();
    }

    @Test
    void bulkheadExportsMetrics() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Bulkhead bulkhead = new Bulkhead("dep", 1, Duration.ZERO, meters);
        bulkhead.tryAcquire();
        assertThat(bulkhead.tryAcquire()).isNull();
        assertThat(meters.get("resilience.bulkhead.rejected").tag("name", "dep").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("resilience.bulkhead.available").tag("name", "dep").gauge().value()).isZero();
    }

    @Test
    void noopGuardAlwaysAdmitsAndRecordsNothing() {
        assertThat(ResilienceGuard.NOOP.active()).isFalse();
        for (int i = 0; i < 1000; i++) {
            try (ResilienceGuard.Call call = ResilienceGuard.NOOP.begin()) {
                call.failure(new IOException());
            }
        }
    }

    @Test
    void guardShedsWithCircuitOpenOnceTheBreakerOpens() {
        CircuitBreaker breaker = new CircuitBreaker("g", new CircuitBreakerSettings(
            2, 0, 10, 10, Duration.ofSeconds(60), Duration.ofSeconds(30), 1, Duration.ofSeconds(30)),
            clock, null, null, null);
        ResilienceGuard guard = new ResilienceGuard(breaker, null);
        for (int i = 0; i < 2; i++) {
            try (ResilienceGuard.Call call = guard.begin()) {
                call.failure(new IOException());
            }
        }
        assertThatThrownBy(guard::begin)
            .isInstanceOf(DependencyUnavailableException.class)
            .satisfies(e -> assertThat(((DependencyUnavailableException) e).reason())
                .isEqualTo(DependencyUnavailableException.Reason.CIRCUIT_OPEN));
    }

    @Test
    void closingAnUnsettledCallIsNeutral() {
        CircuitBreaker breaker = new CircuitBreaker("g", new CircuitBreakerSettings(
            1, 0, 10, 10, Duration.ofSeconds(60), Duration.ofSeconds(30), 1, Duration.ofSeconds(30)),
            clock, null, null, null);
        ResilienceGuard guard = new ResilienceGuard(breaker, null);
        for (int i = 0; i < 20; i++) {
            try (ResilienceGuard.Call ignored = guard.begin()) {
                // caller returned without settling (e.g. an early return): counts as nothing
            }
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void bulkheadFullShedsAndReleasesTheHalfOpenProbeSlot() {
        CircuitBreaker breaker = new CircuitBreaker("g", new CircuitBreakerSettings(
            1, 0, 10, 10, Duration.ofSeconds(60), Duration.ofSeconds(30), 1, Duration.ofSeconds(30)),
            clock, null, null, null);
        Bulkhead bulkhead = new Bulkhead("g", 1, Duration.ZERO, null);
        ResilienceGuard guard = new ResilienceGuard(breaker, bulkhead);

        try (ResilienceGuard.Call call = guard.begin()) {
            call.failure(new IOException()); // opens (threshold 1)
        }
        clock.advance(Duration.ofSeconds(30));

        Bulkhead.Ticket hog = bulkhead.tryAcquire(); // fill the bulkhead from outside
        assertThat(hog).isNotNull();
        assertThatThrownBy(guard::begin)
            .isInstanceOf(DependencyUnavailableException.class)
            .satisfies(e -> assertThat(((DependencyUnavailableException) e).reason())
                .isEqualTo(DependencyUnavailableException.Reason.BULKHEAD_FULL));
        hog.close();

        // The shed call must not have burned the single half-open probe slot.
        try (ResilienceGuard.Call call = guard.begin()) {
            call.success();
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void closingACallReleasesItsBulkheadSlot() {
        Bulkhead bulkhead = new Bulkhead("g", 1, Duration.ZERO, null);
        ResilienceGuard guard = new ResilienceGuard(null, bulkhead);
        try (ResilienceGuard.Call call = guard.begin()) {
            call.success();
            assertThat(bulkhead.available()).isZero();
        }
        assertThat(bulkhead.available()).isEqualTo(1);
    }
}
