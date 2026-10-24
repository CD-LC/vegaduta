package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.ChatAttachments.userSpec;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.isKnownThoughtSignatureBug;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.TurnReplyOutcome.Kind;
import com.agenticai.core.chat.FinishReason;
import com.agenticai.core.chat.FinishReasonAdvisor;
import com.agenticai.core.chat.FinishReasonCapture;
import com.agenticai.core.chat.LlmMetricsAdvisor;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.ToolLoopTranscript;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.chat.gateway.LlmGatewayContentPolicyViolationException;
import com.agenticai.core.guardrails.GuardrailViolationException;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

/**
 * The model calls of a buffered reasoning depth, moved out of {@link BufferedTurnRunner}
 * (extract-only split): the shared prompt shape, the tool-enabled call with its provider fallback
 * and Gemini thought_signature retry, the announce-without-action nudge, the L4 verifier-escalated
 * first draft, and the tool-log reads the runner's re-plan check uses. {@link BufferedTurnRunner}
 * keeps the turn shapes (verify-then-respond, plan-then-respond), threading and timeout.
 *
 * <p>ADR-0022 stage 3: the tenant arrives as the explicit {@link TurnContext}; none of these read
 * the ThreadLocal.
 */
final class BufferedTurnCalls {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final ChatTurnExecutor turns;
    private final ProviderErrorTranslator providerErrorTranslator;
    private final ToolCallLogService toolCallLogService;

    BufferedTurnCalls(
            ChatTurnExecutor turns,
            ProviderErrorTranslator providerErrorTranslator,
            ToolCallLogService toolCallLogService) {
        this.turns = turns;
        this.providerErrorTranslator = providerErrorTranslator;
        this.toolCallLogService = toolCallLogService;
    }

    /** The {@code app.chat.parity.*} settings this turn runs under. */
    ChatParityProperties parity() {
        return turns.parity;
    }

    /** Whether a bring-your-own {@code agent} is a weak tool caller (the delegated turn's counterpart of client selection). */
    boolean weakToolCaller(Agent agent) {
        return turns.continuation.isHeuristicProvider(agent.getProviderName());
    }

    /** The {@code app.chat.finish.*} policy this turn runs under. */
    ChatFinishProperties finishPolicy() {
        return turns.finish;
    }

    /**
     * What the person (or the calling agent) is told for a reply that is not an answer: the prompt-registry notice
     * for {@code agentId} and its tenant, see {@link ReplyFinish#notice}.
     */
    String finishNotice(UUID agentId, TurnReplyOutcome.State state) {
        return ReplyFinish.notice(turns.finish, state, turns.prompts.apply(agentId));
    }

    /** See {@link ChatTurnExecutor#collectToolEvidenceSince}. */
    String collectToolEvidenceSince(UUID agentId, String sessionId, Instant since) {
        return turns.collectToolEvidenceSince(agentId, sessionId, since);
    }

    /**
     * The one prompt shape every buffered reasoning depth runs, factored out of the three
     * byte-identical copies {@link #verifyThenRespond} and {@link #planThenRespond} used to carry
     * inline. {@code tools} may be empty - the {@code toolCallbacks} call is then omitted entirely
     * rather than passed an empty list, matching how {@code streamFast}'s tools-disabled retry
     * builds its prompt.
     */
    private ChatClient.CallResponseSpec bufferedCall(
            ChatClient client,
            UUID agentId,
            String sessionId,
            String systemPrompt,
            List<Message> messages,
            String userMessage,
            List<ToolCallback> tools,
            List<ChatAttachmentPayload> attachments,
            String experimentVariants,
            FinishReasonCapture capture,
            ToolLoopTranscript transcript) {
        ChatClient.ChatClientRequestSpec spec =
                client.prompt()
                        .system(systemPrompt)
                        .messages(messages)
                        .user(userSpec(userMessage, attachments));
        if (!tools.isEmpty()) {
            // The transcript receives the conversation after each tool round, for a continuation pass.
            // The same stable order the streamed path offers (see ChatTurnExecutor#stableToolOrder), so the
            // prompt-cache prefix and the model's tool list do not depend on which depth the agent runs at.
            spec =
                    spec.toolCallbacks(ChatTurnExecutor.stableToolOrder(tools))
                            .toolContext(Map.of(ToolLoopTranscript.TOOL_CONTEXT_KEY, transcript));
        }
        return advised(spec, agentId, sessionId, experimentVariants, capture).call();
    }

