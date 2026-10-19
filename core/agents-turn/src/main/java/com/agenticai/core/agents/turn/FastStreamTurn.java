package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.AgentChatService.EMPTY_REPLY_FALLBACK;
import static com.agenticai.core.agents.turn.ChatAttachments.userSpec;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.containsFabricatedGenerationUrl;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.turnjournal.TurnJournalGateway;
import com.agenticai.core.agents.turnjournal.TurnRecorder;
import com.agenticai.core.chat.LlmMetricsAdvisor;
import com.agenticai.core.chat.gateway.LlmGatewayContentPolicyViolationException;
import com.agenticai.core.guardrails.GuardrailViolationException;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

/**
 * One token-streaming {@link ReasoningDepth#FAST} turn, moved out of {@link
 * ChatTurnExecutor#streamFast} (extract-only split): the primary provider stream, its
 * completion-time audit and persistence, and the empty-reply fallback. The recovery ladder for a
 * failed stream (Gemini thought_signature retry, fallback provider, half-response close) lives in
 * {@link FastStreamRecovery}, which works on this object's per-turn state.
 *
 * <p>A new instance per turn; it holds that turn's values (ADR-0022 stage 3: the tenant comes from
 * the explicit {@link TurnContext}, never from the ThreadLocal) and reads its collaborators from
 * the owning {@link ChatTurnExecutor}.
 */
final class FastStreamTurn {

    final ChatTurnExecutor owner;
    final TurnContext context;
    final Agent agent;
    final UUID agentId;
    final String sessionId;
    final ChatClient chatClient;
    final String systemPrompt;
    final List<Message> history;
    final String userMessage;
    final List<ToolCallback> tools;
    final List<ChatAttachmentPayload> attachments;
    final String experimentVariants;
    final AgentStepEventEmitter stepEvents;

    /** SEC-004: the scoped memory id bound by the caller; sessionId stays raw for events/metrics. */
    final String memorySessionId;

    /** ADR-0020: NOOP unless app.turn-journal.enabled=true; never throws into the turn. */
    final TurnRecorder turn;

    final List<ToolCallback> journaledTools;
    final StringBuilder accumulated = new StringBuilder();

    /**
     * Fabricated-generation-URL guard (2026-08-10): live incident, confirmed twice despite
     * TOOL_TRUST_DIRECTIVE already forbidding it in the system prompt - a hosted model still
     * emitted a fake oaidalleapiprodscus.blob.core.windows.net URL with an invented SAS signature
     * (the target 404s/auth-fails - a real one could only ever come from an actual generation-tool
     * result, never from the model's own knowledge). Prompt instructions alone are not reliably
     * stopping this, so this is the deterministic backstop - see containsFabricatedGenerationUrl's
     * own javadoc for exactly what it catches and its known limitation (it cannot un-send tokens
     * already streamed live to the client this one time; it only stops the lie from persisting
     * into session history and feeds observability).
     */
    final Instant fabricationCheckStartedAt;

    /** What the model is sent besides the tools: system prompt, history, message, attachments. */
    record Prompt(
            String systemPrompt,
            List<Message> history,
            String userMessage,
            List<ChatAttachmentPayload> attachments) {}

    FastStreamTurn(
            ChatTurnExecutor owner,
            TurnContext context,
            Agent agent,
            UUID agentId,
            String sessionId,
            ChatClient chatClient,
            Prompt prompt,
            List<ToolCallback> tools,
            String experimentVariants,
            AgentStepEventEmitter stepEvents) {
        String userMessage = prompt.userMessage();
        List<ChatAttachmentPayload> attachments = prompt.attachments();
        this.owner = owner;
        this.context = context;
        this.agent = agent;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.chatClient = chatClient;
        this.systemPrompt = prompt.systemPrompt();
        this.history = prompt.history();
        this.userMessage = userMessage;
        this.tools = tools;
        this.attachments = attachments;
        this.experimentVariants = experimentVariants;
        this.stepEvents = stepEvents;
        // Statement order is the original streamFast's: bound memory id, journal, wrapped tools,
        // then the fabrication-check start instant.
        this.memorySessionId = SessionMemoryService.boundMemorySessionId();
        this.turn = TurnJournalGateway.begin(agentId);
        turn.intake(
                userMessage,
                attachments == null ? 0 : attachments.size(),
                tools == null ? 0 : tools.size());
        this.journaledTools = turn.wrapTools(tools);
        this.fabricationCheckStartedAt = Instant.now();
    }

