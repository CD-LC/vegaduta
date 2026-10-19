package com.agenticai.core.agents.hosted;

import com.agenticai.core.resilience.CircuitBreaker;
import com.agenticai.core.resilience.CircuitBreakerRegistry;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-row liveness + observed-latency memory for the platform-hosted LLM pool ({@code
 * PlatformHostedLlmProvider}) - the "don't keep paying the timeout of a provider we already know is
 * dead" half of the pool's failover, plus the only real LATENCY signal the ordering in {@link
 * FreeTierChatClientService} has to work with.
 *
 * <p><b>Why not reuse {@code ToolCircuitBreakerService}</b> (the one circuit breaker this codebase
 * already has): it is Redis-backed and keyed by {@code (tenantId, toolName)} - both wrong here. The
 * hosted pool is PLATFORM-wide shared infrastructure, so a Gemini row that is 404ing is 404ing for
 * every tenant at once and there is nothing tenant-scoped to learn; and this state is consulted on
 * the hot path of every hosted chat turn, where a Redis round-trip per provider per turn would add
 * exactly the latency this class exists to avoid. Deliberately in-memory and therefore
 * per-instance: each core replica re-learns a dead provider on its own first few failures. That is
 * a real limitation, not an oversight - it is also self-healing, needs no migration, and costs at
 * most {@link #failureThreshold} wasted calls per replica per cooldown, which is the trade this
 * feature is worth.
 *
 * <p>Failure accounting mirrors {@code ToolCircuitBreakerService}'s posture: a CONSECUTIVE-failure
 * streak (any success clears it), the streak itself expiring after {@link #failureWindow} so a slow
 * trickle of isolated failures spread hours apart never accumulates into a trip.
 *
 * <p>{@link #isTripped} is advisory only. {@code FreeTierChatClientService} pushes tripped rows to
 * the BACK of the chain rather than dropping them, so a single-row pool (the live production shape
 * today) still gets tried and a user never gets "no provider available" from a breaker that happens
 * to be open - see that class's {@code orderForFailover}.
 */
@Component
public class HostedProviderHealth {

    private static final Logger log = LoggerFactory.getLogger(HostedProviderHealth.class);

    /**
     * Weight of the newest observation in the latency EWMA - recent behaviour dominates without a
     * full history buffer.
     */
    private static final double LATENCY_EWMA_ALPHA = 0.3;

    private static final long NO_LATENCY_OBSERVATION = -1L;

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final int failureThreshold;
    private final long failureWindowMs;
    private final long cooldownMs;

    public HostedProviderHealth(
            @Value("${app.hosted-pool.circuit.failure-threshold:3}") int failureThreshold,
            @Value("${app.hosted-pool.circuit.failure-window-seconds:120}")
                    long failureWindowSeconds,
            @Value("${app.hosted-pool.circuit.cooldown-seconds:180}") long cooldownSeconds) {
        this.failureThreshold = failureThreshold;
        this.failureWindowMs = failureWindowSeconds * 1000L;
        this.cooldownMs = cooldownSeconds * 1000L;
    }

    /**
     * Breaker name prefix; the family ({@code hosted-llm}) is what {@code
     * app.resilience.circuit-breaker.instances.*} tunes.
     */
    static final String BREAKER_FAMILY = "hosted-llm:";

    /**
     * Null until Spring injects it (and in every test that builds this class by hand): no breaking
     * then.
     */
    private volatile CircuitBreakerRegistry breakerRegistry;

    /**
     * Optional per-row circuit breaker. Setter (not constructor) injection so the hand-built {@code
     * new HostedProviderHealth(3, 120, 180)} in tests keeps compiling and behaves exactly as
     * before.
     */
    @Autowired(required = false)
    public void setCircuitBreakerRegistry(CircuitBreakerRegistry registry) {
        this.breakerRegistry = registry;
    }

    /**
     * Asks the row's circuit breaker for permission to call it. Gated by {@code
     * app.resilience.circuit-breaker.enabled} (default true in application.yml; false in code for
     * slim contexts). Never blocks, never does I/O.
     *
     * <p>Unlike {@link #isTripped} (advisory ORDERING: tripped rows go to the back), an open
     * breaker lets {@code HostedChainFailover} SKIP the row exactly as if it had just failed - but
     * only when another row follows; the last row is always attempted, so a single-row pool (the
     * live production shape) never turns a breaker into "no provider available".
     *
     * @return null when breaking does not apply (flag off, no registry, no key) - proceed exactly
     *     as before; otherwise a permit whose {@code granted()} says whether to call the row. A
     *     granted permit must be settled by the caller.
     */
    public CircuitBreaker.Permit acquireBreakerPermit(String key) {
        CircuitBreakerRegistry registry = breakerRegistry;
        if (key == null || registry == null || !registry.enabled()) {
            return null;
        }
        try {
            return registry.breaker(BREAKER_FAMILY + key).tryAcquire();
        } catch (RuntimeException e) {
            // Fail safe: if the breaker cannot decide, behave as if there were none.
            return null;
        }
    }

    /** True while this row is inside its post-trip cooldown and should be tried LAST, not first. */
    public boolean isTripped(String key) {
        State state = states.get(key);
        return state != null && state.openUntilMs.get() > System.currentTimeMillis();
    }

    /**
     * A provider actually answered - clears any open circuit and the failure streak, and folds
     * {@code latencyMs} into this row's latency EWMA.
     *
     * @param latencyMs time from subscribing to the provider's stream to its FIRST emitted element.
     *     For the streaming FAST path that is time-to-first-token; for the buffered
     *     CAREFUL/RIGOROUS/PLANNED paths (one element carrying the whole answer) it is the full
     *     call. Both are legitimate "how quick is this provider" signals and this class does not
     *     pretend they are the same measurement - it only ever compares a row against other rows
     *     serving the same kind of turn.
     */
    public void recordSuccess(String key, long latencyMs) {
        if (key == null) {
            return;
        }
        State state = states.computeIfAbsent(key, k -> new State());
        state.consecutiveFailures.set(0);
        state.openUntilMs.set(0L);
        state.ewmaLatencyMs.updateAndGet(
                previous ->
                        previous == NO_LATENCY_OBSERVATION
                                ? latencyMs
                                : Math.round(
                                        LATENCY_EWMA_ALPHA * latencyMs
                                                + (1 - LATENCY_EWMA_ALPHA) * previous));
    }

    /**
     * A provider failed before producing anything - trips the circuit once the streak reaches the
     * threshold.
     */
    public void recordFailure(String key) {
        if (key == null) {
            return;
        }
        State state = states.computeIfAbsent(key, k -> new State());
        long now = System.currentTimeMillis();
        long streakStarted = state.streakStartedAtMs.get();
        int failures;
        if (streakStarted == 0L || now - streakStarted > failureWindowMs) {
            // Stale streak - the previous failures are too old to be evidence
            // about this provider's current state, so start counting again.
            state.streakStartedAtMs.set(now);
            state.consecutiveFailures.set(1);
            failures = 1;
        } else {
            failures = state.consecutiveFailures.incrementAndGet();
        }
        if (failures >= failureThreshold) {
            state.openUntilMs.set(now + cooldownMs);
            state.consecutiveFailures.set(0);
            state.streakStartedAtMs.set(0L);
            log.warn(
                    "Hosted pool provider {} failed {} times in a row - de-prioritising it for the"
                            + " next {}s",
                    key,
                    failures,
                    cooldownMs / 1000L);
        }
    }

    /**
     * Observed latency for this row, or empty when it has never successfully answered on this
     * instance.
     */
    public OptionalDouble observedLatencyMs(String key) {
        State state = key == null ? null : states.get(key);
        if (state == null) {
            return OptionalDouble.empty();
        }
        long ewma = state.ewmaLatencyMs.get();
        return ewma == NO_LATENCY_OBSERVATION ? OptionalDouble.empty() : OptionalDouble.of(ewma);
    }

    private static final class State {
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final AtomicLong streakStartedAtMs = new AtomicLong();
        private final AtomicLong openUntilMs = new AtomicLong();
        private final AtomicLong ewmaLatencyMs = new AtomicLong(NO_LATENCY_OBSERVATION);
    }
}
