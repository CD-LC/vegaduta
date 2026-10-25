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
 *   <li><b>FAST streams</b> have their own overall ceiling ({@code app.llm.http.fast-turn-timeout}):
 *       the idle timer only bounds model SILENCE - every tool round re-arms it - so without a total
 *       limit a turn that kept calling tools could run for hours. An explicit tenant/agent turn
 *       timeout replaces the platform ceiling; an enclosing deadline (an agent job's) still ends the
 *       turn sooner, but unlike a buffered depth a later deadline does NOT extend it: a stream that
 *       outlives the ceiling has outlived what a user-facing turn may take, and a job that
 *       legitimately needs longer sets {@code turnTimeoutSeconds}. A non-positive configured ceiling
 *       means the platform sets none.
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

    /**
     * The overall limit of a FAST stream: {@code configured} (the platform's {@code
     * fast-turn-timeout}) unless a tenant or agent set the turn timeout - theirs wins as set - or
     * the enclosing deadline is sooner. Empty when neither applies (a non-positive {@code
     * configured} and no deadline, the pre-ceiling behavior).
     */
    static Optional<Duration> fast(TurnContext context, Instant now, Duration configured) {
        AgentRuntimeSettings settings = context.runtimeSettings();
        Duration limit =
                settings != null && settings.turnTimeoutOverridden()
                        ? settings.turnTimeout()
                        : configured;
        Optional<Duration> deadline = context.remaining(now).filter(left -> !left.isZero());
        if (limit == null || !limit.isPositive()) {
            return deadline;
        }
        return Optional.of(deadline.map(left -> left.compareTo(limit) < 0 ? left : limit).orElse(limit));
    }

    /**
     * {@code source}, failed with {@link Exceeded} if it has not completed within {@code limit} of being
     * subscribed to. The timer is cancelled when the source ends first; it ticks on the tenant-aware scheduler
     * and only signals, so it touches no tenant state.
     */
    static <T> Flux<T> cap(Flux<T> source, Duration limit) {
        return cap(source, limit, TenantAwareSchedulers.parallel());
    }

    /** As {@link #cap(Flux, Duration)} on {@code scheduler} (a test's manual clock). */
    static <T> Flux<T> cap(Flux<T> source, Duration limit, reactor.core.scheduler.Scheduler scheduler) {
        return Flux.defer(
                () ->
                        source.takeUntilOther(
                                Mono.delay(limit, scheduler)
                                        .<Object>flatMap(tick -> Mono.error(new Exceeded(limit)))));
    }
}