    /** What every buffered model call carries: attribution, the experiment variant, the finish reason. */
    static ChatClient.ChatClientRequestSpec advised(
            ChatClient.ChatClientRequestSpec spec,
            UUID agentId,
            String sessionId,
            String experimentVariants,
            FinishReasonCapture capture) {
        return spec.advisors(
                        a -> {
                            a.param(LlmMetricsAdvisor.AGENT_ID_PARAM, agentId)
                                    .param(LlmMetricsAdvisor.SESSION_ID_PARAM, sessionId);
                            // The provider's stop reason (max_tokens, safety, ...): .content()
                            // discards the response metadata that carries it.
                            if (capture != null) {
                                a.advisors(new FinishReasonAdvisor(capture));
                            }
                            // #1102 (direct feedback: opaque "value cannot be null" 400 on every
                            // single chat turn) - resolveExperimentVariants() returns null
                            // whenever a tenant has no active feature-flag experiment (the
                            // common case), but Spring AI's DefaultChatClient$DefaultAdvisorSpec
                            // #param(k, v) asserts its value argument non-null and throws exactly
                            // that generic message - unconditionally passing experimentVariants
                            // through crashed every turn, not just ones under an experiment.
                            // LlmMetricsAdvisor#paramAsString already treats an absent key the
                            // same as an explicitly-null one (chatClientRequest.context().get
                            // returns null either way), so simply omitting the .param() call when
                            // there's nothing to record is behaviorally identical to before, minus
                            // the crash.
                            if (experimentVariants != null) {
                                a.param(
                                        LlmMetricsAdvisor.EXPERIMENT_VARIANTS_PARAM,
                                        experimentVariants);
                            }
                        });
    }

    /**
     * The tool-enabled turn every buffered reasoning depth runs - CAREFUL/RIGOROUS via {@link
     * #verifyThenRespond}, PLANNED via {@link #planThenRespond} - with the recovery layers {@link
     * #streamFast} has: provider fallback ({@link #callWithProviderFallback}), the visible
     * answer-without-tools when the provider refuses the tool-call history ({@link
     * ThoughtSignatureDegrade}; inside the executor handed to {@code callWithProviderFallback}, so it
     * runs per client), and the continuation of a reply that stops at an announced step ({@link
     * BufferedTurnContinuation}). Nothing can have streamed on a buffered depth, so no "only if nothing
     * has streamed yet" gate is needed.
     */
    String callBufferedTurn(
            Agent agent,
            UUID agentId,
            String sessionId,
            ChatClient chatClient,
            String systemPrompt,
            List<Message> messages,
            String userMessage,
            List<ToolCallback> tools,
            List<ChatAttachmentPayload> attachments,
            TurnContext context,
            String experimentVariants) {
        return callBufferedTurnOn(
                agent,
                agentId,
                sessionId,
                chatClient,
                systemPrompt,
                messages,
                userMessage,
                tools,
                attachments,
                context,
                experimentVariants);
    }

