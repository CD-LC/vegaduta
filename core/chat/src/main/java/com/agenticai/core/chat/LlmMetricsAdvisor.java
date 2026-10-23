package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.RequestCorrelationFilter;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.trace.Span;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.util.context.ContextView;

/**
 * Cross-cutting advisor that times every real LLM call made through any {@link
 * org.springframework.ai.chat.client.ChatClient} this app builds and reports it. Registered once,
 * in {@code AnthropicAgentChatClientFactory.create()} - the single chokepoint every call site
 * (agent chat, quick task, guardrails, reflexion, graph memory, workflow graph generation, Ask-AI
 * assist) already goes through via {@code ChatClientRegistry}.
 *
 * <p><b>Observer structure.</b> The advisor only measures (clock, tenant, MDC request id and span
 * captured on the calling thread, usage merged across stream chunks) and builds one {@link
 * LlmCallObservation} per finished call. Independent {@link LlmCallSink}s subscribe through {@link
 * LlmCallObserver}, which isolates their failures from the model call:
 *
 * <ul>
 *   <li>{@link GenAiSpanRecorder} - OTel GenAI + Langfuse attributes on the captured span
 *   <li>{@link LlmCallLogSink} - the durable {@code llm_call_log} row (via {@link
 *       LlmCallLogService}, with trace/span ids for jumping to the Langfuse trace)
 *   <li>{@link MicrometerLlmMetrics} - {@code llm.calls}, {@code llm.call.errors}, {@code
 *       llm.latency}, {@code llm.cost.usd}, reasoning tokens, {@code llm.stream.cancelled}
 *   <li>{@link CacheTokenMetrics} - the cached-vs-uncached input-token split
 * </ul>
 *
 * plus {@link StreamFinalizer} for the streaming terminal signals, and the in-memory {@link
 * LlmCallMetricsService} counters fed directly. {@code providerName}/{@code model} are supplied
 * once per {@code ChatClient} construction; {@code agentId}/{@code sessionId} come from {@code
 * chatClientRequest.context()}, populated by call sites that opt in via {@code
 * ChatClient.prompt().advisors(a -> a.param(AGENT_ID_PARAM, agentId)...)} (see {@link
 * #attributeTo}); absent attribution still gets a full row, just with a null agent/session.
 *
 * <p><b>Both advisor chains.</b> This implements {@link CallAdvisor} AND {@link StreamAdvisor}
 * (#599): every agent's default reasoning depth uses {@code stream()}, a different advisor chain
 * that a {@code CallAdvisor}-only class never sees.
 *
 * <p><b>Tenant and thread hops.</b> {@code TenantContext}, MDC and {@code Span.current()} are
 * thread-local, and a stream's {@code doFinally} fires on whatever Reactor/WebClient thread
 * completes it. They are therefore captured synchronously at the top of {@link #adviseCall}/{@link
 * #adviseStream} and the tenant is re-applied around every sink dispatch via {@link
 * #withTenantContext} (restoring what was there before). Without this the durable write silently
 * landed in the wrong schema or no-opped.
 *
 * <p><b>What "LLM latency" measures.</b> Each advisor invocation wraps ONE {@code call()/stream()};
 * Spring AI's automatic tool-calling loop runs entirely INSIDE it, so one sample is "one full agent
 * turn" - tool wall-clock time is baked into the same number as model time. A Quick Task's full log
 * ({@code GET /api/quick-task/{taskId}}) lists each tool call's own duration for the split.
 *
 * <p><b>Sink failures never reach the call.</b> A sink or observation-building exception is logged
 * and swallowed in {@link LlmCallObserver}; previously an exception from logging inside {@link
 * #adviseCall}'s try block was caught as if the model call had failed.
 */
public class LlmMetricsAdvisor implements CallAdvisor, StreamAdvisor {

    /**
     * Context key a caller sets via {@code .advisors(a -> a.param(AGENT_ID_PARAM, agentId))} to
     * attribute a call to an agent.
     */
    public static final String AGENT_ID_PARAM = "llmCallLog.agentId";

