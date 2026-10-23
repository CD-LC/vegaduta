package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.AgentChatService.INTERRUPTED_REPLY_MARKER;
import static com.agenticai.core.agents.turn.ChatClientResolver.providerOptions;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.containsFabricatedGenerationUrl;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.tunables.BehaviourSettings;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.tools.AgentToolSelection;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.agents.turn.quality.ReflexionService;
import com.agenticai.core.chat.LlmRetryPolicy;
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
import java.util.function.Supplier;
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

    /**
     * The resolver of the per-tenant / per-agent runtime limits; null (hand-built fixtures) = every turn
     * runs under the platform values this executor was built with. Set from {@link ChatTimeouts#runtime()}
     * when the service is built.
     */
    volatile com.agenticai.core.agents.runtime.AgentRuntime runtime;

    /**
     * Resolves the turn's runtime settings (platform, tenant, agent and the run's own caps) once the agent is
     * known and records them on the turn's context, where every stage that enforces a limit reads them.
     * Never fails the turn: with no resolver, or one that throws, the turn runs under the platform values.
     */
    void resolveRuntime(TurnContext context, UUID agentId, com.agenticai.core.agents.runtime.RuntimeLimits caps) {
        com.agenticai.core.agents.runtime.AgentRuntime resolver = runtime;
        if (resolver == null) {
            return;
        }
        try {
            context.runtime().set(resolver.forAgent(agentId, caps));
        } catch (RuntimeException e) {
            log.warn("Could not resolve the runtime settings of agent {}; the turn runs under the platform's: {}", agentId, e.toString());
        }
    }

    /** Continuation passes this turn may run: the agent's setting, else the platform's {@link #maxContinuations}. */
    int continuationPasses(TurnContext context) {
        com.agenticai.core.agents.runtime.AgentRuntimeSettings settings = context == null ? null : context.runtimeSettings();
        return settings != null ? settings.continuationPasses() : maxContinuations;
    }

    /**
     * A tool-loop transcript for this turn's run, carrying the turn's own round cap when its tool-step ceiling
     * needs a larger backstop than the platform's (see {@code AgentRuntimeSettingsResolver#toolLoopRounds}).
     */
    com.agenticai.core.chat.ToolLoopTranscript newTranscript(TurnContext context) {
        return newTranscript(context, context == null ? null : context.requestedAgentId());
    }

    /**
     * As {@link #newTranscript(TurnContext)} for the agent that answers (the routed one when an experiment chose a
     * variant): the transcript also carries this tenant's and agent's tool-result clearing settings
     * ({@code chat.toolLoop.resultClearing.*}), which the tool loop applies instead of the process-wide ones.
     */
    com.agenticai.core.chat.ToolLoopTranscript newTranscript(TurnContext context, UUID agentId) {
        com.agenticai.core.chat.ToolLoopTranscript transcript = new com.agenticai.core.chat.ToolLoopTranscript();
        if (context != null) {
            // null = the tool loop's own (platform) value; set only when the catalog answers for this tenant and agent.
            transcript.resultClearing(
                    behaviour.boolOrNull(TOOL_RESULT_CLEARING_ENABLED_KEY, context.scope(), agentId),
                    behaviour.integerOrNull(TOOL_RESULT_CLEARING_THRESHOLD_KEY, context.scope(), agentId));
        }
        com.agenticai.core.agents.runtime.AgentRuntime resolver = runtime;
        com.agenticai.core.agents.runtime.AgentRuntimeSettings settings = context == null ? null : context.runtimeSettings();
        if (resolver != null && settings != null) {
            transcript.limitRounds(resolver.toolLoopRounds(settings));
        }
        // The tool loop writes text of its own (the "tool budget used up" result, the closing reply, the answer to a
        // tool name that was never offered): it looks it up in the prompt registry for THIS turn's agent and tenant.
        if (context != null) {
            transcript.forPrompts(context.requestedAgentId(), context.scope());
        }
        return transcript;
    }

    /**
     * Whether a reply that looks like a stopped task is re-prompted ({@code app.chat.continuation.*}),
     * one policy for every reasoning depth. Set from {@link ChatTimeouts#continuation()} when the
     * service is built; the shipped defaults apply until then.
     */
    volatile TurnContinuation continuation = new TurnContinuation(new ContinuationProperties());

    /**
     * What to do with the provider's finish reason ({@code app.chat.finish.*}): continue a reply cut
     * off at the output limit, refuse honestly when the safety system stopped it, never present an
     * empty or cut-off reply as an answer. Set from {@link ChatTimeouts#finish()} when the
     * service is built; the shipped defaults apply until then.
     */
    volatile ChatFinishProperties finish = new ChatFinishProperties();

    /**
     * The tenant's and the agent's view of the settings catalog ({@code chat.finish.*}, {@code
     * chat.continuation.heuristicEnabled}, the tool-loop result clearing, ...): the platform values until {@link
     * ChatServiceParts} wires the catalog.
     */
    volatile BehaviourSettings behaviour = BehaviourSettings.platformOnly();

    static final String TOOL_RESULT_CLEARING_ENABLED_KEY = "chat.toolLoop.resultClearing.enabled";
    static final String TOOL_RESULT_CLEARING_THRESHOLD_KEY = "chat.toolLoop.resultClearing.thresholdChars";

    /** What the provider's finish reason makes this turn do, for its tenant and agent; see {@link TurnFinishLimits}. */
    TurnFinishLimits finishLimits(TurnContext context, UUID agentId) {
        return TurnFinishLimits.resolve(behaviour, finish, context == null ? null : context.scope(), agentId);
    }

    /**
     * What a turn does when it runs long or a provider fails while tools are involved ({@code
     * app.chat.resilience.*}): the tool-aware idle timeout, replay after tools, the timeout notices. Set
     * from {@link ChatTimeouts#resilience()} when the service is built; the shipped defaults until then.
     */
    volatile ChatResilienceProperties resilience = new ChatResilienceProperties();

    /**
     * {@code app.chat.tool-ledger.*}: the record of what the turn's tools did, stored with the assistant
     * turn. Set from {@link ChatTimeouts#toolLedger()} when the service is built.
     */
    volatile ToolLedgerProperties toolLedger = new ToolLedgerProperties();

    /**
     * {@code app.chat.parity.*}: where the streamed and the buffered turn deliberately differ. Set from
     * {@link ChatTimeouts#parity()} when the service is built.
     */
    volatile ChatParityProperties parity = new ChatParityProperties();

    /**
     * {@code app.chat.cache.*}: what the response caches may serve and store and what they key on. Set from
     * {@link ChatTimeouts#cache()} when the service is built.
     */
    volatile ChatCachePolicy cachePolicy = new ChatCachePolicy(new ChatCacheProperties());

    /** Whether a turn that ran tools may be replayed on another provider; see {@link FailoverReplayGate}. */
    volatile FailoverReplayGate replayGate = new FailoverReplayGate(resilience, null);

    /** {@code app.llm.retry.*}: how a streaming turn retries a transient failure before its first token. */
    volatile LlmRetryPolicy retryPolicy = LlmRetryPolicy.defaults();

    /**
     * The prompt registry's view for an agent (its, its tenant's and the platform's overrides) for the
     * texts this executor sends or shows. The shipped text until {@link ChatServiceParts} wires the
     * settings bean.
     */
    volatile java.util.function.Function<UUID, ResolvedDirectives> prompts =
            ResolvedDirectives.DEFAULTS_FOR_AGENT;

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
     * As above, with the step-event wording of the agent (its, its tenant's or the platform's override of
     * {@code message.degraded.tools_disabled}); {@code context} supplies the tenant for the lookup.
     */
    void flagToolsDisabledRetry(
            Agent agent, AgentStepEventEmitter stepEvents, TurnContext context, UUID agentId) {
        String text =
                context.scope()
                        .get(
                                () ->
                                        prompts.apply(agentId)
                                                .textOrShipped(DegradedRetrySignal.TOOLS_DISABLED_KEY));
        DegradedRetrySignal.flag(meterRegistry, agent, stepEvents, text);
    }

    /**
     * The live-stream half of the fabricated-generation-URL guard - see {@link
     * ChatPromptDirectives#containsFabricatedGenerationUrl}'s javadoc for the incident and {@code dispatchTurn}'s
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
        // Taken here, on the request thread: the map below runs on the stream-completion thread, and
        // the object keeps the turn's tenant for its lookups.
        ResolvedDirectives pd = prompts.apply(agentId);
        return stream.collectList()
                .map(chunks -> String.join("", chunks))
                .map(
                        joined -> {
                            // A reply the output guardrails withheld (FastStreamTurn checked and stored
                            // it) is sent as the replacement, never as the text that was held.
                            String replacement = context.reply().replacement();
                            String fullText = replacement != null ? replacement : joined;
                            // The tool-log read runs in the turn's tenant scope (ADR-0022): this
                            // map fires on the stream-completion thread, not the request thread.
                            if (containsFabricatedGenerationUrl(fullText, pd)
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
                                return pd.textOrShipped("message.fabricated_url.not_generated");
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
        return withAssistantTurnPersistence(
                stream, agentId, sessionId, memorySessionId, accumulated, onCompleteFullAnswer, () -> null);
    }

    /**
     * As above for a turn whose tools are recorded: {@code toolRecord} is read when a partial reply is
     * stored after a failure or a cancel, so the interrupted turn keeps what its tools did.
     */
    Flux<String> withAssistantTurnPersistence(
            Flux<String> stream,
            UUID agentId,
            String sessionId,
            String memorySessionId,
            StringBuilder accumulated,
            Consumer<String> onCompleteFullAnswer,
            Supplier<ToolLedger> toolRecord) {
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
                                    memorySessionId, accumulated, assistantTurnPersisted, toolRecord);
                        })
                .doOnCancel(
                        () ->
                                persistInterruptedAssistantTurn(
                                        memorySessionId, accumulated, assistantTurnPersisted, toolRecord));
    }

    /**
     * The error/cancel half of {@link #withAssistantTurnPersistence}: persist the partial answer
     * once, marked, and never let a persistence failure (e.g. Redis down at the exact moment the
     * stream died) mask the original stream error on its way to the client.
     */
    private void persistInterruptedAssistantTurn(
            String sessionId,
            StringBuilder accumulated,
            AtomicBoolean assistantTurnPersisted,
            Supplier<ToolLedger> toolRecord) {
        String partial = accumulated.toString();
        if (partial.isEmpty() || !assistantTurnPersisted.compareAndSet(false, true)) {
            return;
        }
        try {
            sessionMemoryService.appendTurn(
                    sessionId,
                    ChatTurn.assistant(partial + INTERRUPTED_REPLY_MARKER, toolRecord.get()));
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
        return callWithProviderFallback(tenantId, agent, primaryClient, promptExecutor, null, null);
    }

    /**
     * As above, for a turn whose tool activity is known: a failure that comes after the turn ran tools
     * with side effects is not retried on the fallback provider (it would run the turn, tools included,
     * a second time) unless the effect journal recorded each of them - see {@link FailoverReplayGate}.
     * The original failure is rethrown, with the tools that ran named in the log, the metric and, when
     * the turn has step events, a note.
     *
     * @param activity the turn's tool activity; {@code null} skips the check (a delegated call has none)
     */
    String callWithProviderFallback(
            String tenantId,
            Agent agent,
            ChatClient primaryClient,
            Function<ChatClient, String> promptExecutor,
            TurnToolActivity activity,
            AgentStepEventEmitter stepEvents) {
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
            if (replayBlockedBy(activity, agent, "byok-fallback", stepEvents).isPresent()) {
                throw classified != null ? classified : e;
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
                TurnLifecycleEvents.failover(stepEvents, agent.getFallbackModel(), e);
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
     * The FAST stream's idle timeout: model silence only, a running tool suspends it (see {@link
     * StreamIdleWatch}). Every idle timer of a turn's passes goes through here so none of them times a tool.
     */
    Flux<String> idleTimeout(Flux<String> stream, TurnToolActivity activity) {
        return StreamIdleWatch.apply(
                stream,
                streamIdleTimeout,
                resilience.getToolIdleCeiling(),
                activity,
                com.agenticai.core.security.concurrent.TenantAwareSchedulers.parallel());
    }

    /**
     * Retries {@code first} when it fails before a token under {@code app.llm.retry.*} (429, 5xx, a
     * connection that failed before a response), unless a tool has run this turn; see {@link StreamRetry}.
     * {@code retry} builds the request again for each retry. A no-op when the setting is off.
     */
    Flux<String> retryBeforeFirstToken(
            Flux<String> first,
            java.util.function.Supplier<Flux<String>> retry,
            TurnToolActivity activity,
            com.agenticai.core.security.TenantScope scope,
            UUID agentId,
            String sessionId,
            AgentStepEventEmitter stepEvents) {
        LlmRetryPolicy policy = retryPolicy;
        if (!policy.retriesStreamsBeforeFirstToken(scope)) {
            return first;
        }
        return StreamRetry.apply(
                first,
                retry,
                policy,
                () -> !activity.anyToolRan(),
                scope,
                com.agenticai.core.security.concurrent.TenantAwareSchedulers.parallel(),
                (retryNumber, wait, failure) -> {
                    log.warn(
                            "Agent {} session {}: the model stream failed before its first token ({}); retry {} of {}"
                                    + " in {} ms",
                            agentId,
                            sessionId,
                            failure.toString(),
                            retryNumber,
                            policy.maxAttempts(scope) - 1,
                            wait.toMillis());
                    TurnLifecycleEvents.callRetry(stepEvents, retryNumber, policy.maxAttempts(scope), failure);
                    try {
                        meterRegistry.counter("chat.llm.retry", "path", "stream-pre-first-token").increment();
                    } catch (RuntimeException e) {
                        log.debug("Could not record chat.llm.retry", e);
                    }
                });
    }

    /**
     * Whether failing {@code agent}'s turn over to another provider is blocked because the turn already
     * ran tools: empty when it may be replayed, else the tools that stand in the way. A block is counted,
     * logged and, on a step-event turn, told to the consumer; see {@link FailoverReplayGate}.
     *
     * @param path which failover this is (for the metric and the log)
     */
    java.util.Optional<String> replayBlockedBy(
            TurnToolActivity activity, Agent agent, String path, AgentStepEventEmitter stepEvents) {
        FailoverReplayGate.Verdict verdict = replayGate.check(activity);
        if (verdict.allowed()) {
            return java.util.Optional.empty();
        }
        DegradedRetrySignal.flagReplayBlocked(
                meterRegistry, agent, path, verdict.toolsThatRan(), resilience, stepEvents);
        return java.util.Optional.of(verdict.toolsThatRan());
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
