package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.AgentChatService.INTERRUPTED_REPLY_MARKER;
import static com.agenticai.core.agents.turn.ChatClientResolver.providerOptions;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.containsFabricatedGenerationUrl;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.tools.AgentToolSelection;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.agents.turn.quality.ReflexionService;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayContentPolicyViolationException;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.guardrails.GuardrailViolationException;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import com.agenticai.core.providers.ProviderCredentialResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

/**
 * The token-streaming FAST-depth turn, the shared provider-fallback wrapper and the assistant-turn
 * persistence hooks, extracted verbatim from {@link AgentChatService} (extract-only split).
 */
final class ChatTurnExecutor {

    static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    // Package-private: read by the per-turn FastStreamTurn / FastStreamRecovery split out of
    // streamFast.
    final ProviderCredentialResolver credentialResolver;
    final LlmGatewayService llmGatewayService;
    final ProviderErrorTranslator providerErrorTranslator;
    final SessionMemoryService sessionMemoryService;
    private final ToolCallLogService toolCallLogService;
    final LlmQualityJudgeService llmQualityJudgeService;
    final ChatGuardrailGate guardrails;
    final ChatIssueRecorder issues;
    final AgentMessageEvents events;

    /**
     * Genuine mid-stream idle timeout for {@link #streamFast} - see {@code
     * app.llm.http.stream-idle-timeout}'s comment in application.yml for why this is a Reactor
     * {@code Flux#timeout} operator applied here rather than a client-config property: neither JDK
     * HttpClient nor Apache HttpClient 5 (the two transports {@code LlmHttpClientFactory} can
     * select) expose a timer that re-arms per received chunk, only one that disarms permanently
     * once response headers arrive. This resets on every emitted token instead.
     */
    final Duration streamIdleTimeout;

    /**
     * How many extra model passes a FAST turn may run when a reply stops at an announced-but-undone
     * step ({@link TurnContinuation}); 0 turns it off. Set from {@code app.chat.continuation.max-passes}.
     */
    volatile int maxContinuations;

    ChatTurnExecutor(
            ProviderCredentialResolver credentialResolver,
            LlmGatewayService llmGatewayService,
            ProviderErrorTranslator providerErrorTranslator,
            SessionMemoryService sessionMemoryService,
            ToolCallLogService toolCallLogService,
            LlmQualityJudgeService llmQualityJudgeService,
            ChatGuardrailGate guardrails,
            ChatIssueRecorder issues,
            AgentMessageEvents events,
            Duration streamIdleTimeout) {
        this.credentialResolver = credentialResolver;
        this.llmGatewayService = llmGatewayService;
        this.providerErrorTranslator = providerErrorTranslator;
        this.sessionMemoryService = sessionMemoryService;
        this.toolCallLogService = toolCallLogService;
        this.llmQualityJudgeService = llmQualityJudgeService;
        this.guardrails = guardrails;
        this.issues = issues;
        this.events = events;
        this.streamIdleTimeout = streamIdleTimeout;
    }

    /** The counter for a turn re-run without its tools; see {@link DegradedRetrySignal#METRIC}. */
    static final String DEGRADED_TOOLS_DISABLED_METRIC = DegradedRetrySignal.METRIC;

    /**
     * Defaults to Micrometer's global composite, which Spring Boot populates with the application
     * registry ({@code management.metrics.use-global-registry}, default true) - so this records
     * without the constructor (owned by {@code AgentChatService}) having to change. {@link
     * #setMeterRegistry} lets the owner or a test inject a specific registry.
     */
    private MeterRegistry meterRegistry = Metrics.globalRegistry;

