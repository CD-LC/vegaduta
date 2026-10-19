package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.ChatAttachments.userSpec;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.isKnownThoughtSignatureBug;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.chat.LlmMetricsAdvisor;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.chat.gateway.LlmGatewayContentPolicyViolationException;
import com.agenticai.core.guardrails.GuardrailViolationException;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
            String experimentVariants) {
        ChatClient.ChatClientRequestSpec spec =
                client.prompt()
                        .system(systemPrompt)
                        .messages(messages)
                        .user(userSpec(userMessage, attachments));
        if (!tools.isEmpty()) {
            spec = spec.toolCallbacks(tools);
        }
        return spec.advisors(
                        a -> {
                            a.param(LlmMetricsAdvisor.AGENT_ID_PARAM, agentId)
                                    .param(LlmMetricsAdvisor.SESSION_ID_PARAM, sessionId);
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
                        })
                .call();
    }

    /**
     * The tool-enabled turn every buffered reasoning depth runs - CAREFUL/RIGOROUS via {@link
     * #verifyThenRespond}, PLANNED via {@link #planThenRespond} - with BOTH recovery layers {@link
     * #streamFast} has, instead of only one.
     *
     * <p><b>Reasoning-depth audit, 2026-09-17.</b> These paths already had provider fallback
     * ({@link #callWithProviderFallback}, #848) but never had the known Spring AI Gemini {@code
     * thought_signature} workaround - a single same-provider, same-model retry with tools disabled
     * (spring-projects/spring-ai#5466, see {@link #isKnownThoughtSignatureBug} and the {@code
     * onErrorResume} in {@code streamFast} for the bug itself). That gap is directly user-visible:
     * a Gemini tool-calling agent whose turns FAST silently rescues hard-fails the moment the
     * customer switches it to CAREFUL, RIGOROUS or PLANNED - same provider, same model, same tools,
     * only the depth changed. Since the hosted pool is Gemini-first, that is the common shape of
     * "the agent stopped answering when I picked a reasoning depth".
     *
     * <p>The retry lives INSIDE the executor handed to {@code callWithProviderFallback}, so it runs
     * per client: the cheap tools-disabled retry is tried first on the primary (no credential
     * lookup, no second provider - streamFast's ordering), and if the fallback provider is reached
     * it gets its own retry too. Unlike streamFast there is no "only if nothing has streamed yet"
     * gate - nothing CAN have streamed on a buffered depth, the whole answer is emitted at the end
     * or not at all.
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
        Instant callStartedAt = Instant.now();
        String answer =
                callBufferedTurnOn(
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
        // Announce-without-action guard: a small model can reply "I will now retrieve... one
        // moment" with ZERO tool calls, and that text would be shown as the finished answer.
        // Re-run once, with a nudge, only when tools exist and none ran. Fail-open on error.
        if (!tools.isEmpty()
                && ChatPromptDirectives.isUnfulfilledAnnouncement(answer)
                && turns.collectToolEvidenceSince(agentId, sessionId, callStartedAt) == null) {
            log.warn(
                    "Agent {} session {} announced work without calling a tool - re-running once with a nudge",
                    agentId,
                    sessionId);
            try {
                String retry =
                        callBufferedTurnOn(
                                agent,
                                agentId,
                                sessionId,
                                chatClient,
                                systemPrompt,
                                messages,
                                userMessage + ChatPromptDirectives.CALL_TOOL_NOW_NUDGE,
                                tools,
                                attachments,
                                context,
                                experimentVariants);
                if (retry != null && !retry.isBlank()) {
                    return retry;
                }
            } catch (RuntimeException e) {
                log.warn(
                        "Announce-nudge retry failed for agent {} session {} - keeping first reply",
                        agentId,
                        sessionId,
                        e);
            }
        }
        return answer;
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
                                    turnCalls.size()));
            if (reason.isPresent()
                    && !router.replaySafe(
                            turnCalls.stream().map(ToolCallLog::getToolName).toList())) {
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
            return better == null || better.isBlank()
                    ? new BufferedTurnRunner.Draft(answer, chatClient)
                    : new BufferedTurnRunner.Draft(better, strong, strongStartedAt);
        } catch (RuntimeException e) {
            // Escalation is an improvement on an answer this turn already has; never let it
            // destroy that answer (same fail-open posture as the re-plan retry).
            log.warn(
                    "Verifier escalation failed for agent {} session {} - keeping the cheap answer",
                    agentId,
                    sessionId,
                    e);
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
        return turns.callWithProviderFallback(
                context.tenantId(),
                agent,
                chatClient,
                client -> {
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
                                        experimentVariants)
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
                        if (classified == null || !isKnownThoughtSignatureBug(classified)) {
                            throw e;
                        }
                        log.warn(
                                "Provider {} failed for agent {} session {} with the known Spring AI Gemini "
                                        + "thought_signature bug (spring-projects/spring-ai#5466) on a buffered "
                                        + "reasoning depth - retrying once on the same provider/model with tools "
                                        + "disabled for this turn",
                                agent.getProviderName(),
                                agentId,
                                sessionId);
                        return bufferedCall(
                                        client,
                                        agentId,
                                        sessionId,
                                        systemPrompt,
                                        messages,
                                        userMessage,
                                        List.of(),
                                        attachments,
                                        experimentVariants)
                                .content();
                    }
                });
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