    /**
     * L4 first-draft call. Only the first draft of a turn can escalate (the verifyThenRespond and
     * planThenRespond entry calls); retries and checker passes never re-escalate. Returns the
     * client that produced the kept answer so every later pass (consistency check, Reflexion,
     * outbound guardrails, re-plan retry) runs on the model that wrote the answer rather than on
     * the weaker cheap one. Router null/inactive: exactly the pre-existing single call, same
     * client.
     */
    BufferedTurnRunner.Draft callFirstDraft(
            Agent agent,
            UUID agentId,
            String sessionId,
            ChatClient chatClient,
            String systemPrompt,
            List<Message> messages,
            String userMessage,
            List<ToolCallback> tools,
            List<ChatAttachmentPayload> attachments,
            TurnContext context,
            String experimentVariants) {
        VerifierEscalationRouter router = escalationRouter;
        if (router == null || !router.isActive()) {
            return new BufferedTurnRunner.Draft(
                    callBufferedTurn(
                            agent,
                            agentId,
                            sessionId,
                            chatClient,
                            systemPrompt,
                            messages,
                            userMessage,
                            tools,
                            attachments,
                            context,
                            experimentVariants),
                    chatClient);
        }
        Instant startedAt = Instant.now();
        String answer =
                callBufferedTurn(
                        agent,
                        agentId,
                        sessionId,
                        chatClient,
                        systemPrompt,
                        messages,
                        userMessage,
                        tools,
                        attachments,
                        context,
                        experimentVariants);
        // How the cheap draft ended; put back if the escalated re-run is not the answer kept.
        final TurnReplyOutcome.State cheapOutcome = context.reply().snapshot();
        try {
            Optional<Supplier<ChatClient>> target = router.escalationFor(agentId, chatClient);
            if (target.isEmpty()) {
                return new BufferedTurnRunner.Draft(answer, chatClient);
            }
            List<ToolCallLog> turnCalls =
                    toolCallLogService.findByAgentIdAndSessionId(agentId, sessionId).stream()
                            .filter(
                                    e ->
                                            e.getStartedAt() != null
                                                    && e.getStartedAt().isAfter(startedAt))
                            .toList();
            int toolErrors =
                    (int)
                            turnCalls.stream()
                                    .filter(
                                            e ->
                                                    e.getErrorMessage() != null
                                                            && !e.getErrorMessage().isBlank())
                                    .count();
            Optional<VerifierEscalationRouter.Reason> reason =
                    router.shouldEscalate(
                            router.inspect(
                                    answer,
                                    BufferedTurnRunner.turnOutputSchema(agent, userMessage),
                                    toolErrors,
                                    turnCalls.size(),
                                    agentId));
            if (reason.isPresent()
                    && !router.replaySafe(
                            turnCalls.stream().map(ToolCallLog::getToolName).toList(), agentId)) {
                // Re-running would repeat a possibly mutating tool call (send, create, delete...).
                router.recordSkippedForSideEffects(agentId, reason.get());
                return new BufferedTurnRunner.Draft(answer, chatClient);
            }
            router.recordDecision(agentId, reason, answer == null ? 0 : answer.length());
            if (reason.isEmpty()) {
                return new BufferedTurnRunner.Draft(answer, chatClient);
            }
            log.info(
                    "Escalating agent {} session {} to primary model (reason {})",
                    agentId,
                    sessionId,
                    reason.get());
            ChatClient strong = target.get().get();
            Instant strongStartedAt = Instant.now();
            String better =
                    callBufferedTurnOn(
                            agent,
                            agentId,
                            sessionId,
                            strong,
                            systemPrompt,
                            messages,
                            userMessage,
                            tools,
                            attachments,
                            context,
                            experimentVariants);
            // The kept answer's attempt starts at strongStartedAt, so the PLANNED re-plan check
            // does
            // not mistake the DISCARDED cheap draft's tool errors for a failure of this answer.
            if (better == null || better.isBlank()) {
                context.reply().restore(cheapOutcome);
                return new BufferedTurnRunner.Draft(answer, chatClient);
            }
            return new BufferedTurnRunner.Draft(better, strong, strongStartedAt);
        } catch (RuntimeException e) {
            // Escalation is an improvement on an answer this turn already has; never let it
            // destroy that answer (same fail-open posture as the re-plan retry).
            log.warn(
                    "Verifier escalation failed for agent {} session {} - keeping the cheap answer",
                    agentId,
                    sessionId,
                    e);
            context.reply().restore(cheapOutcome);
            return new BufferedTurnRunner.Draft(answer, chatClient);
        }
    }

