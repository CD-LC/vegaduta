package com.agenticai.core.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.resilience.CircuitBreaker.Permit;
import com.agenticai.core.resilience.CircuitBreaker.State;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Exhaustive state-machine tests; time only moves through {@link ManualClock}. */
class CircuitBreakerTest {

    private static final Duration OPEN_FOR = Duration.ofSeconds(30);

    private final ManualClock clock = new ManualClock();

    private static CircuitBreakerSettings settings(
        int consecutive, int ratePercent, int minCalls, int windowSize, int probes
    ) {
        return new CircuitBreakerSettings(
            consecutive, ratePercent, minCalls, windowSize, Duration.ofSeconds(60), OPEN_FOR, probes,
            Duration.ofSeconds(30));
    }

    private CircuitBreaker breaker(CircuitBreakerSettings s) {
        return new CircuitBreaker("t", s, clock, null, null, null);
    }

    private static void fail(CircuitBreaker b) {
        Permit p = b.tryAcquire();
        assertThat(p.granted()).isTrue();
        p.failure(new IOException("boom"));
    }

    private static void succeed(CircuitBreaker b) {
        Permit p = b.tryAcquire();
        assertThat(p.granted()).isTrue();
        p.success();
    }

    private CircuitBreaker openBreaker(CircuitBreakerSettings s) {
        CircuitBreaker b = breaker(s);
        for (int i = 0; i < s.consecutiveFailureThreshold(); i++) {
            fail(b);
        }
        assertThat(b.state()).isEqualTo(State.OPEN);
        return b;
    }

    // ---------------------------------------------------------------- closed

    @Test
    void startsClosedAndGrantsEverything() {
        CircuitBreaker b = breaker(settings(3, 0, 10, 10, 1));
        assertThat(b.state()).isEqualTo(State.CLOSED);
        for (int i = 0; i < 100; i++) {
            assertThat(b.tryAcquire().granted()).isTrue();
        }
    }

    @Test
    void opensOnExactlyTheConsecutiveFailureThreshold() {
        CircuitBreaker b = breaker(settings(3, 0, 10, 10, 1));
        fail(b);
        fail(b);
        assertThat(b.state()).isEqualTo(State.CLOSED);
        assertThat(b.consecutiveFailures()).isEqualTo(2);
        fail(b);
        assertThat(b.state()).isEqualTo(State.OPEN);
    }

    @Test
    void aSuccessResetsTheConsecutiveStreak() {
        CircuitBreaker b = breaker(settings(3, 0, 10, 10, 1));
        fail(b);
        fail(b);
        succeed(b);
        fail(b);
        fail(b);
        assertThat(b.state()).isEqualTo(State.CLOSED);
        fail(b);
        assertThat(b.state()).isEqualTo(State.OPEN);
    }

    @Test
    void failureRateTripsOnlyOnceMinimumCallsAreReached() {
        CircuitBreaker b = breaker(settings(0, 50, 4, 10, 1));
        fail(b);
        fail(b);
        fail(b); // 3 of 3 failed but only 3 calls < minimumCalls 4
        assertThat(b.state()).isEqualTo(State.CLOSED);
        fail(b); // 4 calls, 100% failed
        assertThat(b.state()).isEqualTo(State.OPEN);
    }

    @Test
    void failureRateBoundaryIsInclusive() {
        CircuitBreaker b = breaker(settings(0, 50, 4, 10, 1));
        succeed(b);
        succeed(b);
        fail(b);
        assertThat(b.state()).isEqualTo(State.CLOSED); // 1/3, below minimum anyway
        fail(b); // 2/4 = exactly 50% -> opens
        assertThat(b.state()).isEqualTo(State.OPEN);
    }