    /** Same as {@link #AGENT_ID_PARAM}, for the conversation/task session id. */
    public static final String SESSION_ID_PARAM = "llmCallLog.sessionId";

    /**
     * Master Roadmap 2026 item #13 (A/B testing & feature flags) - same opt-in advisor-param
     * mechanism as {@link #AGENT_ID_PARAM}. {@code AgentChatService} computes this once per chat
     * turn as a flat {@code "flagKey=variant,flagKey2=variant2"} string.
     */
    public static final String EXPERIMENT_VARIANTS_PARAM = "llmCallLog.experimentVariants";

    /**
     * C1 version stamping - same opt-in advisor-param mechanism as {@link #AGENT_ID_PARAM}. Carries
     * the {@code agent_version.id} the calling turn resolved at start; {@link #attributeTo} fills
     * it from {@code AgentVersionPin} automatically, and the call log falls back to the pin
     * directly for call sites that set attribution params manually.
     */
    public static final String AGENT_VERSION_ID_PARAM = "llmCallLog.agentVersionId";

    /** Optional explicit prompt version; falls back to a hash of the system message. */
    public static final String PROMPT_VERSION_PARAM = "llmCallLog.promptVersion";

    /**
     * Set to {@code true} via {@code .advisors(a -> a.param(SKIP_OBSERVATION_PARAM, true))} on a
     * platform-initiated probe that is nobody's usage (the model warm-up): the call is made but no
     * {@code llm_call_log} row, spend counter or metric is recorded for it, so a probe that runs on
     * a member's request thread is never booked to that member's tenant.
     */
    public static final String SKIP_OBSERVATION_PARAM = "llmCallLog.skipObservation";

    /**
     * The attribution block every side-call in a chat turn should carry, in one place (2026-09-17
     * reasoning-depth audit). The extra completions the non-FAST reasoning depths add (consistency
     * check, Reflexion critique, plan, re-plan) are precisely the calls that make up the COST of a
     * deeper depth, and used to land in {@code llm_call_log} with a null agent and session.
     *
     * <p>Null-tolerant on purpose: {@code AdvisorSpec#param} asserts its value non-null, so a
     * caller without an agent or session simply records nothing for that key. C1: the agent-version
     * pin rides along too, read off {@code AgentVersionPin} at spec-build time.
     */
    public static java.util.function.Consumer<
                    org.springframework.ai.chat.client.ChatClient.AdvisorSpec>
            attributeTo(UUID agentId, String sessionId) {
        return spec -> {
            if (agentId != null) {
                spec.param(AGENT_ID_PARAM, agentId);
            }
            if (sessionId != null) {
                spec.param(SESSION_ID_PARAM, sessionId);
            }
            UUID versionPin = com.agenticai.core.security.AgentVersionPin.current();
            if (versionPin != null) {
                spec.param(AGENT_VERSION_ID_PARAM, versionPin);
            }
        };
    }

    /**
     * Error message prefix on the {@code llm_call_log} row of a cancelled stream - see {@link
     * StreamFinalizer}.
     */
    static final String STREAM_CANCELLED_MESSAGE_PREFIX = "CANCELLED";

    private final LlmCallMetricsService metrics;
    private final LlmCallLogService callLogService;
    private final ProviderName providerName;
    private final String model;
    private final LlmPricingCatalog pricingCatalog;
    private final MicrometerLlmMetrics micrometerMetrics;
    private final CacheTokenMetrics cacheTokenMetrics;

    /**
     * NOOP unless {@link #withObservationRegistry} is called (LlmModelSupport#advisors does) - then
     * no invoke_agent span.
     */
    private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

    private GenAiAttributeNames genAiAttributeNames = GenAiAttributeNames.DEFAULT;
    private LangfuseUserId langfuseUserId = LangfuseUserId.DEFAULT;

    /** Replaces the default sinks (tests only); {@code null} = the default four. */
    private List<LlmCallSink> sinkOverride;

    private LlmCallObserver observer;
    private StreamFinalizer streamFinalizer;