    /** L4 (default null = feature unwired). See {@link VerifierEscalationRouter}. */
    private volatile VerifierEscalationRouter escalationRouter;

    void useEscalationRouter(VerifierEscalationRouter router) {
        this.escalationRouter = router;
    }

    private String callBufferedTurnOn(
            Agent agent,
            UUID agentId,
            String sessionId,
            ChatClient chatClient,
            String systemPrompt,
            List<Message> messages,
            String userMessage,
            List<ToolCallback> tools,
            List<ChatAttachmentPayload> attachments,
            TurnContext context,
            String experimentVariants) {
        // A new reply attempt: how an earlier attempt of this turn ended does not apply to this one.
        context.reply().begin();
        Instant attemptStartedAt = Instant.now();
        FinishReasonCapture finish = new FinishReasonCapture();
        ToolLoopTranscript transcript = turns.newTranscript(context, agentId);
        int callsAtStart = context.toolActivity().finishedCalls();
        // The client that actually answered (the fallback provider, when the primary failed): any
        // follow-up pass must go to the same model, not start a second provider fallback.
        AtomicReference<ChatClient> answeredBy = new AtomicReference<>(chatClient);
        String text =
                turns.callWithProviderFallback(
                        context.tenantId(),
                        agent,
                        chatClient,
                        client -> {
                            answeredBy.set(client);
                            try {
                                return bufferedCall(
                                                client,
                                                agentId,
                                                sessionId,
                                                systemPrompt,
                                                messages,
                                                userMessage,
                                                tools,
                                                attachments,
                                                experimentVariants,
                                                finish,
                                                transcript)
                                        .content();
                            } catch (RuntimeException e) {
                                // Guardrail/step-limit failures never came from the provider, so they are
                                // not classified at all here - same exclusion callWithProviderFallback and
                                // streamFast's onErrorMap already apply, and it keeps translate() (which
                                // wraps every Throwable unconditionally) off exceptions it would mislabel.
                                if (tools.isEmpty()
                                        || e instanceof ToolStepLimitExceededException
                                        || e instanceof GuardrailViolationException
                                        || e instanceof LlmGatewayContentPolicyViolationException) {
                                    throw e;
                                }
                                // Same classify-then-decide shape callWithProviderFallback uses, and the
                                // same null tolerance for a mocked translator.
                                UpstreamProviderException classified =
                                        e instanceof UpstreamProviderException upstream
                                                ? upstream
                                                : providerErrorTranslator.translate(
                                                        e, agent.getProviderName());
                                if (classified == null
                                        || !turns.resilience.isThoughtSignatureDegrade()
                                        || !isKnownThoughtSignatureBug(classified)) {
                                    throw e;
                                }
                                return ThoughtSignatureDegrade.answerBuffered(
                                        turns, agent, context, agentId, sessionId, attemptStartedAt, userMessage,
                                        userText ->
                                                bufferedCall(
                                                                client, agentId, sessionId, systemPrompt,
                                                                messages, userText, List.of(), attachments,
                                                                experimentVariants, finish, null)
                                                        .content());
                            }
                        },
                        // Failing over replays the turn from the start, tools included: not after a
                        // tool with side effects ran (see FailoverReplayGate).
                        context.toolActivity(),
                        context.stepEvents());
        String settled =
                settleFinish(
                        text,
                        finish,
                        answeredBy.get(),
                        agentId,
                        sessionId,
                        systemPrompt,
                        messages,
                        userMessage,
                        tools,
                        experimentVariants,
                        context,
                        attemptStartedAt);
        // One continuation policy for every depth (see BufferedTurnContinuation): the same decision and the
        // same replay of the real conversation the streamed path uses.
        ChatClient answerer = answeredBy.get();
        return new BufferedTurnContinuation(turns.continuation, turns.continuationPasses(context))
                .withTranscripts(() -> turns.newTranscript(context, agentId))
                .withPrompts(forAgent -> turns.prompts.apply(forAgent))
                .withBehaviour(turns.behaviour)
                .apply(
                        new BufferedTurnContinuation.Run(
                                agentId,
                                sessionId,
                                new ContinuationThread(systemPrompt, messages, userMessage, attachments),
                                transcript,
                                callsAtStart,
                                !tools.isEmpty()),
                        context,
                        settled,
                        BufferedTurnContinuation.modelPass(answerer, agentId, sessionId, tools, experimentVariants),
                        (more, passFinish) ->
                                settleFinish(
                                        more,
                                        passFinish,
                                        answerer,
                                        agentId,
                                        sessionId,
                                        systemPrompt,
                                        messages,
                                        userMessage,
                                        tools,
                                        experimentVariants,
                                        context,
                                        attemptStartedAt));
    }

