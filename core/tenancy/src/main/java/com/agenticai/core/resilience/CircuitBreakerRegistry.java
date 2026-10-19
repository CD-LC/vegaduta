package com.agenticai.core.resilience;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Owns the process's circuit breakers and bulkheads, by dependency name.
 *
 * <p><b>Names and families.</b> A name is {@code family} or {@code family:instance}
 * (e.g. {@code brave}, {@code e2b}, {@code sidecar}, {@code meta-graph},
 * {@code hosted-llm:row:12}). Tuning is per FAMILY, so every hosted-LLM row shares the
 * {@code hosted-llm} settings but trips independently.
 *
 * <p><b>Master switch.</b> While {@code enabled} is false (the default,
 * {@code app.resilience.circuit-breaker.enabled=${RESILIENCE_CB_ENABLED:false}}),
 * {@link #guard(String)} returns {@link ResilienceGuard#NOOP} and nothing in the process changes.
 *
 * <p><b>Per replica.</b> State is in memory on purpose: a breaker is consulted on request
 * paths, and a shared (Redis) breaker would put a network hop in front of the very calls it is
 * protecting. Each replica learns about a dead dependency from its own first few failures; that
 * costs at most the threshold's worth of failed calls per replica per open period, and it means
 * old and new revisions side by side during a deploy cannot disagree through shared state.
 *
 * <p><b>Reaching non-bean code.</b> {@code ResilienceConfiguration} installs the bean as the
 * process-wide {@link #shared()} instance so self-constructed clients (which no Spring wiring
 * reaches, e.g. {@code BraveWebSearchClient}) can look their guard up. {@link #shared()} never
 * returns null: before install / after close it is a disabled registry. It is a process-wide
 * static, so with several Spring contexts in one JVM the most recently installed registry wins
 * (production runs one context per process; tests that install one must call {@code install(null)}
 * in teardown, as {@code BraveWebSearchClientCircuitBreakerTest} does). Accepted, not fixed: an
 * instance-scoped lookup would need wiring into clients that are built with {@code new}.
 *
 * <p><b>Time.</b> The default clock is monotonic ({@link MonotonicClock}), not wall-clock.
 */
public final class CircuitBreakerRegistry implements AutoCloseable {

    private static final CircuitBreakerRegistry DISABLED = new CircuitBreakerRegistry(
        false, family -> CircuitBreakerSettings.defaults(), family -> 0, Duration.ZERO, MonotonicClock.INSTANCE, null);

    private static volatile CircuitBreakerRegistry shared = DISABLED;

    private final boolean enabled;
    private final Function<String, CircuitBreakerSettings> settingsByFamily;
    private final ToIntFunction<String> bulkheadLimitByFamily;
    private final Duration bulkheadMaxWait;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Map<String, ResilienceGuard> guards = new ConcurrentHashMap<>();
    private final Map<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();

    /**
     * @param bulkheadLimitByFamily max concurrent calls for a family; 0 or less = no bulkhead.
     * @param meters nullable.
     */
    public CircuitBreakerRegistry(
        boolean enabled,
        Function<String, CircuitBreakerSettings> settingsByFamily,
        ToIntFunction<String> bulkheadLimitByFamily,
        Duration bulkheadMaxWait,
        Clock clock,
        MeterRegistry meters
    ) {
        this.enabled = enabled;
        this.settingsByFamily = settingsByFamily;
        this.bulkheadLimitByFamily = bulkheadLimitByFamily;
        this.bulkheadMaxWait = bulkheadMaxWait;
        this.clock = clock == null ? MonotonicClock.INSTANCE : clock;
        this.meters = meters;
    }

    /** The process-wide registry; a disabled one until {@link #install} is called. Never null. */
    public static CircuitBreakerRegistry shared() {
        return shared;
    }

    /** Makes {@code registry} the {@link #shared()} one. Called by the Spring configuration. */
    public static void install(CircuitBreakerRegistry registry) {
        shared = registry == null ? DISABLED : registry;
    }

    /** Family of a name: the part before the first ':' (the whole name when there is none). */
    public static String family(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(0, colon);
    }

    public boolean enabled() {
        return enabled;
    }

    /** The breaker for {@code name}, created on first use. Exists even while disabled (tests, admin views). */
    public CircuitBreaker breaker(String name) {
        return breakers.computeIfAbsent(name, n -> new CircuitBreaker(
            n, settingsByFamily.apply(family(n)), clock, meters, null, null));
    }

    /** The guard integrations use. {@link ResilienceGuard#NOOP} while disabled. */
    public ResilienceGuard guard(String name) {
        if (!enabled) {
            return ResilienceGuard.NOOP;
        }
        return guards.computeIfAbsent(name, n -> new ResilienceGuard(breaker(n), bulkheadFor(n)));
    }

    private Bulkhead bulkheadFor(String name) {
        int limit = bulkheadLimitByFamily.applyAsInt(family(name));
        if (limit <= 0) {
            return null;
        }
        return bulkheads.computeIfAbsent(name, n -> new Bulkhead(n, limit, bulkheadMaxWait, meters));
    }

    /** Snapshot of every breaker created so far, by name (sorted). */
    public Map<String, CircuitBreaker.State> states() {
        Map<String, CircuitBreaker.State> out = new TreeMap<>();
        breakers.forEach((name, breaker) -> out.put(name, breaker.state()));
        return out;
    }

    /** Uninstalls this registry if it is the shared one. Spring calls this on context shutdown. */
    @Override
    public void close() {
        if (shared == this) {
            shared = DISABLED;
        }
    }
}