    /**
     * The original {@code chat()} stream from before Stage A/B existed, with the same operators in
     * the same order (the three retry branches are {@link FastStreamRecovery}'s methods and the
     * prompt is built by {@link #content}), so {@link ReasoningDepth#FAST} (every agent's default)
     * is unaffected by that build plan.
     */
    Flux<String> stream() {
        FastStreamRecovery recovery = new FastStreamRecovery(this);
        turn.modelCall(String.valueOf(agent.getProviderName()), agent.getModel(), "primary");
        Flux<String> stream =
                // Direct feedback: "an option to see the llm errors and success logs...evaluate
                // the price cost per call or a task" - LlmMetricsAdvisor (registered on every
                // ChatClient this app builds) reads the agent/session params from the request
                // context to attribute the LlmCallLog row it writes back to this agent/
                // conversation; without them, the row still gets written (cost, tokens,
                // success/error, latency) just with a null agent/session.
                content(chatClient, true)
                        // Genuine mid-stream idle timeout - see ChatTurnExecutor#streamIdleTimeout
                        // and app.llm.http.stream-idle-timeout's comment in application.yml.
                        // Resets on every emitted token; fires
                        // java.util.concurrent.TimeoutException (already a recognized category
                        // in ProviderErrorTranslator.translate, handled identically to a
                        // transport-level timeout) the first time a gap between tokens exceeds
                        // this duration, independent of how long the stream has run in total.
                        .timeout(owner.streamIdleTimeout)
                        // Translates provider failures (billing, rate limit, invalid model, auth)
                        // into a clean UpstreamProviderException instead of letting Spring AI's
                        // generic RuntimeException fall through as an opaque 500. If the failure
                        // happens before any tokens are emitted (the common case: auth/billing/
                        // model errors reject the request immediately), GlobalExceptionHandler
                        // still gets a chance to map it to a proper status before the response is
                        // committed. If it happens mid-stream after tokens already flowed, the
                        // HTTP status is already committed and the SSE stream just ends - that's
                        // an inherent limit of HTTP streaming, not something this fixes.
                        //
                        // Guardrail exceptions (Autonomy/Guardrails Strategy Stage A) are
                        // deliberately excluded from provider-error translation: they originate
                        // from GuardedToolCallback, not from a failed upstream provider call, and
                        // ProviderErrorTranslator.translate() has no "I don't recognize this"
                        // fallback - it wraps every Throwable into an UpstreamProviderException
                        // unconditionally, which would relabel "tool step limit exceeded" as "the
                        // LLM provider call failed" and mislead anyone debugging it.
                        .onErrorMap(
                                e ->
                                        e instanceof ToolStepLimitExceededException
                                                        || e instanceof GuardrailViolationException
                                                        || e
                                                                instanceof
                                                                LlmGatewayContentPolicyViolationException
                                                ? e
                                                : owner.providerErrorTranslator.translate(
                                                        e, agent.getProviderName()))
                        .onErrorResume(recovery::retryWithoutToolsOnThoughtSignatureBug)
                        .onErrorResume(recovery::retryOnFallbackProvider)
                        .onErrorResume(recovery::closeHalfResponse)
                        // Empty-response fix (2026-08-10): distinct from the "half response" case
                        // above - this is a stream that completes NORMALLY, no error at all, but
                        // the provider emitted zero real tokens (a genuinely blank completion - a
                        // live report showed exactly this: an empty assistant bubble, 0 tool
                        // calls, no error surfaced anywhere). Nothing upstream throws in this
                        // shape, so onErrorResume above never runs; this needs its own check on
                        // the SUCCESS path. Flux.defer + concatWith only evaluates/subscribes
                        // after the upstream Flux completes, by which point every real token has
                        // already been appended to accumulated - never fires while content is
                        // still arriving, and adds nothing when accumulated is non-empty (the
                        // overwhelmingly common case stays byte-for-byte identical to before this
                        // operator existed).
                        .concatWith(
                                Flux.defer(
                                        () -> {
                                            if (!accumulated.isEmpty()) {
                                                return Flux.empty();
                                            }
                                            ChatTurnExecutor.log.warn(
                                                    "Agent {} session {} completed with a genuinely"
                                                            + " empty response - no tokens, no error",
                                                    agentId,
                                                    sessionId);
                                            return Flux.just(EMPTY_REPLY_FALLBACK);
                                        }));
        // Turn-loss fix (2026-08-07): the complete-path body below is the old doOnComplete body
        // unchanged; withAssistantTurnPersistence adds the once-guard around it plus the
        // error/cancel partial-persist. Everything else about this stream (operators above, error
        // propagation to the caller) is untouched.
        if (owner.maxContinuations > 0 && tools != null && !tools.isEmpty()) {
            // Evaluated only after the primary pass completes normally (concatWith), by which time
            // every token of it is already in {@code accumulated}.
            stream = stream.concatWith(Flux.defer(() -> continueIfUnfinished(0, 0, Instant.now())));
        }
        if (turn.active()) {
            stream =
                    stream.doOnError(
                                    e -> turn.commit(null, "error:" + e.getClass().getSimpleName()))
                            .doOnCancel(() -> turn.commit(null, "cancelled"));
        }
        return owner.withAssistantTurnPersistence(
                stream, agentId, sessionId, memorySessionId, accumulated, this::onCompleteFullAnswer);
    }

