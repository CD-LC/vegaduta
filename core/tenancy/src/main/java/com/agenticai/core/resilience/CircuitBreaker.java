package com.agenticai.core.resilience;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A small in-house circuit breaker (closed / open / half-open). No Resilience4j:
 * the build does not carry it and this needs only the state machine.
 *
 * <h4>Contract</h4>
 * <ul>
 *   <li>{@link #tryAcquire()} NEVER blocks and never does I/O - it is safe on a
 *       request thread. It returns a {@link Permit}; a rejected permit means
 *       "do not call the dependency, return the feature's existing unavailable
 *       result".</li>
 *   <li>A granted permit must be settled by the caller with {@link Permit#success()},
 *       {@link Permit#failure(Throwable)} or {@link Permit#ignore()} (first call
 *       wins, later calls are no-ops, so a {@code finally { permit.ignore(); }}
 *       is always safe). Use {@code ignore()} for outcomes that say nothing about
 *       the dependency's health (caller cancelled, tenant's own bad input).</li>
 *   <li>Outcomes of calls started BEFORE the latest state change are dropped
 *       (a permit carries the epoch it was issued in), so a slow call that
 *       finishes after the breaker already re-opened cannot corrupt a probe.</li>
 *   <li>Thread safety: one short critical section per acquire/settle. State is
 *       per JVM (per replica) on purpose - see {@code CircuitBreakerRegistry}.</li>
 *   <li>Time comes from an injected {@link Clock}, so every edge is unit-testable.</li>
 * </ul>
 *
 * <p>Metrics (Micrometer, only when a registry is given):
 * {@code resilience.circuit.calls{name,outcome=success|failure|rejected|ignored|stale}},
 * {@code resilience.circuit.transitions{name,to}} and the gauge
 * {@code resilience.circuit.state{name}} (0 closed, 1 half-open, 2 open).
 */
public final class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    /** Breaker state. {@code code} is the value exported by the state gauge. */
    public enum State {
        CLOSED(0), HALF_OPEN(1), OPEN(2);

        private final int code;

        State(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    /** A state change, handed to the optional listener (outside the breaker's lock). */
    public record Transition(String name, State from, State to, long atMillis) {}

    private enum Outcome { SUCCESS, FAILURE, IGNORED }

    private final String name;
    private final CircuitBreakerSettings settings;
    private final Clock clock;
    private final Predicate<Throwable> countsAsFailure;
    private final Consumer<Transition> listener;
    private final long openMillis;
    private final long windowMillis;
    private final long probeTimeoutMillis;

    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter rejectedCounter;
    private final Counter ignoredCounter;
    private final Counter staleCounter;
    private final MeterRegistry meters;

    private final Object lock = new Object();

    // ---- guarded by lock (state is volatile only so the gauge can read it lock-free) ----
    private volatile State state = State.CLOSED;
    private long epoch;
    private long openedAtMillis;
    private int consecutiveFailures;
    private final long[] ringTimes;
    private final boolean[] ringFailed;
    private int ringCount;
    private int ringNext;
    private int probesIssued;
    private int probesSucceeded;
    private long lastProbeIssuedAtMillis;

    public CircuitBreaker(String name, CircuitBreakerSettings settings) {
        this(name, settings, MonotonicClock.INSTANCE, null, null, null);
    }

    /**
     * @param countsAsFailure nullable; decides whether a failure passed to {@link Permit#failure(Throwable)}
     *     really is a dependency fault. A failure it rejects is recorded as a SUCCESS (the dependency
     *     answered). Null = every reported failure counts.
     * @param listener nullable; called after every state change, outside the lock.
     * @param meters nullable; no metrics when null.
     */
    public CircuitBreaker(
        String name, CircuitBreakerSettings settings, Clock clock, MeterRegistry meters,
        Predicate<Throwable> countsAsFailure, Consumer<Transition> listener
    ) {
        this.name = name;
        this.settings = settings == null ? CircuitBreakerSettings.defaults() : settings;
        this.clock = clock == null ? MonotonicClock.INSTANCE : clock;
        this.countsAsFailure = countsAsFailure;
        this.listener = listener;
        this.openMillis = this.settings.openDuration().toMillis();
        this.windowMillis = this.settings.window().toMillis();
        this.probeTimeoutMillis = this.settings.halfOpenProbeTimeout().toMillis();
        this.ringTimes = new long[this.settings.windowSize()];
        this.ringFailed = new boolean[this.settings.windowSize()];
        this.meters = meters;
        if (meters != null) {
            this.successCounter = counter(meters, "success");
            this.failureCounter = counter(meters, "failure");
            this.rejectedCounter = counter(meters, "rejected");
            this.ignoredCounter = counter(meters, "ignored");
            this.staleCounter = counter(meters, "stale");
            Gauge.builder("resilience.circuit.state", this, b -> b.state.code())
                .description("Circuit breaker state: 0 closed, 1 half-open, 2 open")
                .tag("name", name)
                .register(meters);
        } else {
            this.successCounter = null;
            this.failureCounter = null;
            this.rejectedCounter = null;
            this.ignoredCounter = null;
            this.staleCounter = null;
        }
    }

    private Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("resilience.circuit.calls").tag("name", name).tag("outcome", outcome).register(registry);
    }

    public String name() {
        return name;
    }

    public CircuitBreakerSettings settings() {
        return settings;
    }

    /** Current state as last decided (an expired OPEN only becomes HALF_OPEN on the next acquire). */
    public State state() {
        return state;
    }

    /** Failures in a row since the last success (CLOSED state only; 0 otherwise). */
    public int consecutiveFailures() {
        synchronized (lock) {
            return consecutiveFailures;
        }
    }

    /** Millis until an OPEN breaker starts admitting probes; 0 when not open or already due. */
    public long retryAfterMillis() {
        synchronized (lock) {
            if (state != State.OPEN) {
                return 0L;
            }
            return Math.max(0L, openMillis - (clock.millis() - openedAtMillis));
        }
    }

    /** Forces CLOSED with empty history (operator/test hook). */
    public void reset() {
        Transition t = null;
        synchronized (lock) {
            if (state != State.CLOSED) {
                t = moveTo(State.CLOSED, clock.millis());
            } else {
                resetWindow();
                consecutiveFailures = 0;
                epoch++;
            }
        }
        publish(t);
    }

    /**
     * Asks for permission to call the dependency. Never blocks, never throws.
     */
    public Permit tryAcquire() {
        Transition transition = null;
        Permit permit;
        synchronized (lock) {
            long now = clock.millis();
            if (state == State.OPEN && now - openedAtMillis >= openMillis) {
                transition = moveTo(State.HALF_OPEN, now);
            }
            switch (state) {
                case CLOSED -> permit = new Permit(this, true, epoch, false);
                case OPEN -> permit = Permit.rejected();
                default -> {
                    if (probesIssued < settings.halfOpenPermittedCalls()) {
                        probesIssued++;
                        lastProbeIssuedAtMillis = now;
                        permit = new Permit(this, true, epoch, true);
                    } else if (now - lastProbeIssuedAtMillis >= probeTimeoutMillis) {
                        // Probes that never reported back (caller died / forgot to settle): reclaim the
                        // slots, and bump the epoch so a leaked probe finishing late is dropped.
                        log.warn("Circuit '{}' reclaiming half-open probes that never reported an outcome", name);
                        epoch++;
                        probesIssued = 1;
                        probesSucceeded = 0;
                        lastProbeIssuedAtMillis = now;
                        permit = new Permit(this, true, epoch, true);
                    } else {
                        permit = Permit.rejected();
                    }
                }
            }
        }
        if (!permit.granted() && rejectedCounter != null) {
            rejectedCounter.increment();
        }
        publish(transition);
        return permit;
    }

    /**
     * Runs {@code call} under the breaker. Throws {@link DependencyUnavailableException} without
     * calling it when the circuit is open; any RuntimeException/Error from {@code call} is reported
     * as a failure and rethrown.
     */
    public <T> T execute(Supplier<T> call) {
        Permit permit = tryAcquire();
        if (!permit.granted()) {
            throw new DependencyUnavailableException(name, DependencyUnavailableException.Reason.CIRCUIT_OPEN);
        }
        try {
            T result = call.get();
            permit.success();
            return result;
        } catch (RuntimeException | Error e) {
            permit.failure(e);
            throw e;
        } finally {
            permit.ignore(); // no-op once settled
        }
    }

    // ------------------------------------------------------------------------------------------

    private void settle(Permit permit, Outcome reported, Throwable cause) {
        Outcome outcome = reported;
        if (reported == Outcome.FAILURE && countsAsFailure != null && !safeCounts(cause)) {
            outcome = Outcome.SUCCESS; // the dependency answered; the error was the caller's
        }
        Transition transition = null;
        boolean stale = false;
        synchronized (lock) {
            if (permit.epoch != epoch) {
                stale = true;
            } else {
                long now = clock.millis();
                switch (outcome) {
                    case IGNORED -> {
                        if (permit.probe && state == State.HALF_OPEN && probesIssued > 0) {
                            probesIssued--; // free the probe slot, neither success nor failure
                        }
                    }
                    case SUCCESS -> {
                        if (state == State.CLOSED) {
                            record(now, false);
                            consecutiveFailures = 0;
                        } else if (state == State.HALF_OPEN && permit.probe) {
                            probesSucceeded++;
                            if (probesSucceeded >= settings.halfOpenPermittedCalls()) {
                                transition = moveTo(State.CLOSED, now);
                            }
                        }
                    }
                    case FAILURE -> {
                        if (state == State.CLOSED) {
                            record(now, true);
                            consecutiveFailures++;
                            if (shouldTrip(now)) {
                                transition = moveTo(State.OPEN, now);
                            }
                        } else if (state == State.HALF_OPEN && permit.probe) {
                            transition = moveTo(State.OPEN, now);
                        }
                    }
                }
            }
        }
        if (stale) {
            increment(staleCounter);
        } else {
            switch (outcome) {
                case SUCCESS -> increment(successCounter);
                case FAILURE -> increment(failureCounter);
                case IGNORED -> increment(ignoredCounter);
            }
        }
        publish(transition);
    }

    private boolean safeCounts(Throwable cause) {
        try {
            return countsAsFailure.test(cause);
        } catch (RuntimeException e) {
            return true; // cannot decide: treat as a real failure (the conservative reading)
        }
    }

    private static void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    // callers hold lock
    private void record(long now, boolean failed) {
        ringTimes[ringNext] = now;
        ringFailed[ringNext] = failed;
        ringNext = (ringNext + 1) % ringTimes.length;
        if (ringCount < ringTimes.length) {
            ringCount++;
        }
    }

    // callers hold lock
    private boolean shouldTrip(long now) {
        if (settings.consecutiveFailureThreshold() > 0
            && consecutiveFailures >= settings.consecutiveFailureThreshold()) {
            return true;
        }
        if (settings.failureRatePercent() > 0) {
            int total = 0;
            int failed = 0;
            for (int i = 0; i < ringCount; i++) {
                if (now - ringTimes[i] <= windowMillis) {
                    total++;
                    if (ringFailed[i]) {
                        failed++;
                    }
                }
            }
            return total >= settings.minimumCalls()
                && (long) failed * 100L >= (long) settings.failureRatePercent() * total;
        }
        return false;
    }

    // callers hold lock
    private void resetWindow() {
        ringCount = 0;
        ringNext = 0;
    }

    // callers hold lock
    private Transition moveTo(State next, long now) {
        State from = state;
        state = next;
        epoch++;
        switch (next) {
            case OPEN -> {
                openedAtMillis = now;
                resetWindow();
                consecutiveFailures = 0;
            }
            case HALF_OPEN -> {
                probesIssued = 0;
                probesSucceeded = 0;
                lastProbeIssuedAtMillis = now;
            }
            case CLOSED -> {
                resetWindow();
                consecutiveFailures = 0;
            }
        }
        return new Transition(name, from, next, now);
    }

    private void publish(Transition transition) {
        if (transition == null) {
            return;
        }
        if (transition.to() == State.OPEN) {
            log.warn("Circuit '{}' OPENED (was {}) - calls are shed for {}s then probed",
                name, transition.from(), openMillis / 1000L);
        } else {
            log.info("Circuit '{}' {} -> {}", name, transition.from(), transition.to());
        }
        if (meters != null) {
            meters.counter("resilience.circuit.transitions", "name", name, "to", transition.to().name()).increment();
        }
        if (listener != null) {
            try {
                listener.accept(transition);
            } catch (RuntimeException e) {
                log.warn("Circuit '{}' transition listener threw", name, e);
            }
        }
    }

    /**
     * One call's right to proceed. Settle it exactly once (extra calls are ignored).
     * Rejected and untracked permits are inert.
     */
    public static final class Permit {

        private static final Permit REJECTED = new Permit(null, false, 0L, false);
        private static final Permit UNTRACKED = new Permit(null, true, 0L, false);

        private final CircuitBreaker owner;
        private final boolean granted;
        private final long epoch;
        private final boolean probe;
        private final AtomicBoolean settled = new AtomicBoolean(false);

        private Permit(CircuitBreaker owner, boolean granted, long epoch, boolean probe) {
            this.owner = owner;
            this.granted = granted;
            this.epoch = epoch;
            this.probe = probe;
        }

        /** The shared "do not call" permit. */
        public static Permit rejected() {
            return REJECTED;
        }

        /** A granted permit that reports nowhere (used when breaking is switched off). */
        public static Permit untracked() {
            return UNTRACKED;
        }

        /** True when the caller may call the dependency. */
        public boolean granted() {
            return granted;
        }

        public void success() {
            settle(Outcome.SUCCESS, null);
        }

        public void failure(Throwable cause) {
            settle(Outcome.FAILURE, cause);
        }

        /** Neutral: says nothing about the dependency (cancel, caller error). Frees a half-open probe slot. */
        public void ignore() {
            settle(Outcome.IGNORED, null);
        }

        private void settle(Outcome outcome, Throwable cause) {
            if (owner != null && settled.compareAndSet(false, true)) {
                owner.settle(this, outcome, cause);
            }
        }
    }
}