    /** The registry the degraded-turn counters go to (ChatTurnDispatcher's depth-timeout fallback). */
    MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry != null ? meterRegistry : Metrics.globalRegistry;
    }

    /** Pre-step-event signature, kept for existing callers: no step events are emitted. */
    Flux<String> streamFast(
            TurnContext context,
            Agent agent,
            UUID agentId,
            String sessionId,
            ChatClient chatClient,
            String systemPrompt,
            List<Message> history,
            String userMessage,
            List<ToolCallback> tools,
            List<ChatAttachmentPayload> attachments,
            String experimentVariants) {
        return streamFast(
                context,
                agent,
                agentId,
                sessionId,
                chatClient,
                systemPrompt,
                history,
                userMessage,
                tools,
                attachments,
                experimentVariants,
                AgentStepEventEmitter.disabled());
    }


    /**
     * Deterministic tool order so the provider's cached prompt prefix survives turns where the
     * relevance-selected subset differs: always-kept tools (see {@link
     * AgentToolProvider#isAlwaysRelevantTool}) first, then everything else, each group sorted by
     * name. Never mutates the input.
     *
     * <p>Not wired here on purpose: OpenAI's per-request {@code prompt_cache_key}. This executor
     * holds neither the BYOK key nor per-request OpenAiChatOptions (those live in the ChatClient
     * factory), and OpenAI already caches automatically on the longest common prefix, which this
     * ordering keeps stable. Anthropic caching is handled separately by its caching strategy.
     */
    static List<ToolCallback> stableToolOrder(List<ToolCallback> tools) {
        if (tools == null || tools.size() < 2) {
            return tools;
        }
        java.util.Comparator<ToolCallback> byName =
                java.util.Comparator.comparing(
                        t -> t.getToolDefinition().name(),
                        java.util.Comparator.nullsLast(
                                java.util.Comparator.<String>naturalOrder()));
        java.util.Comparator<ToolCallback> alwaysKeptFirst =
                java.util.Comparator.comparing(
                        t -> !AgentToolSelection.isAlwaysRelevantTool(t.getToolDefinition().name()));
        List<ToolCallback> sorted = new java.util.ArrayList<>(tools);
        sorted.sort(alwaysKeptFirst.thenComparing(byName));
        return sorted;
    }

    /**
     * The token-streaming {@link ReasoningDepth#FAST} turn - see {@link FastStreamTurn} (the
     * primary stream and its completion) and {@link FastStreamRecovery} (the retry ladder), where
     * the body moved in the extract-only split. ADR-0022 stage 3: the tenant comes from {@code
     * context}, not from the ThreadLocal; the SEC-004 memory id is still read from the binding the
     * caller made on this thread.
     *
     * @param stepEvents this turn's step-event emitter (never null; {@link
     *     AgentStepEventEmitter#disabled()} when the caller did not ask for events). Used only to
     *     flag the tools-disabled degraded retry.
     */
    Flux<String> streamFast(
            TurnContext context,
            Agent agent,
            UUID agentId,
            String sessionId,
            ChatClient chatClient,
            String systemPrompt,
            List<Message> history,
            String userMessage,
            List<ToolCallback> tools,
            List<ChatAttachmentPayload> attachments,
            String experimentVariants,
            AgentStepEventEmitter stepEvents) {
        return new FastStreamTurn(
                        this,
                        context,
                        agent,
                        agentId,
                        sessionId,
                        chatClient,
                        new FastStreamTurn.Prompt(systemPrompt, history, userMessage, attachments),
                        tools,
                        experimentVariants,
                        stepEvents)
                .stream();
    }

    /** See {@link DegradedRetrySignal#flag}. */
    void flagToolsDisabledRetry(Agent agent, AgentStepEventEmitter stepEvents) {
        DegradedRetrySignal.flag(meterRegistry, agent, stepEvents);
    }

    /**
     * The live-stream half of the fabricated-generation-URL guard - see {@link
     * #FABRICATED_GENERATION_URL_MARKERS}'s javadoc for the incident and {@code dispatchTurn}'s
     * comment in {@link #chat} for why this only applies to turns that both offer a generation tool
     * and read like a generation request. Buffers the entire reply (no chunk reaches the caller
     * until the upstream stream completes) and substitutes an honest correction for the SAME
     * fabrication signature the persisted-history guard checks - this is what stops the fake link
     * from ever reaching the client at all, not just from surviving in history. Errors on the
     * upstream stream pass through unchanged; this only inspects a stream that completed
     * successfully.
     */
    Flux<String> bufferAndGuardGenerationClaims(
            TurnContext context,
            Flux<String> stream,
            UUID agentId,
            String sessionId,
            Instant turnStartedAt) {
        return stream.collectList()
                .map(chunks -> String.join("", chunks))
                .map(
                        fullText -> {
                            // The tool-log read runs in the turn's tenant scope (ADR-0022): this
                            // map fires on the stream-completion thread, not the request thread.
                            if (containsFabricatedGenerationUrl(fullText)
                                    && context.scope()
                                                    .get(
                                                            () ->
                                                                    collectToolEvidenceSince(
                                                                            agentId,
                                                                            sessionId,
                                                                            turnStartedAt))
                                            == null) {
                                log.warn(
                                        "Agent {} session {} - buffered generation-adjacent reply"
                                                + " contained a fabricated generation-service URL with"
                                                + " no corroborating tool call; substituting an honest"
                                                + " correction before it ever reached the client",
                                        agentId,
                                        sessionId);
                                return "I wasn't able to generate that - I don't have a real result"
                                        + " to share. Please try asking again.";
                            }
                            return fullText;
                        })
                .flatMapMany(Flux::just);
    }

    /**
     * Turn-loss fix (2026-08-07): before this, the FAST path persisted the assistant turn ONLY in
     * {@code doOnComplete} - a mid-stream provider failure, stream-idle timeout, or client cancel
     * silently dropped every token already streamed to the user from session memory (the user turn
     * itself is safe: {@link #chat} appends it before the stream starts). This wraps the
     * fully-built provider stream with the three terminal-signal hooks:
     *
     * <ul>
     *   <li><b>complete</b> - runs {@code onCompleteFullAnswer} (the caller's pre-existing
     *       audit/persist/quality-judge body) exactly as before;
     *   <li><b>error/cancel</b> - persists whatever partial answer exists, suffixed with {@link
     *       #INTERRUPTED_REPLY_MARKER}. Persistence-only: the error itself still propagates to the
     *       client unchanged, and a token-less failure (the retryable pre-stream shape the fallback
     *       branches above handle) persists nothing, so hosted-chain rungs that fail over cleanly
     *       write no phantom turns.
     * </ul>
     *
     * <p>The shared {@link AtomicBoolean} guarantees at most ONE assistant turn per stream even if
     * Reactor ever delivers more than one terminal hook for the same subscription (e.g. an operator
     * that cancels upstream after completion).
     *
     * <p>Package-private purely as the test seam for {@code AgentChatServiceTurnPersistenceTest} -
     * the full streaming pipeline above it is not reasonably drivable through Mockito deep stubs.
     */
    Flux<String> withAssistantTurnPersistence(
            Flux<String> stream,
            UUID agentId,
            String sessionId,
            String memorySessionId,
            StringBuilder accumulated,
            Consumer<String> onCompleteFullAnswer) {
        AtomicBoolean assistantTurnPersisted = new AtomicBoolean(false);
        return stream.doOnNext(accumulated::append)
                .doOnComplete(
                        () -> {
                            if (!accumulated.isEmpty()
                                    && assistantTurnPersisted.compareAndSet(false, true)) {
                                onCompleteFullAnswer.accept(accumulated.toString());
                            }
                        })
                .doOnError(
                        e -> {
                            log.error(
                                    "Chat stream failed for agent {} session {}",
                                    agentId,
                                    sessionId,
                                    e);
                            persistInterruptedAssistantTurn(
                                    memorySessionId, accumulated, assistantTurnPersisted);
                        })
                .doOnCancel(
                        () ->
                                persistInterruptedAssistantTurn(
                                        memorySessionId, accumulated, assistantTurnPersisted));
    }

    /**
     * The error/cancel half of {@link #withAssistantTurnPersistence}: persist the partial answer
     * once, marked, and never let a persistence failure (e.g. Redis down at the exact moment the
     * stream died) mask the original stream error on its way to the client.
     */
    private void persistInterruptedAssistantTurn(
            String sessionId, StringBuilder accumulated, AtomicBoolean assistantTurnPersisted) {
        String partial = accumulated.toString();
        if (partial.isEmpty() || !assistantTurnPersisted.compareAndSet(false, true)) {
            return;
        }
        try {
            sessionMemoryService.appendTurn(
                    sessionId, ChatTurn.assistant(partial + INTERRUPTED_REPLY_MARKER));
        } catch (RuntimeException persistFailure) {
            log.warn(
                    "Could not persist interrupted assistant turn for session {}",
                    sessionId,
                    persistFailure);
        }
    }

    /**
     * #848 (LLM gateway seam) - the synchronous (non-streaming) counterpart of {@link
     * #streamFast}'s inline fallback block, generalized so {@link #verifyThenRespond}, {@link
     * #planThenRespond}, and {@link #delegateCall} (none of which had provider fallback before
     * this) get the same "retry once on {@code agent.getFallbackProviderName()}" behavior #829 gave
     * the FAST path, without duplicating the resolve-fallback-client boilerplate three more times.
     * Guardrail/ tool-step-limit exceptions are never retried on the fallback provider - they
     * didn't come from the provider failing, so switching providers can't fix them (same exclusion
     * {@code streamFast}'s {@code onErrorMap} ternary already applies). If the fallback attempt
     * itself throws, the ORIGINAL exception is what propagates - it's the one that actually
     * explains why the turn failed, same reasoning {@code streamFast}'s own comment gives for the
     * identical choice.
     *
     * <p>Fallback is additionally gated on {@link HostedFailoverPolicy}'s retryability decision,
     * same as the streaming path and the hosted pool: an input-shaped failure (content policy,
     * context length, 413/422) is rethrown immediately - already TRANSLATED, so its actionable
     * consumer message survives - because the fallback provider would reject the same input
     * identically. Raw (untranslated) exceptions are classified through {@code
     * providerErrorTranslator} first; anything it maps to the generic 502 (connection errors,
     * unstructured failures) stays retryable, so the pre-gate behavior for those is unchanged.
     * Package-private (was private) purely for {@code AgentChatServiceByokFallbackGatingTest}'s
     * seam.
     *
     * @param promptExecutor builds and runs one full prompt against whichever {@link ChatClient}
     *     it's given - called once against {@code primaryClient}, and again against a
     *     freshly-resolved fallback client only if the first attempt throws.
     */
    String callWithProviderFallback(
            String tenantId,
            Agent agent,
            ChatClient primaryClient,
            Function<ChatClient, String> promptExecutor) {
        try {
            return promptExecutor.apply(primaryClient);
        } catch (RuntimeException e) {
            if (e instanceof ToolStepLimitExceededException
                    || e instanceof GuardrailViolationException
                    || e instanceof LlmGatewayContentPolicyViolationException
                    || agent.getFallbackProviderName() == null) {
                throw e;
            }
            // Classify BEFORE deciding to fall back. translate() is idempotent
            // (an UpstreamProviderException passes through unchanged), so this
            // never double-wraps, and the caller's own onErrorMap downstream
            // treats the classified exception the same way.
            UpstreamProviderException classified =
                    e instanceof UpstreamProviderException upstream
                            ? upstream
                            : providerErrorTranslator.translate(e, agent.getProviderName());
            // classified is contractually never null (translate wraps every
            // Throwable) - the null-check only shields mocked translators from
            // an NPE, degrading to the pre-gate "always fall back" behavior.
            if (classified != null && !HostedFailoverPolicy.isRetryable(classified)) {
                log.warn(
                        "Provider {} failed for agent {} with a non-retryable (input-shaped) error"
                                + " ({}) - not retrying on fallback provider {}, it would reject this"
                                + " the same way",
                        agent.getProviderName(),
                        agent.getId(),
                        classified.getMessage(),
                        agent.getFallbackProviderName());
                throw classified;
            }
            try {
                String fallbackApiKey =
                        credentialResolver.resolveActiveKey(agent.getFallbackProviderName());
                // RL GEN_PARAMS scope boundary - see the identical note at
                // streamFast's inline fallback above; same reasoning applies to
                // this shared callWithProviderFallback helper.
                ChatClient fallbackClient =
                        llmGatewayService.resolveClient(
                                tenantId,
                                new GatewayCallSpec(
                                        agent.getFallbackProviderName(),
                                        fallbackApiKey,
                                        agent.getFallbackModel(),
                                        agent.getMaxTokens(),
                                        agent.getTemperature(),
                                        agent.getTopP(),
                                        null,
                                        providerOptions(agent.getFallbackProviderName(), agent)));
                log.warn(
                        "Provider {} failed for agent {} ({}), retrying once on fallback provider"
                                + " {}",
                        agent.getProviderName(),
                        agent.getId(),
                        e.getMessage(),
                        agent.getFallbackProviderName());
                issues.recordFallbackTriggeredIssue(agent.getId(), agent, e);
                return promptExecutor.apply(fallbackClient);
            } catch (RuntimeException fallbackFailure) {
                log.warn(
                        "Fallback provider {} could not be resolved/used for agent {}",
                        agent.getFallbackProviderName(),
                        agent.getId(),
                        fallbackFailure);
                // Covers BOTH failure shapes uniformly (resolveActiveKey/
                // resolveClient throwing above, or promptExecutor.apply
                // itself throwing) - this catch can't tell which happened,
                // and doesn't need to: either way the fallback didn't
                // rescue the turn. If resolution never even succeeded,
                // recordFallbackTriggeredIssue above never ran, so only this
                // one issue is recorded, not both.
                issues.recordFallbackAlsoFailedIssue(agent.getId(), agent, e, fallbackFailure);
                throw e;
            }
        }
    }

    /**
     * Verification-audit fix (item 5) - {@code verifyThenRespond}'s grounding input for {@link
     * AnswerConsistencyChecker}/{@link ReflexionService}. Same {@code toolCallLogService}
     * since-filter shape {@link #firstToolFailureSince} and {@code chatWithParams}'s {@code
     * ToolCallSummary} mapping already use, just reading back tool name + output instead of only
     * failures. Returns {@code null} when no tool ran this turn, so a tool-free turn's checker
     * prompts are byte-identical to before this fix.
     */
    String collectToolEvidenceSince(UUID agentId, String sessionId, Instant since) {
        List<String> entries =
                toolCallLogService.findByAgentIdAndSessionId(agentId, sessionId).stream()
                        .filter(entry -> entry.getStartedAt().isAfter(since))
                        .map(
                                entry ->
                                        entry.getToolName()
                                                + " -> "
                                                + (entry.getToolOutput() == null
                                                        ? ""
                                                        : entry.getToolOutput()))
                        .toList();
        return entries.isEmpty() ? null : String.join("\n", entries);
    }
}