    public LlmMetricsAdvisor(
            LlmCallMetricsService metrics,
            LlmCallLogService callLogService,
            ProviderName providerName,
            String model,
            MeterRegistry meterRegistry,
            LlmPricingCatalog pricingCatalog) {
        this.metrics = metrics;
        this.callLogService = callLogService;
        this.providerName = providerName;
        this.model = model;
        this.pricingCatalog = pricingCatalog;
        this.micrometerMetrics = new MicrometerLlmMetrics(meterRegistry);
        this.cacheTokenMetrics = new CacheTokenMetrics(meterRegistry);
        rebuildObserver();
    }

    /** Sinks in dispatch order: span tags, durable row, business meters, cache meters. */
    private void rebuildObserver() {
        List<LlmCallSink> sinks =
                sinkOverride != null
                        ? sinkOverride
                        : List.of(
                                new GenAiSpanRecorder(genAiAttributeNames, langfuseUserId),
                                new LlmCallLogSink(callLogService),
                                micrometerMetrics,
                                cacheTokenMetrics);
        this.observer = new LlmCallObserver(providerName, model, pricingCatalog, sinks);
        this.streamFinalizer = new StreamFinalizer(metrics, observer, model);
    }

    /** Test seam: dispatch to exactly these sinks instead of the default four. */
    LlmMetricsAdvisor withSinks(List<LlmCallSink> sinks) {
        this.sinkOverride = sinks;
        rebuildObserver();
        return this;
    }

    /**
     * Enables the OTel GenAI {@code invoke_agent} span around agent-attributed calls (see {@link
     * AgentInvocationObservation}). Fluent so the 16 factory construction sites stay unchanged;
     * {@link LlmModelSupport#advisors} applies the application's registry to every metrics advisor
     * it is handed.
     */
    public LlmMetricsAdvisor withObservationRegistry(ObservationRegistry registry) {
        this.observationRegistry = registry == null ? ObservationRegistry.NOOP : registry;
        return this;
    }

    /**
     * Which spelling(s) of the renamed GenAI attributes to emit ({@code gen_ai.system} and/or
     * {@code gen_ai.provider.name}); see {@link GenAiAttributeNames}. Same wiring as the registry
     * above.
     */
    public LlmMetricsAdvisor withGenAiAttributeNames(GenAiAttributeNames names) {
        this.genAiAttributeNames = names == null ? GenAiAttributeNames.DEFAULT : names;
        rebuildObserver();
        return this;
    }

    /**
     * What {@code langfuse.user.id} carries (real user / salted hash / tenant); see {@link
     * LangfuseUserId}. Default is the real user, the long-standing behaviour.
     */
    public LlmMetricsAdvisor withLangfuseUserId(LangfuseUserId resolver) {
        this.langfuseUserId = resolver == null ? LangfuseUserId.DEFAULT : resolver;
        rebuildObserver();
        return this;
    }