    /**
     * Keeps a task going when the model stops at "let me do X" without doing it: the last pass's
     * reply is checked by {@link TurnContinuation}, and if it reads as unfinished the model is
     * called again with what was said and what the tools returned plus a nudge to act. Bounded by
     * {@code app.chat.continuation.max-passes}; stops when a pass made no tool call (no progress),
     * when the reply is a question, a summary or an approval wait, or on any error (the earlier
     * output is already delivered and persisted).
     *
     * @param passes continuation passes run so far
     * @param segmentStart where the previous pass's text starts in {@link #accumulated}
     * @param passStartedAt when the previous pass began, to find its tool calls
     */
    private Flux<String> continueIfUnfinished(int passes, int segmentStart, Instant passStartedAt) {
        if (passes >= owner.maxContinuations) {
            return Flux.empty();
        }
        String segment = accumulated.substring(Math.min(segmentStart, accumulated.length()));
        if (!TurnContinuation.looksUnfinished(segment)) {
            return Flux.empty();
        }
        String evidence;
        try {
            evidence =
                    context.scope()
                            .get(
                                    () ->
                                            owner.collectToolEvidenceSince(
                                                    agentId, sessionId, passStartedAt));
        } catch (RuntimeException e) {
            return Flux.empty();
        }
        if (passes > 0 && evidence == null) {
            ChatTurnExecutor.log.info(
                    "Agent {} session {}: continuation pass {} made no tool call; stopping",
                    agentId,
                    sessionId,
                    passes);
            return Flux.empty();
        }
        ChatTurnExecutor.log.info(
                "Agent {} session {}: reply stopped at an unfinished step; continuation pass {}",
                agentId,
                sessionId,
                passes + 1);
        turn.guard("continuation", "pass-" + (passes + 1));
        String nudge = TurnContinuation.NUDGE;
        if (evidence != null) {
            String shown = evidence.length() > 4000 ? evidence.substring(0, 4000) + "..." : evidence;
            nudge = "Tool results so far:\n" + shown + "\n\n" + nudge;
        }
        List<Message> next = new java.util.ArrayList<>(history);
        next.add(new org.springframework.ai.chat.messages.UserMessage(userMessage));
        next.add(new org.springframework.ai.chat.messages.AssistantMessage(segment));
        int nextStart = accumulated.length() + 2;
        Instant started = Instant.now();
        String nudgeFinal = nudge;
        // The previous pass completes on an HTTP-client thread that holds no TenantContext, and
        // concatWith(Flux.defer(...)) subscribes this pass from there. Subscribe it in the turn's
        // captured scope so the advisors (LlmMetricsAdvisor, RlTrajectoryAdvisor) see the tenant and
        // their llm_call_log / rl_episode writes land in the tenant schema instead of the fail-closed
        // "tenant_context_missing" one (AGENTS.md rule 12).
        Flux<String> pass =
                TenantScopedFlux.subscribeInScope(
                                context.scope(),
                                () ->
                                        chatClient
                                                .prompt()
                                                .system(systemPrompt)
                                                .messages(next)
                                                .user(nudgeFinal)
                                                .toolCallbacks(ChatTurnExecutor.stableToolOrder(journaledTools))
                                                .advisors(
                                                        a ->
                                                                a.param(LlmMetricsAdvisor.AGENT_ID_PARAM, agentId)
                                                                        .param(LlmMetricsAdvisor.SESSION_ID_PARAM, sessionId))
                                                .stream()
                                                .content())
                        .timeout(owner.streamIdleTimeout)
                        .onErrorResume(
                                e -> {
                                    ChatTurnExecutor.log.warn(
                                            "Continuation pass failed for agent {} session {}",
                                            agentId,
                                            sessionId,
                                            e);
                                    return Flux.empty();
                                });
        return Flux.just("\n\n")
                .concatWith(pass)
                .concatWith(Flux.defer(() -> continueIfUnfinished(passes + 1, nextStart, started)));
    }

