package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.turn.TurnContinuation.Decision;
import com.agenticai.core.agents.turn.TurnContinuation.Signals;
import com.agenticai.core.agents.turn.TurnReplyOutcome.Kind;
import com.agenticai.core.chat.ToolLoopTranscript;
import reactor.core.publisher.Flux;

/**
 * Keeps a streamed task going when the model stops at "let me do X" without doing it, moved out of
 * {@link FastStreamTurn}. After the primary pass (and the finish-reason handling that follows it) the
 * reply is put to {@link TurnContinuation}; if it reads as an announced step that was not taken, the
 * model is called again with the real conversation so far ({@link ContinuationThread}: its own tool-use
 * and the tool results, the original request with its attachments) and a nudge to act.
 *
 * <p>Bounded by {@code app.chat.continuation.max-passes}. Stops when the decision says so: the turn is
 * parked on a human approval, the model is not a weak tool caller, a pass called no tool, or the reply
 * does not read as unfinished. A pass that fails is not swallowed: the reply is closed with the
 * interrupted marker, as a failure after text has streamed always is, and a policy stop (step ceiling,
 * guardrail, content policy) reaches the caller.
 */
final class FastStreamContinuation {

    private final FastStreamTurn t;
    private final ContinuationThread thread;

    FastStreamContinuation(FastStreamTurn turn) {
        this.t = turn;
        this.thread = new ContinuationThread(turn.systemPrompt, turn.history, turn.userMessage, turn.attachments);
    }

    /** The passes to run after the primary one, as a stream to concatenate after it. */
    Flux<String> afterPrimaryPass() {
        thread.passFinished(t.transcript);
        return pass(0, 0, t.callsAtStart);
    }

    private Flux<String> pass(int passes, int segmentStart, int callsAtPassStart) {
        if (passes >= t.owner.continuationPasses(t.context)) {
            return Flux.empty();
        }
        String segment = t.accumulated.substring(Math.min(segmentStart, t.accumulated.length()));
        TurnContinuation policy = t.owner.continuation;
        Decision decision =
                policy.decide(
                        new Signals(
                                segment,
                                t.context.modelTraits().weakToolCaller(),
                                t.context.approval() != null && t.context.approval().isParked(),
                                passes,
                                t.activity().finishedCalls() - callsAtPassStart),
                        t.owner.behaviour.bool(
                                TurnContinuation.HEURISTIC_ENABLED_KEY,
                                t.context.scope(),
                                t.agentId,
                                policy::heuristicEnabledByPlatform));
        if (!decision.proceed()) {
            ChatTurnExecutor.log.debug(
                    "Agent {} session {}: no continuation pass after pass {} ({})",
                    t.agentId,
                    t.sessionId,
                    passes,
                    decision.reason());
            return Flux.empty();
        }
        ChatTurnExecutor.log.info(
                "Agent {} session {}: reply stopped at an unfinished step; continuation pass {}",
                t.agentId,
                t.sessionId,
                passes + 1);
        t.turn.guard("continuation", "pass-" + (passes + 1));
        TurnLifecycleEvents.unfinishedStepPass(t.stepEvents, passes + 1, t.owner.continuationPasses(t.context));
        ContinuationThread.Request request =
                thread.next(
                        segment,
                        t.context
                                .scope()
                                .get(() -> policy.nudge(decision.locale(), t.owner.prompts.apply(t.agentId))));
        // The pass's own invocation transcript, seeded with the rounds the turn already spent so the
        // loop's round cap counts all of the turn's passes instead of refilling on each one.
        ToolLoopTranscript transcript =
                t.owner.newTranscript(t.context, t.agentId).roundsSpentBefore(t.toolRoundsSpent());
        int nextStart = t.accumulated.length() + 2;
        int callsAtStart = t.activity().finishedCalls();
        // The previous pass completes on an HTTP-client thread that holds no TenantContext, and
        // concatWith(Flux.defer(...)) subscribes this pass from there. Subscribe it in the turn's captured
        // scope so the advisors (LlmMetricsAdvisor, RlTrajectoryAdvisor) see the tenant and their
        // llm_call_log / rl_episode writes land in the tenant schema instead of the fail-closed
        // "tenant_context_missing" one (AGENTS.md rule 12).
        Flux<String> pass =
                TenantScopedFlux.subscribeInScope(
                                t.context.scope(), () -> t.continuationContent(request, transcript))
                        .transform(t::idleTimeout)
                        .onErrorResume(
                                e -> {
                                    ChatTurnExecutor.log.warn(
                                            "Continuation pass {} failed for agent {} session {}",
                                            passes + 1,
                                            t.agentId,
                                            t.sessionId,
                                            e);
                                    // A policy stop means the turn was stopped, not that a pass hiccupped:
                                    // it reaches the caller. Any other failure leaves the task unfinished,
                                    // so the reply is closed with the same honest-truncation marker the
                                    // primary pass gets, instead of looking complete.
                                    if (FastStreamRecovery.isPolicyStop(e)) {
                                        return Flux.error(e);
                                    }
                                    t.reply().mark(Kind.INTERRUPTED);
                                    return Flux.just(AgentChatService.INTERRUPTED_REPLY_MARKER);
                                });
        return Flux.just("\n\n")
                .concatWith(pass)
                // This pass can itself have been cut off or stopped by the provider.
                .concatWith(
                        Flux.defer(
                                () -> {
                                    thread.passFinished(transcript);
                                    t.spendPassRounds(transcript);
                                    return t.settleFinish();
                                }))
                .concatWith(
                        Flux.defer(
                                () ->
                                        t.reply().isComplete()
                                                ? pass(passes + 1, nextStart, callsAtStart)
                                                : Flux.empty()));
    }
}
