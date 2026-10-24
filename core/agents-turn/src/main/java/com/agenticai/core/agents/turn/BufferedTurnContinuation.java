package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.tunables.BehaviourSettings;
import com.agenticai.core.agents.turn.TurnContinuation.Decision;
import com.agenticai.core.agents.turn.TurnContinuation.Signals;
import com.agenticai.core.agents.turn.TurnReplyOutcome.Kind;
import com.agenticai.core.chat.FinishReasonCapture;
import com.agenticai.core.chat.ToolLoopTranscript;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The continuation policy of the buffered reasoning depths (CAREFUL, RIGOROUS, PLANNED): the same
 * decision ({@link TurnContinuation}) and the same conversation replay ({@link ContinuationThread}) the
 * streamed path uses ({@link FastStreamContinuation}), so an agent behaves the same whichever depth it
 * is set to. Until now only the streamed path re-prompted a model that stopped at an announced step; the
 * buffered path had a separate check that re-ran the whole turn with the user's message edited, and
 * nothing else.
 *
 * <p>Blocking: each pass is a model call run by {@link Pass}, and its text is appended to the reply
 * after a blank line. A pass that fails leaves the reply as it was, marked interrupted, with the
 * interrupted marker appended - the same honest ending a streamed reply gets; a policy stop (step
 * ceiling, guardrail, content policy) reaches the caller.
 */
final class BufferedTurnContinuation {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    /** One model call: sends {@code request} with the turn's tools, records its tool rounds, reports its finish reason. */
    @FunctionalInterface
    interface Pass {
        String run(ContinuationThread.Request request, ToolLoopTranscript transcript, FinishReasonCapture finish);
    }

    /** Applies the finish-reason handling (cut off, refused, ...) to the text of a pass. */
    @FunctionalInterface
    interface Settle {
        String apply(String text, FinishReasonCapture finish);
    }

    /** What the turn knows when its first pass has finished. */
    record Run(
            UUID agentId,
            String sessionId,
            ContinuationThread thread,
            ToolLoopTranscript transcript,
            int callsAtStart,
            boolean toolsOffered) {}

    /**
     * The model call of a continuation pass on {@code client}: the conversation so far and the nudge, the
     * turn's {@code tools} on offer, the pass's own tool rounds recorded into its transcript.
     */
    static Pass modelPass(
            org.springframework.ai.chat.client.ChatClient client,
            UUID agentId,
            String sessionId,
            java.util.List<org.springframework.ai.tool.ToolCallback> tools,
            String experimentVariants) {
        return (request, transcript, finish) -> {
            org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec spec = client.prompt();
            if (request.systemPrompt() != null) {
                spec = spec.system(request.systemPrompt());
            }
            spec =
                    spec.messages(request.messages())
                            .user(request.user())
                            .toolCallbacks(ChatTurnExecutor.stableToolOrder(tools))
                            .toolContext(java.util.Map.of(ToolLoopTranscript.TOOL_CONTEXT_KEY, transcript));
            String content =
                    BufferedTurnCalls.advised(spec, agentId, sessionId, experimentVariants, finish)
                            .call()
                            .content();
            return content == null ? "" : content;
        };
    }

    private final TurnContinuation policy;
    private final int maxPasses;

    private java.util.function.Supplier<ToolLoopTranscript> transcripts = ToolLoopTranscript::new;

    BufferedTurnContinuation(TurnContinuation policy, int maxPasses) {
        this.policy = policy;
        this.maxPasses = maxPasses;
    }

    private java.util.function.Function<UUID, ResolvedDirectives> prompts =
            ResolvedDirectives.DEFAULTS_FOR_AGENT;

    private BehaviourSettings behaviour = BehaviourSettings.platformOnly();

    /** Where the tenant's and agent's {@code chat.continuation.heuristicEnabled} comes from; default the platform value. */
    BufferedTurnContinuation withBehaviour(BehaviourSettings source) {
        this.behaviour = source == null ? BehaviourSettings.platformOnly() : source;
        return this;
    }

    /** Where the per-agent prompt overrides come from (the nudge); default the shipped text. */
    BufferedTurnContinuation withPrompts(java.util.function.Function<UUID, ResolvedDirectives> source) {
        this.prompts = source == null ? ResolvedDirectives.DEFAULTS_FOR_AGENT : source;
        return this;
    }

    /** Where each pass's transcript comes from (the turn's own, carrying its round cap); default a plain one. */
    BufferedTurnContinuation withTranscripts(java.util.function.Supplier<ToolLoopTranscript> source) {
        this.transcripts = source;
        return this;
    }

    /**
     * {@code reply} followed by the continuation passes it needs: none for a reply that is an answer,
     * for a turn without tools, or when the reply did not end normally (the finish-reason handling has
     * dealt with it).
     */
    String apply(Run run, TurnContext context, String reply, Pass pass, Settle settle) {
        if (maxPasses <= 0 || !run.toolsOffered() || !context.reply().isComplete()) {
            return reply;
        }
        run.thread().passFinished(run.transcript());
        StringBuilder out = new StringBuilder(reply == null ? "" : reply);
        String segment = reply == null ? "" : reply;
        int callsAtPassStart = run.callsAtStart();
        boolean heuristic =
                behaviour.bool(
                        TurnContinuation.HEURISTIC_ENABLED_KEY,
                        context.scope(),
                        run.agentId(),
                        policy::heuristicEnabledByPlatform);
        for (int passes = 0; passes < maxPasses; passes++) {
            Decision decision =
                    policy.decide(
                            new Signals(
                                    segment,
                                    context.modelTraits().weakToolCaller(),
                                    context.approval() != null && context.approval().isParked(),
                                    passes,
                                    context.toolActivity().finishedCalls() - callsAtPassStart),
                            heuristic);
            if (!decision.proceed()) {
                log.debug(
                        "Agent {} session {}: no continuation pass after pass {} ({})",
                        run.agentId(),
                        run.sessionId(),
                        passes,
                        decision.reason());
                break;
            }
            log.info(
                    "Agent {} session {}: reply stopped at an unfinished step; continuation pass {}",
                    run.agentId(),
                    run.sessionId(),
                    passes + 1);
            TurnLifecycleEvents.unfinishedStepPass(context.stepEvents(), passes + 1, maxPasses);
            ContinuationThread.Request request =
                    run.thread()
                            .next(
                                    segment,
                                    context.scope()
                                            .get(
                                                    () ->
                                                            policy.nudge(
                                                                    decision.locale(),
                                                                    prompts.apply(run.agentId()))));
            ToolLoopTranscript transcript = transcripts.get();
            FinishReasonCapture finish = new FinishReasonCapture();
            callsAtPassStart = context.toolActivity().finishedCalls();
            try {
                segment = settle.apply(pass.run(request, transcript, finish), finish);
            } catch (RuntimeException e) {
                if (FastStreamRecovery.isPolicyStop(e)) {
                    throw e;
                }
                log.warn(
                        "Continuation pass {} failed for agent {} session {}",
                        passes + 1,
                        run.agentId(),
                        run.sessionId(),
                        e);
                context.reply().mark(Kind.INTERRUPTED);
                return out + AgentChatService.INTERRUPTED_REPLY_MARKER;
            }
            run.thread().passFinished(transcript);
            if (segment != null && !segment.isBlank()) {
                out.append("\n\n").append(segment);
            }
            if (!context.reply().isComplete()) {
                break;
            }
        }
        return out.toString();
    }
}