    /**
     * Acts on the provider's finish reason of a buffered reply, the blocking counterpart of {@code
     * FastStreamTurn#settleFinish}. Cut off at the output limit: more passes continue the reply (up
     * to {@code app.chat.finish.max-continuations}, no tools offered) and the pieces are joined.
     * Stopped by the provider's safety system: the turn is marked refused. An unusable tool call: one
     * more pass with the tools on offer, only when no tool ran this turn. The outcome is recorded on
     * the turn's {@link TurnReplyOutcome}; the text returned is the model's own, never a notice.
     */
    private String settleFinish(
            String firstText,
            FinishReasonCapture finish,
            ChatClient client,
            UUID agentId,
            String sessionId,
            String systemPrompt,
            List<Message> messages,
            String userMessage,
            List<ToolCallback> tools,
            String experimentVariants,
            TurnContext context,
            Instant attemptStartedAt) {
        // The switch and the two limits as they apply to this tenant and agent (the catalog; else app.chat.finish.*).
        TurnFinishLimits limits = turns.finishLimits(context, agentId);
        TurnReplyOutcome reply = context.reply();
        String text = firstText == null ? "" : firstText;
        int lengthPasses = 0;
        int toolCallRetries = 0;
        while (limits.enabled()) {
            switch (finish.reason()) {
                case SAFETY -> {
                    reply.mark(Kind.REFUSED, finish.raw());
                    return text;
                }
                case LENGTH -> {
                    if (lengthPasses >= limits.maxContinuations()) {
                        reply.mark(Kind.TRUNCATED);
                        return text;
                    }
                    lengthPasses++;
                    log.info(
                            "Agent {} session {}: the provider ended the reply with {}; continuation pass {} of {}",
                            agentId,
                            sessionId,
                            finish.raw(),
                            lengthPasses,
                            limits.maxContinuations());
                    TurnLifecycleEvents.finishPass(
                            context.stepEvents(), true, lengthPasses - 1, limits.maxContinuations());
                    String more =
                            finishPass(
                                    client,
                                    agentId,
                                    sessionId,
                                    systemPrompt,
                                    messages,
                                    userMessage,
                                    text,
                                    context.scope()
                                            .get(
                                                    () ->
                                                            turns.prompts
                                                                    .apply(agentId)
                                                                    .text(NudgeTexts.REPLY_CUT_OFF)),
                                    // Prose only: tools on offer would let a cut-off answer rerun
                                    // the calls that produced it.
                                    List.of(),
                                    experimentVariants,
                                    finish,
                                    reply,
                                    Kind.TRUNCATED);
                    if (more == null) {
                        return text;
                    }
                    text = text + more;
                }
                case MALFORMED_TOOL_CALL -> {
                    if (toolCallRetries >= limits.maxToolCallRetries()
                            || !noToolRanSince(agentId, sessionId, attemptStartedAt)) {
                        reply.mark(Kind.MALFORMED_TOOL_CALL);
                        return text;
                    }
                    toolCallRetries++;
                    log.info(
                            "Agent {} session {}: the provider ended the reply with {}; tool-call retry {} of {}",
                            agentId,
                            sessionId,
                            finish.raw(),
                            toolCallRetries,
                            limits.maxToolCallRetries());
                    TurnLifecycleEvents.finishPass(
                            context.stepEvents(), false, toolCallRetries - 1, limits.maxToolCallRetries());
                    String more =
                            finishPass(
                                    client,
                                    agentId,
                                    sessionId,
                                    systemPrompt,
                                    messages,
                                    userMessage,
                                    text,
                                    context.scope()
                                            .get(
                                                    () ->
                                                            turns.prompts
                                                                    .apply(agentId)
                                                                    .text(NudgeTexts.RETRY_TOOL_CALL)),
                                    tools,
                                    experimentVariants,
                                    finish,
                                    reply,
                                    Kind.MALFORMED_TOOL_CALL);
                    if (more == null) {
                        return text;
                    }
                    text = text + more;
                }
                default -> {
                    return text;
                }
            }
        }
        return text;
    }

