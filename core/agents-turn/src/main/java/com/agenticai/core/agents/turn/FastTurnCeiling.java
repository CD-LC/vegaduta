package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.Agent;
import java.time.Instant;
import reactor.core.publisher.Flux;

/**
 * The FAST stream's overall wall-clock ceiling ({@code app.llm.http.fast-turn-timeout}): what
 * {@link ChatTurnDispatcher} wraps around every FAST reply stream, factored out so the dispatcher
 * stays readable. The idle watchdog alone could not bound a turn - every tool round re-arms it, so
 * a turn that kept making (even instant) tool calls had no total deadline and could loop forever.
 * A stream past the ceiling is cut and the person is told what ran, in the same wording a buffered
 * depth uses; the reply is marked interrupted. The tokens already streamed were stored as an
 * interrupted reply when the cap cancelled the stream, so nothing is stored here.
 */
final class FastTurnCeiling {

    private FastTurnCeiling() {}

    /**
     * {@code fast} bounded by the platform's {@code fast-turn-timeout}, replaced by an explicit
     * tenant/agent turn timeout, or cut to the turn's enclosing deadline when that is sooner (see
     * {@link TurnTimeLimit#fast}). A hit substitutes the partial-steps reply instead of erroring.
     */
    static Flux<String> cap(Flux<String> fast, ChatTurnExecutor turns, TurnContext context, Agent agent) {
        return TurnTimeLimit.fast(context, Instant.now(), turns.fastTurnTimeout)
                .map(
                        limit ->
                                TurnTimeLimit.cap(fast, limit)
                                        .onErrorResume(
                                                TurnTimeLimit.Exceeded.class,
                                                exceeded ->
                                                        Flux.defer(
                                                                () ->
                                                                        deadlineExceededReply(
                                                                                turns,
                                                                                context,
                                                                                agent,
                                                                                exceeded))))
                .orElse(fast);
    }

    /**
     * What the person is told when a FAST stream hits its wall-clock ceiling: the same partial-steps
     * report a buffered depth timeout uses, counted and logged the same way, and the reply marked
     * interrupted so it is never cached or judged.
     */
    private static Flux<String> deadlineExceededReply(
            ChatTurnExecutor turns, TurnContext context, Agent agent, TurnTimeLimit.Exceeded exceeded) {
        ChatResilienceProperties resilience = turns.resilience;
        TurnToolActivity.Snapshot ran = context.toolActivity().snapshot();
        DegradedRetrySignal.flagDepthTimeoutPartial(
                turns.meterRegistry(), agent, "FAST", DepthTimeoutReport.completed(resilience, ran));
        context.reply().mark(TurnReplyOutcome.Kind.INTERRUPTED);
        return Flux.just(DepthTimeoutReport.text(resilience, "FAST", exceeded.limitRoundedUp(), ran));
    }
}
