package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.runtime.AgentRuntimeSettings;
import com.agenticai.core.security.concurrent.TenantAwareSchedulers;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The wall-clock limit of one turn: the turn's runtime settings ({@code turnTimeoutSeconds}: platform
 * {@code app.llm.http.buffered-turn-timeout} unless a tenant or agent set another) and the enclosing
 * deadline an agent job gives its turn. Decided here so every depth reads the same rules.
 *
 * <ul>
 *   <li><b>Buffered depths</b> (CAREFUL / RIGOROUS / PLANNED) always have a limit. With a deadline set, the
 *       turn ends at the deadline when that is sooner; a deadline LATER than the platform default extends
 *       it (a job has no browser that gives up at 300 s, and its own timeout is the authority), but never an
 *       explicit tenant/agent limit, which the owner set on purpose.
 *   <li><b>FAST streams</b> have no overall limit of their own (only the idle timer), so one applies only
 *       when a tenant or agent set it or a deadline gives it: the sooner of the two.
 * </ul>
 */
final class TurnTimeLimit {

    private TurnTimeLimit() {}

    /** The turn ran past its limit. Not a {@link java.util.concurrent.TimeoutException}: a slow turn is not a dead provider. */
    static final class Exceeded extends RuntimeException {

        private final transient Duration limit;

        Exceeded(Duration limit) {
            super("The turn ran past its " + Math.max(1, (limit.toMillis() + 999) / 1000) + "-second limit");
            this.limit = limit;
        }

        Duration limit() {
            return limit;
        }

        /** The limit in whole seconds, at least one, for a notice that says "N-second limit". */
        Duration limitRoundedUp() {
            return Duration.ofSeconds(Math.max(1, (limit.toMillis() + 999) / 1000));
        }
    }

    /** How long one buffered turn may run, as of {@code now}. {@code configured} is the platform default. */
    static Duration buffered(TurnContext context, Instant now, Duration configured) {
        AgentRuntimeSettings settings = context.runtimeSettings();
        Duration limit = settings != null ? settings.turnTimeout() : configured;
        boolean explicit = settings != null && settings.turnTimeoutOverridden();
        Optional<Duration> remaining = context.remaining(now).filter(left -> !left.isZero());
        if (remaining.isEmpty()) {
            // No deadline, or one that already passed: a clock a moment ahead must not fire the turn at once.
            return limit;
        }
        Duration left = remaining.get();
        if (!explicit && left.compareTo(limit) > 0) {
            return left;
        }
        return left.compareTo(limit) < 0 ? left : limit;
    }

    /** The overall limit of a FAST stream, or empty when it has none. */
    static Optional<Duration> fast(TurnContext context, Instant now) {
        AgentRuntimeSettings settings = context.runtimeSettings();
        Optional<Duration> own =
                settings != null && settings.turnTimeoutOverridden()
                        ? Optional.of(settings.turnTimeout())
                        : Optional.empty();
        Optional<Duration> deadline = context.remaining(now).filter(left -> !left.isZero());
        if (own.isPresent() && deadline.isPresent()) {
            return Optional.of(own.get().compareTo(deadline.get()) < 0 ? own.get() : deadline.get());
        }
        return own.isPresent() ? own : deadline;
    }

    /**
     * {@code source}, failed with {@link Exceeded} if it has not completed within {@code limit} of being
     * subscribed to. The timer is cancelled when the source ends first; it ticks on the tenant-aware scheduler
     * and only signals, so it touches no tenant state.
     */
    static <T> Flux<T> cap(Flux<T> source, Duration limit) {
        return Flux.defer(
                () ->
                        source.takeUntilOther(
                                Mono.delay(limit, TenantAwareSchedulers.parallel())
                                        .<Object>flatMap(tick -> Mono.error(new Exceeded(limit)))));
    }
}