    @Test
    void failureRateJustBelowThresholdStaysClosed() {
        CircuitBreaker b = breaker(settings(0, 50, 4, 10, 1));
        succeed(b);
        succeed(b);
        succeed(b);
        fail(b); // 1/4 = 25%
        assertThat(b.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void callsOlderThanTheWindowDoNotCountTowardTheRate() {
        CircuitBreaker b = breaker(settings(0, 50, 3, 10, 1));
        fail(b);
        fail(b);
        clock.advance(Duration.ofSeconds(61)); // both fall out of the 60s window
        fail(b); // window now holds 1 call < minimumCalls 3
        assertThat(b.state()).isEqualTo(State.CLOSED);
        fail(b);
        fail(b); // 3 fresh failures
        assertThat(b.state()).isEqualTo(State.OPEN);
    }

    @Test
    void windowEdgeIsInclusive() {
        CircuitBreaker b = breaker(settings(0, 50, 3, 10, 1));
        fail(b);
        fail(b);
        clock.advance(Duration.ofSeconds(60)); // exactly the window: still counted
        fail(b);
        assertThat(b.state()).isEqualTo(State.OPEN);
    }

    @Test
    void ringOverwritesTheOldestEntryWhenFull() {
        // window of 4 calls: three old failures are pushed out by four successes
        CircuitBreaker b = breaker(settings(0, 50, 4, 4, 1));
        fail(b);
        fail(b);
        succeed(b);
        succeed(b); // 2/4 -> only evaluated on failure, so still closed
        assertThat(b.state()).isEqualTo(State.CLOSED);
        succeed(b);
        succeed(b); // ring now holds S,S,S,S (both failures overwritten)
        fail(b); // 1/4 = 25%
        assertThat(b.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void disabledTriggersNeverOpen() {
        CircuitBreaker b = breaker(settings(0, 0, 1, 10, 1));
        for (int i = 0; i < 50; i++) {
            fail(b);
        }
        assertThat(b.state()).isEqualTo(State.CLOSED);
    }

    // ------------------------------------------------------------------ open

    @Test
    void openRejectsWithoutCallingAndCountsNothing() {
        CircuitBreaker b = openBreaker(settings(2, 0, 10, 10, 1));
        Permit p = b.tryAcquire();
        assertThat(p.granted()).isFalse();
        p.failure(new IOException()); // inert
        p.success();
        assertThat(b.state()).isEqualTo(State.OPEN);
    }

    @Test
    void staysOpenUntilTheOpenDurationElapsesToTheMillisecond() {
        CircuitBreaker b = openBreaker(settings(2, 0, 10, 10, 1));
        clock.advanceMillis(OPEN_FOR.toMillis() - 1);
        assertThat(b.tryAcquire().granted()).isFalse();
        assertThat(b.state()).isEqualTo(State.OPEN);
        assertThat(b.retryAfterMillis()).isEqualTo(1L);
        clock.advanceMillis(1);
        Permit probe = b.tryAcquire();
        assertThat(probe.granted()).isTrue();
        assertThat(b.state()).isEqualTo(State.HALF_OPEN);
    }

    @Test
    void retryAfterIsZeroWhenNotOpen() {
        CircuitBreaker b = breaker(settings(2, 0, 10, 10, 1));
        assertThat(b.retryAfterMillis()).isZero();
    }

    @Test
    void failuresReportedWhileOpenAreDroppedAsStale() {
        CircuitBreaker b = breaker(settings(2, 0, 10, 10, 1));
        Permit slow1 = b.tryAcquire();
        Permit slow2 = b.tryAcquire();
        fail(b);
        fail(b); // opens
        assertThat(b.state()).isEqualTo(State.OPEN);
        slow1.failure(new IOException());
        slow2.success();
        assertThat(b.state()).isEqualTo(State.OPEN);
        clock.advance(OPEN_FOR);
        Permit probe = b.tryAcquire();
        assertThat(b.state()).isEqualTo(State.HALF_OPEN);
        probe.success();
        assertThat(b.state()).isEqualTo(State.CLOSED);
    }

    // ------------------------------------------------------------- half-open

    @Test
    void halfOpenAdmitsExactlyTheConfiguredProbes() {
        CircuitBreaker b = openBreaker(settings(2, 0, 10, 10, 3));
        clock.advance(OPEN_FOR);
        assertThat(b.tryAcquire().granted()).isTrue();
        assertThat(b.tryAcquire().granted()).isTrue();
        assertThat(b.tryAcquire().granted()).isTrue();
        assertThat(b.tryAcquire().granted()).isFalse();
        assertThat(b.state()).isEqualTo(State.HALF_OPEN);
    }

    @Test
    void allProbesSucceedingClosesTheBreakerWithAFreshHistory() {
        CircuitBreaker b = openBreaker(settings(2, 0, 10, 10, 2));
        clock.advance(OPEN_FOR);
        Permit p1 = b.tryAcquire();
        Permit p2 = b.tryAcquire();
        p1.success();
        assertThat(b.state()).isEqualTo(State.HALF_OPEN); // one of two
        p2.success();
        assertThat(b.state()).isEqualTo(State.CLOSED);
        fail(b);
        assertThat(b.state()).isEqualTo(State.CLOSED); // streak restarted from zero (threshold 2)
        fail(b);
        assertThat(b.state()).isEqualTo(State.OPEN);
    }

    @Test
    void aSingleProbeFailureReopensWithAFreshOpenTimer() {
        CircuitBreaker b = openBreaker(settings(2, 0, 10, 10, 2));
        clock.advance(OPEN_FOR);
        Permit p1 = b.tryAcquire();
        Permit p2 = b.tryAcquire();
        p1.success();
        p2.failure(new IOException());
        assertThat(b.state()).isEqualTo(State.OPEN);
        assertThat(b.tryAcquire().granted()).isFalse();
        clock.advanceMillis(OPEN_FOR.toMillis() - 1);
        assertThat(b.tryAcquire().granted()).isFalse(); // timer restarted at the re-open
        clock.advanceMillis(1);
        assertThat(b.tryAcquire().granted()).isTrue();
    }

    @Test
    void ignoringAProbeFreesItsSlotWithoutCountingAnything() {
        CircuitBreaker b = openBreaker(settings(2, 0, 10, 10, 1));
        clock.advance(OPEN_FOR);
        Permit p1 = b.tryAcquire();
        assertThat(b.tryAcquire().granted()).isFalse();
        p1.ignore();
        assertThat(b.state()).isEqualTo(State.HALF_OPEN);
        Permit p2 = b.tryAcquire();
        assertThat(p2.granted()).isTrue();
        p2.success();
        assertThat(b.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void aProbeThatNeverReportsIsReclaimedAfterTheProbeTimeout() {
        CircuitBreaker b = openBreaker(settings(2, 0, 10, 10, 1));
        clock.advance(OPEN_FOR);
        Permit leaked = b.tryAcquire();
        assertThat(leaked.granted()).isTrue();
        assertThat(b.tryAcquire().granted()).isFalse();
        clock.advance(Duration.ofSeconds(29));
        assertThat(b.tryAcquire().granted()).isFalse();
        clock.advance(Duration.ofSeconds(1)); // probe timeout (30s) reached
        Permit fresh = b.tryAcquire();
        assertThat(fresh.granted()).isTrue();
        leaked.failure(new IOException()); // late report from the leaked probe: dropped
        assertThat(b.state()).isEqualTo(State.HALF_OPEN);
        fresh.success();
        assertThat(b.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void aClosedEraPermitFinishingDuringHalfOpenIsNotCountedAsAProbe() {
        CircuitBreaker b = breaker(settings(2, 0, 10, 10, 1));
        Permit longRunning = b.tryAcquire();
        fail(b);
        fail(b);
        clock.advance(OPEN_FOR);
        Permit probe = b.tryAcquire();
        assertThat(b.state()).isEqualTo(State.HALF_OPEN);
        longRunning.success(); // must NOT close the breaker
        assertThat(b.state()).isEqualTo(State.HALF_OPEN);
        probe.failure(new IOException());
        assertThat(b.state()).isEqualTo(State.OPEN);
    }

    // ---------------------------------------------------------- misc / API

    @Test
    void aPermitSettlesOnlyOnce() {
        CircuitBreaker b = breaker(settings(2, 0, 10, 10, 1));
        Permit p = b.tryAcquire();
        p.failure(new IOException());
        p.failure(new IOException());
        p.failure(new IOException());
        assertThat(b.state()).isEqualTo(State.CLOSED);
        assertThat(b.consecutiveFailures()).isEqualTo(1);
    }

    @Test
    void failuresTheCallerDeclaresNotTheDependenciesFaultCountAsSuccess() {
        CircuitBreaker b = new CircuitBreaker(
            "t", settings(2, 0, 10, 10, 1), clock, null, e -> e instanceof IOException, null);
        for (int i = 0; i < 10; i++) {
            Permit p = b.tryAcquire();
            p.failure(new IllegalArgumentException("tenant's own bad input"));
        }
        assertThat(b.state()).isEqualTo(State.CLOSED);
        fail(b);
        fail(b);
        assertThat(b.state()).isEqualTo(State.OPEN);
    }

    @Test
    void aThrowingPredicateIsTreatedAsARealFailure() {
        CircuitBreaker b = new CircuitBreaker(
            "t", settings(1, 0, 10, 10, 1), clock, null, e -> { throw new IllegalStateException(); }, null);
        b.tryAcquire().failure(new IOException());
        assertThat(b.state()).isEqualTo(State.OPEN);
    }

    @Test
    void executeRunsTheCallAndReportsOutcomes() {
        CircuitBreaker b = breaker(settings(2, 0, 10, 10, 1));
        assertThat(b.execute(() -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> b.execute(() -> { throw new IllegalStateException("x"); }))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> b.execute(() -> { throw new IllegalStateException("x"); }))
            .isInstanceOf(IllegalStateException.class);
        assertThat(b.state()).isEqualTo(State.OPEN);
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> b.execute(calls::incrementAndGet))
            .isInstanceOf(DependencyUnavailableException.class)
            .satisfies(e -> assertThat(((DependencyUnavailableException) e).reason())
                .isEqualTo(DependencyUnavailableException.Reason.CIRCUIT_OPEN));
        assertThat(calls).hasValue(0); // never called while open
    }

    @Test
    void resetClosesAndForgetsHistoryAndInvalidatesOutstandingPermits() {
        CircuitBreaker b = openBreaker(settings(2, 0, 10, 10, 1));
        b.reset();
        assertThat(b.state()).isEqualTo(State.CLOSED);
        fail(b);
        assertThat(b.state()).isEqualTo(State.CLOSED);

        CircuitBreaker c = breaker(settings(2, 0, 10, 10, 1));
        Permit before = c.tryAcquire();
        c.reset();
        before.failure(new IOException());
        assertThat(c.consecutiveFailures()).isZero(); // stale, dropped
    }

    @Test
    void settingsAreClampedNotRejected() {
        CircuitBreakerSettings s = new CircuitBreakerSettings(-5, 500, 0, 100_000, null, Duration.ZERO, 0, null);
        assertThat(s.consecutiveFailureThreshold()).isZero();
        assertThat(s.failureRatePercent()).isEqualTo(100);
        assertThat(s.minimumCalls()).isEqualTo(1);
        assertThat(s.windowSize()).isEqualTo(CircuitBreakerSettings.MAX_WINDOW_SIZE);
        assertThat(s.window()).isPositive();
        assertThat(s.openDuration()).isPositive();
        assertThat(s.halfOpenPermittedCalls()).isEqualTo(1);
        assertThat(s.halfOpenProbeTimeout()).isPositive();
    }

    @Test
    void listenerSeesEveryTransitionInOrderAndABrokenListenerIsHarmless() {
        List<String> seen = new ArrayList<>();
        CircuitBreaker b = new CircuitBreaker("t", settings(1, 0, 10, 10, 1), clock, null, null, t -> {
            seen.add(t.from() + ">" + t.to());
            throw new IllegalStateException("listener bug");
        });
        fail(b);
        clock.advance(OPEN_FOR);
        b.tryAcquire().success();
        assertThat(seen).containsExactly("CLOSED>OPEN", "OPEN>HALF_OPEN", "HALF_OPEN>CLOSED");
        assertThat(b.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void exportsMetrics() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CircuitBreaker b = new CircuitBreaker("dep", settings(2, 0, 10, 10, 1), clock, meters, null, null);
        succeed(b);
        fail(b);
        fail(b); // opens
        assertThat(b.tryAcquire().granted()).isFalse();
        b.tryAcquire(); // second rejection
        assertThat(meters.get("resilience.circuit.calls").tag("name", "dep").tag("outcome", "success")
            .counter().count()).isEqualTo(1.0);
        assertThat(meters.get("resilience.circuit.calls").tag("name", "dep").tag("outcome", "failure")
            .counter().count()).isEqualTo(2.0);
        assertThat(meters.get("resilience.circuit.calls").tag("name", "dep").tag("outcome", "rejected")
            .counter().count()).isEqualTo(2.0);
        assertThat(meters.get("resilience.circuit.transitions").tag("name", "dep").tag("to", "OPEN")
            .counter().count()).isEqualTo(1.0);
        assertThat(meters.get("resilience.circuit.state").tag("name", "dep").gauge().value())
            .isEqualTo((double) State.OPEN.code());
    }

    // ------------------------------------------------------------ concurrency

    @Test
    void concurrentAcquiresInHalfOpenNeverExceedTheProbeBudget() throws Exception {
        int probes = 3;
        CircuitBreaker b = openBreaker(settings(2, 0, 10, 10, probes));
        clock.advance(OPEN_FOR);
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger granted = new AtomicInteger();
            List<Permit> permits = java.util.Collections.synchronizedList(new ArrayList<>());
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    Permit p = b.tryAcquire();
                    if (p.granted()) {
                        granted.incrementAndGet();
                        permits.add(p);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
            assertThat(granted).hasValue(probes);
            permits.forEach(Permit::success);
            assertThat(b.state()).isEqualTo(State.CLOSED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentFailuresOpenTheBreakerExactlyOnce() throws Exception {
        List<CircuitBreaker.Transition> transitions = java.util.Collections.synchronizedList(new ArrayList<>());
        CircuitBreaker b = new CircuitBreaker("t", settings(5, 0, 10, 10, 1), clock, null, null, transitions::add);
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    Permit p = b.tryAcquire();
                    if (p.granted()) {
                        p.failure(new IOException());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
            assertThat(b.state()).isEqualTo(State.OPEN);
            assertThat(transitions).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