    @Override
    public String getName() {
        return "LlmMetricsAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public ChatClientResponse adviseCall(
            ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        // Read-only view-as session: no LLM calls, no spend (backstop - see ImpersonationContext).
        com.agenticai.core.security.ImpersonationContext.assertNotImpersonating("llm-call");
        long startNanos = System.nanoTime();
        Instant startedAt = Instant.now();
        // Captured defensively (see class javadoc) - adviseCall runs synchronously on the calling
        // thread in every known path, but this keeps all three captures symmetric with
        // adviseStream.
        Optional<TenantContext> capturedContext = TenantContext.current();
        String capturedRequestId = MDC.get(RequestCorrelationFilter.MDC_KEY);
        // Span.current() never returns null (Span.getInvalid() when no span is active).
        Span capturedSpan = Span.current();
        String snapshotId =
                LlmRequestSnapshotter.capture(chatClientRequest, providerName, model, false);
        micrometerMetrics.recordPrefixBudget(chatClientRequest, providerName, model);
        ChatClientResponse response;
        try {
            response = nextCallInAgentSpan(chatClientRequest, callAdvisorChain);
        } catch (RuntimeException e) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            metrics.recordCall(latencyMs, false);
            withTenantContext(
                    capturedContext,
                    () ->
                            observer.observe(
                                    chatClientRequest,
                                    (Usage) null,
                                    false,
                                    e,
                                    capturedRequestId,
                                    latencyMs,
                                    startedAt,
                                    capturedSpan,
                                    false));
            throw e;
        }
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        metrics.recordCall(latencyMs, true);
        Usage responseUsage = StreamUsageAccumulator.usageOf(response);
        withTenantContext(
                capturedContext,
                () -> {
                    observer.observe(
                            chatClientRequest,
                            responseUsage,
                            true,
                            null,
                            capturedRequestId,
                            latencyMs,
                            startedAt,
                            capturedSpan,
                            false);
                    LlmCallRequestParams.attachSnapshotUsage(
                            chatClientRequest, snapshotId, responseUsage);
                });
        return response;
    }

    /**
     * Runs the rest of the chain inside the {@code invoke_agent} span (when tracing is on and the
     * call is agent-attributed) so the chat and execute_tool spans nest under it. {@link
     * #adviseCall} captured the pre-existing current span BEFORE this opens its scope, so the
     * langfuse.* tagging target is unchanged.
     */
    private ChatClientResponse nextCallInAgentSpan(
            ChatClientRequest request, CallAdvisorChain chain) {
        Observation agentSpan = startAgentSpan(request);
        if (agentSpan == null) {
            return chain.nextCall(request);
        }
        try (Observation.Scope ignored = agentSpan.openScope()) {
            return chain.nextCall(request);
        } catch (RuntimeException e) {
            agentSpan.error(e);
            throw e;
        } finally {
            agentSpan.stop();
        }
    }

    private Observation startAgentSpan(ChatClientRequest request) {
        if (observationRegistry.isNoop()) {
            return null;
        }
        Optional<TenantContext> context = TenantContext.current();
        return AgentInvocationObservation.start(
                observationRegistry,
                genAiSystem(),
                model,
                LlmCallRequestParams.uuid(request, AGENT_ID_PARAM),
                LlmCallRequestParams.agentVersionId(request),
                LlmCallRequestParams.promptVersion(request),
                LlmCallRequestParams.string(request, SESSION_ID_PARAM),
                context.map(TenantContext::getTenantId).orElse(null),
                langfuseUserId.resolve(
                        context.map(TenantContext::getTenantId).orElse(null),
                        context.map(TenantContext::getSubject).orElse(null)),
                genAiAttributeNames);
    }

    private String genAiSystem() {
        return ProviderName.GROK == providerName ? "xai" : providerName.name().toLowerCase();
    }

    /**
     * The streaming half of the #599 fix. Unlike {@link #adviseCall}, {@code
     * streamAdvisorChain.nextStream(...)} returns a cold {@link Flux} immediately - the real work
     * happens as it's subscribed to and drained downstream, so the call is recorded from a {@code
     * doFinally} on the returned Flux ({@link StreamFinalizer}), guarded by an {@link
     * AtomicBoolean} so a subscription is recorded exactly once no matter how the
     * complete/error/cancel signals race.
     *
     * <p><b>Usage is merged across chunks, not read off the last one.</b> Providers split the
     * tally: Anthropic reports input tokens on its opening {@code message_start} event and output
     * tokens on the closing {@code message_delta}; OpenAI sends one usage-only chunk at the very
     * end; a cancelled stream never reaches the end at all. {@link StreamUsageAccumulator} keeps
     * the max prompt and max completion count over every chunk that reported a non-empty usage.
     *
     * <p><b>Span.</b> The span to tag is resolved once per subscription, never re-read in the
     * finalizer (which runs on an unrelated thread). Preference order: the ChatClient's own
     * observation span, if a real {@code ObservationRegistry} ever put one into the Reactor context
     * (dormant today); otherwise {@code Span.current()} captured synchronously when this method is
     * invoked.
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        com.agenticai.core.security.ImpersonationContext.assertNotImpersonating("llm-call");
        // Captured HERE, synchronously, before the Flux is even returned - this is the calling
        // (request) thread, the one place guaranteed to have the right TenantContext/MDC/Span. The
        // doFinally finalizer fires later on whatever thread the streaming response finishes on.
        Optional<TenantContext> capturedContext = TenantContext.current();
        String capturedRequestId = MDC.get(RequestCorrelationFilter.MDC_KEY);
        Span callerSpan = Span.current();
        String snapshotId =
                LlmRequestSnapshotter.capture(chatClientRequest, providerName, model, true);
        micrometerMetrics.recordPrefixBudget(chatClientRequest, providerName, model);
        // Per-SUBSCRIPTION state lives inside deferContextual: a downstream retry/repeat
        // resubscribes, and each attempt is its own provider call that must get its own row, clock
        // and exactly-once guard.
        return Flux.deferContextual(
                reactorContext -> {
                    Observation agentSpan = startAgentSpan(chatClientRequest);
                    Flux<ChatClientResponse> upstream =
                            streamAdvisorChain.nextStream(chatClientRequest);
                    if (agentSpan != null) {
                        // Spring AI's streaming models read their parent observation from this
                        // Reactor-context key, which is how chat/execute_tool spans nest under
                        // invoke_agent without a thread-local scope.
                        upstream =
                                upstream.doOnError(agentSpan::error)
                                        .doFinally(signal -> agentSpan.stop())
                                        .contextWrite(
                                                ctx -> ctx.put(OBSERVATION_CONTEXT_KEY, agentSpan));
                    }
                    long startNanos = System.nanoTime();
                    Instant startedAt = Instant.now();
                    Span span = resolveStreamSpan(reactorContext, callerSpan);
                    StreamUsageAccumulator usage = new StreamUsageAccumulator();
                    AtomicReference<Throwable> failure = new AtomicReference<>();
                    AtomicBoolean finalized = new AtomicBoolean(false);
                    return upstream.doOnNext(usage::accept)
                            .doOnError(failure::set)
                            .doFinally(
                                    signal -> {
                                        if (!finalized.compareAndSet(false, true)) {
                                            return;
                                        }
                                        long latencyMs =
                                                (System.nanoTime() - startNanos) / 1_000_000;
                                        streamFinalizer.finalizeStream(
                                                signal,
                                                failure.get(),
                                                usage.merged(),
                                                chatClientRequest,
                                                capturedContext,
                                                capturedRequestId,
                                                latencyMs,
                                                startedAt,
                                                span,
                                                snapshotId);
                                    });
                });
    }

    /**
     * Re-applies {@code capturedContext} on whichever thread is running right now, runs {@code
     * work}, then restores whatever was there before (never unconditionally clears - same care
     * {@link com.agenticai.core.agents.tools.TenantContextPropagatingToolCallback} takes, so this
     * never clobbers a context some other, already-correct mechanism had set on this thread for an
     * unrelated reason). A {@code null} captured context (the "security" profile off, or genuinely
     * no tenant context on the original thread) is a no-op - {@code work} still runs, just without
     * touching {@code TenantContext} at all.
     */
    static void withTenantContext(Optional<TenantContext> capturedContext, Runnable work) {
        TenantScope.of(capturedContext).run(work);
    }

    /**
     * The span a stream's finalizer tags - see {@link #adviseStream}'s javadoc. Reading the
     * ChatClient observation's span requires briefly opening its scope (with a tracing handler that
     * is what makes its span {@code Span.current()}); only {@code micrometer-observation} API is
     * used, so there is no compile dependency on a tracing bridge. Any failure, a no-op observation
     * or an invalid span falls back to {@code callerSpan}.
     */
    static Span resolveStreamSpan(ContextView reactorContext, Span callerSpan) {
        try {
            Object candidate = reactorContext.getOrDefault(OBSERVATION_CONTEXT_KEY, null);
            if (candidate instanceof Observation observation && !observation.isNoop()) {
                try (Observation.Scope ignored = observation.openScope()) {
                    Span observed = Span.current();
                    if (observed.getSpanContext().isValid()) {
                        return observed;
                    }
                }
            }
        } catch (RuntimeException e) {
            // Tracing is a side channel - fall through to the caller span.
        }
        return callerSpan;
    }

    /**
     * {@code ObservationThreadLocalAccessor.KEY} - the Reactor-context key Spring AI's {@code
     * DefaultChatClient} writes its ChatClient observation under. Inlined as a literal (it is a
     * compile-time constant in micrometer-observation 1.14.x) so this class never loads {@code
     * ObservationThreadLocalAccessor}, whose interface lives in the optional context-propagation
     * library.
     */
    static final String OBSERVATION_CONTEXT_KEY = "micrometer.observation";

    /**
     * Merges token usage across the chunks of one stream - see {@link #adviseStream}'s javadoc.
     * Synchronized because a downstream cancel can race an in-flight {@code onNext} on another
     * thread.
     *
     * <p>Public so the reservation-tracking advisors in {@code core.agents} ({@code
     * FreeTierTokenTrackingAdvisor}, {@code PerUserBudgetTrackingAdvisor}) reconcile against the
     * SAME merged usage the durable {@code llm_call_log} row and the cost meters see - those
     * advisors used to read the LAST chunk only, which undercounts providers that split usage
     * across chunks (Anthropic reports input on {@code message_start}, output on {@code
     * message_delta}), so the token quota and per-user dollar budget debited less than the call log
     * recorded.
     *
     * <p>The returned {@link Usage} is the most informative chunk's own object whenever that chunk
     * already carries both maxima, which keeps provider subtypes intact ({@code GoogleGenAiUsage}
     * carries its cache count as its own getters, and {@link CacheTokenAccounting} relies on that).
     * Otherwise it is a {@link DefaultUsage} of the two maxima, carrying the native usage of the
     * chunk that reported the most prompt tokens - the input-side chunk, which is where providers
     * put their cache read/write counts (Anthropic's {@code message_start}).
     */
    public static final class StreamUsageAccumulator {
        private Integer maxPrompt;
        private Integer maxCompletion;
        private Usage promptSource;
        private Usage latestNonEmpty;

        public synchronized void accept(ChatClientResponse response) {
            Usage usage = usageOf(response);
            if (usage == null) {
                return;
            }
            Integer prompt = usage.getPromptTokens();
            Integer completion = usage.getCompletionTokens();
            boolean hasPrompt = prompt != null && prompt > 0;
            boolean hasCompletion = completion != null && completion > 0;
            if (!hasPrompt && !hasCompletion) {
                return;
            }
            latestNonEmpty = usage;
            if (hasPrompt && (maxPrompt == null || prompt >= maxPrompt)) {
                maxPrompt = prompt;
                promptSource = usage;
            }
            if (hasCompletion && (maxCompletion == null || completion > maxCompletion)) {
                maxCompletion = completion;
            }
        }

        /**
         * {@code null} when no chunk reported any usage - "nothing measured", never a fabricated
         * zero.
         */
        public synchronized Usage merged() {
            if (latestNonEmpty == null) {
                return null;
            }
            for (Usage candidate : new Usage[] {latestNonEmpty, promptSource}) {
                if (candidate != null
                        && java.util.Objects.equals(
                                positiveOrNull(candidate.getPromptTokens()), maxPrompt)
                        && java.util.Objects.equals(
                                positiveOrNull(candidate.getCompletionTokens()), maxCompletion)) {
                    return candidate;
                }
            }
            Object nativeUsage =
                    promptSource != null
                            ? promptSource.getNativeUsage()
                            : latestNonEmpty.getNativeUsage();
            return new DefaultUsage(
                    maxPrompt == null ? 0 : maxPrompt,
                    maxCompletion == null ? 0 : maxCompletion,
                    null,
                    nativeUsage);
        }

        private static Integer positiveOrNull(Integer value) {
            return value != null && value > 0 ? value : null;
        }

        private static Usage usageOf(ChatClientResponse response) {
            if (response == null) {
                return null;
            }
            ChatResponse chatResponse = response.chatResponse();
            if (chatResponse == null || chatResponse.getMetadata() == null) {
                return null;
            }
            return chatResponse.getMetadata().getUsage();
        }
    }
}