    /**
     * One follow-up pass. {@code null} means it failed and the turn has been marked {@code
     * onFailure}: the text so far is kept, and the person is told it is unfinished. A policy stop
     * (step ceiling, guardrail, content policy) is not a failed pass; it propagates.
     */
    private String finishPass(
            ChatClient client,
            UUID agentId,
            String sessionId,
            String systemPrompt,
            List<Message> messages,
            String userMessage,
            String replySoFar,
            String nudge,
            List<ToolCallback> passTools,
            String experimentVariants,
            FinishReasonCapture finish,
            TurnReplyOutcome reply,
            Kind onFailure) {
        ReplyFinish.PassPrompt prompt = ReplyFinish.passPrompt(messages, userMessage, replySoFar, nudge);
        ChatClient.ChatClientRequestSpec spec =
                client.prompt().system(systemPrompt).messages(prompt.messages()).user(prompt.user());
        if (!passTools.isEmpty()) {
            spec = spec.toolCallbacks(ChatTurnExecutor.stableToolOrder(passTools));
        }
        try {
            String content =
                    advised(spec, agentId, sessionId, experimentVariants, finish).call().content();
            return content == null ? "" : content;
        } catch (RuntimeException e) {
            if (FastStreamRecovery.isPolicyStop(e)) {
                throw e;
            }
            log.warn(
                    "Finish-reason pass failed for agent {} session {} - keeping the text so far",
                    agentId,
                    sessionId,
                    e);
            reply.mark(onFailure);
            return null;
        }
    }

    /** True when the tool-call log shows no tool call for this session since {@code since}. */
    private boolean noToolRanSince(UUID agentId, String sessionId, Instant since) {
        try {
            return turns.collectToolEvidenceSince(agentId, sessionId, since) == null;
        } catch (RuntimeException e) {
            log.warn(
                    "Agent {} session {}: could not tell whether a tool ran this turn; not retrying the tool call",
                    agentId,
                    sessionId,
                    e);
            return false;
        }
    }

    /**
     * The cheapest reliable "did this plan just break" signal available - see {@link
     * TaskPlanningService}'s javadoc for why this deliberately isn't a separate LLM classification
     * call after every tool result. {@code toolCallLogService} already records every tool call
     * (success or failure) for this session via {@code LoggingToolCallback}; filtering to {@code
     * since} (captured right before this turn's {@code .call()}) isolates THIS turn's tool calls
     * from the session's full history.
     */
    String firstToolFailureSince(UUID agentId, String sessionId, Instant since) {
        return toolCallLogService.findByAgentIdAndSessionId(agentId, sessionId).stream()
                .filter(
                        entry ->
                                entry.getStartedAt().isAfter(since)
                                        && entry.getErrorMessage() != null
                                        && !entry.getErrorMessage().isBlank())
                .findFirst()
                .map(entry -> entry.getToolName() + ": " + entry.getErrorMessage())
                .orElse(null);
    }
}