    /**
     * The prompt every FAST attempt sends: the primary call and the fallback provider's offer the
     * turn's (journaled, stably ordered) tools, the thought_signature retry passes {@code false}
     * so no {@code toolCallbacks} call is made at all.
     */
    Flux<String> content(ChatClient client, boolean offerTools) {
        ChatClient.ChatClientRequestSpec spec =
                client.prompt()
                        .system(systemPrompt)
                        .messages(history)
                        .user(userSpec(userMessage, attachments));
        if (offerTools) {
            spec = spec.toolCallbacks(ChatTurnExecutor.stableToolOrder(journaledTools));
        }
        return spec.advisors(
                        a -> {
                            a.param(LlmMetricsAdvisor.AGENT_ID_PARAM, agentId)
                                    .param(LlmMetricsAdvisor.SESSION_ID_PARAM, sessionId);
                            // Forwards provider reasoning (Ollama "reasoning",
                            // reasoning_content elsewhere) into step events so
                            // the console can show live thinking. No-op when the
                            // turn opted out of step events (shared disabled emitter).
                            a.advisors(new ReasoningEmitAdvisor(stepEvents));
                            // #1102 (direct feedback: opaque "value cannot be null" 400 on every
                            // single chat turn) - resolveExperimentVariants() returns null
                            // whenever a tenant has no active feature-flag experiment (the common
                            // case), but Spring AI's DefaultChatClient$DefaultAdvisorSpec#param(k,
                            // v) asserts its value argument non-null and throws exactly that
                            // generic message - unconditionally passing experimentVariants through
                            // crashed every turn, not just ones under an experiment.
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
                .stream()
                .content();
    }

    /** The complete-path body: outbound audit, fabrication check, persistence, judge sampling. */
    private void onCompleteFullAnswer(String fullAnswer) {
        // Content guardrails, OUTPUT path, FAST depth: detection + audit only - tokens are
        // already delivered, see auditStreamedOutboundViolation's javadoc for the tradeoff.
        owner.guardrails.auditStreamedOutboundViolation(
                agent, agentId, sessionId, context.tenantId(), fullAnswer, context.tenantContext());
        turn.guard("outbound-audit", "ran");
        // Fabricated-generation-URL guard: same "tokens already delivered, detect for the record"
        // tradeoff as the guardrail audit above - this cannot recall what already streamed live,
        // but it keeps the lie from being replayed as trusted context on the NEXT turn and gives
        // observability into how often it happens. Persist the corrected text instead of the raw
        // fabrication when triggered. The tool-log read runs in the turn's tenant scope: this
        // fires on the stream-completion thread.
        String toPersist = fullAnswer;
        boolean fabricatedUrl =
                containsFabricatedGenerationUrl(fullAnswer)
                        && context.scope()
                                        .get(
                                                () ->
                                                        owner.collectToolEvidenceSince(
                                                                agentId,
                                                                sessionId,
                                                                fabricationCheckStartedAt))
                                == null;
        turn.verify("fabricated-generation-url", !fabricatedUrl);
        if (fabricatedUrl) {
            toPersist =
                    owner.issues.recordAndCorrectFabricatedGenerationUrl(
                            agentId, sessionId, fullAnswer);
        }
        turn.commit(toPersist, "completed");
        owner.sessionMemoryService.appendTurn(memorySessionId, ChatTurn.assistant(toPersist));
        StreamedReplyCompletion.afterPersisted(
                owner.events,
                owner.llmQualityJudgeService,
                new StreamedReplyCompletion.Reply(
                        chatClient,
                        agentId,
                        sessionId,
                        context.tenantId(),
                        userMessage,
                        toPersist,
                        fullAnswer),
                context.tenantContext());
    }
}
