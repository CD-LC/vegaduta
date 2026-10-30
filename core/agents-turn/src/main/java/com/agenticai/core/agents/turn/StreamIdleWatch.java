package com.agenticai.core.agents.turn;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;

/**
 * The FAST stream's idle timeout, aware of tool execution. A plain {@code Flux#timeout(Duration)}
 * resets only on tokens, but the tool loop runs inside the model stream: while a tool works (a
 * browser task, a coding task, an agent-to-agent call, a local model's prompt evaluation) nothing
 * is emitted, and the timer declared the provider dead, failed the turn, and let the recovery
 * ladder replay it - tools included - on another provider.
 *
 * <p>This counts model silence only. The turn is idle when nothing has happened for the idle
 * timeout, where "something happened" is a token, a tool starting, a tool finishing or a tool
 * reporting progress ({@link TurnToolActivity}). While a tool is in flight the allowance is the
 * longer {@code toolCeiling}, measured from the tool's last start, finish or progress, so a tool
 * that outlives it is still caught as hung. The timeout surfaces as the same {@link
 * TimeoutException} the plain operator raised, so everything that recognises it ({@code
 * ProviderErrorTranslator}, {@link DegradedRetrySignal#isTimeout}) is unchanged.
 */
final class StreamIdleWatch {

    private StreamIdleWatch() {}

    /**
     * @param source the model's token stream
     * @param idle how long the model may be silent while no tool is running
     * @param toolCeiling how long a tool may run, silent, before it is counted as hung
     * @param activity the turn's tool activity; also the clock the decision is made on
     * @param scheduler where the timers run ({@code Schedulers.parallel()} in production)
     */
    /**
     * As {@link #apply}, with the serving row's own idle-timeout (V674): {@code context}'s model
     * traits carry the row's {@code responseTimeoutMs} (set per attempt by {@code dispatchTurn}),
     * which replaces the platform {@code idle} for this stream only. Null/absent = the platform
     * value.
     */
    static <T> Flux<T> apply(
            Flux<T> source,
            Duration idle,
            Duration toolCeiling,
            TurnToolActivity activity,
            TurnContext context,
            Scheduler scheduler) {
        Duration override =
                context == null || context.modelTraits() == null
                        ? null
                        : context.modelTraits().streamIdleTimeout();
        return apply(
                source,
                override != null && override.compareTo(Duration.ZERO) > 0 ? override : idle,
                toolCeiling,
                activity,
                scheduler);
    }

    static <T> Flux<T> apply(
            Flux<T> source,
            Duration idle,
            Duration toolCeiling,
            TurnToolActivity activity,
            Scheduler scheduler) {
        // Chained on the given stream itself (no wrapping Flux.defer), so a caller that decorated
        // the
        // stream keeps the operator chain it built.
        AtomicLong lastToken = new AtomicLong(activity.nowNanos());
        Publisher<?> watch = watch(idle, toolCeiling, activity, lastToken, scheduler);
        return source.doOnSubscribe(subscription -> lastToken.set(activity.nowNanos()))
                .doOnNext(t -> lastToken.set(activity.nowNanos()))
                .timeout(
                        watch,
                        item -> watch,
                        Flux.defer(
                                () ->
                                        Flux.error(
                                                new TimeoutException(
                                                        "Did not observe any item or terminal signal within "
                                                                + idle.toMillis()
                                                                + "ms (no token and no tool activity)"))));
    }

    /**
     * A publisher that completes once the turn has been idle long enough, re-checking after every
     * wait: a wait that ends while a tool has meanwhile started or finished is simply started again
     * for the time that is left.
     */
    private static Publisher<?> watch(
            Duration idle,
            Duration toolCeiling,
            TurnToolActivity activity,
            AtomicLong lastToken,
            Scheduler scheduler) {
        // The wait emits its tick; .then() drops it, because the timeout operator fires on the
        // first
        // element OR the completion of this publisher and only the completion means "idle". A tool
        // starting, finishing or reporting progress ends the wait early so the deadline is worked
        // out
        // again: a wait computed while a tool ran (the long allowance) must not outlive the tool.
        return Mono.defer(
                        () -> {
                            Sinks.Empty<Void> changed = Sinks.empty();
                            Runnable remove = activity.onActivity(changed::tryEmitEmpty);
                            return Mono.firstWithSignal(
                                            Mono.delay(
                                                            untilIdle(
                                                                    idle,
                                                                    toolCeiling,
                                                                    activity,
                                                                    lastToken),
                                                            scheduler)
                                                    .then(),
                                            changed.asMono())
                                    .doFinally(signal -> remove.run());
                        })
                .repeat(() -> !untilIdle(idle, toolCeiling, activity, lastToken).isZero());
    }

    /**
     * How much longer the turn may stay quiet; {@link Duration#ZERO} when it has been quiet too
     * long.
     */
    private static Duration untilIdle(
            Duration idle, Duration toolCeiling, TurnToolActivity activity, AtomicLong lastToken) {
        boolean toolRunning = activity.inFlightCount() > 0;
        long allowed =
                (toolRunning && toolCeiling.compareTo(idle) > 0 ? toolCeiling : idle).toNanos();
        long lastHappened = Math.max(lastToken.get(), activity.lastActivityNanos());
        long left = lastHappened + allowed - activity.nowNanos();
        return left <= 0 ? Duration.ZERO : Duration.ofNanos(Math.max(left, 1_000_000L));
    }
}
