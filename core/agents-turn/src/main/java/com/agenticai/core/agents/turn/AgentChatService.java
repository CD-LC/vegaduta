package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.context.ChatContextCompaction;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.crew.HandoffChain;
import com.agenticai.core.agents.experiment.AgentExperimentService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.hosted.HostedChainLink;
import com.agenticai.core.agents.loadtest.LoadTestTenantAllowlist;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.modality.ModelModalitiesService;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.tools.SmallModelProperties;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.tools.e2b.SandboxFileRegistry;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.cache.SemanticResponseCacheService;
import com.agenticai.core.agents.vision.VisionSessionService;
import com.agenticai.core.agents.vision.VisionUsageService;
import com.agenticai.core.chat.ArgumentSanitizingToolCallingManager;
import com.agenticai.core.rl.api.RlPolicyPort;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantStatusGuard;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.tenancy.FeatureFlagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Orchestrates one chat turn: resolve the agent's active provider key, build a ChatClient for it,
 * attach any enabled MCP tools, replay session history so the model has context, stream the reply,
 * and persist both sides of the exchange to Redis once the stream completes successfully.
 */
@Service
public class AgentChatService extends LegacyChatEntryPoints {

    /**
     * Start of the reply a turn gets when the platform LLM pool is empty - callers that must treat
     * it as a failure match on this (B8).
     */
    public static final String NO_PLATFORM_LLM_NOTICE_PREFIX = "No platform LLM is configured";

    static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    // Constructor-injected groups of collaborators (see the constructor javadoc).
    private final ChatModelFacade model;
    private final ChatPolicyFacade policy;
    private final ChatObservabilityFacade observability;
    private final ChatQualityFacade quality;
    private final ChatContextFacade context;
    private final ChatRoutingFacade routing;

    final AgentService agentService;
    final SessionMemoryService sessionMemoryService;
    final AgentToolPort agentToolProvider;
    final TenantLimits tenantLimits;
    final FreeTierChatClientService freeTierChatClientService;
    final TenantStatusGuard tenantStatusGuard;
    // Master Roadmap 2026 item #13 (A/B testing & feature flags, §B) - core/
    // tenancy is already a hard (non-Provider) dependency of this class via
    // tenantStatusGuard above, so this follows the same posture rather than
    // ObjectProvider's lazy-resolution pattern (reserved here for genuinely
    // optional cross-module deps like PlatformIssueService).
    final FeatureFlagService featureFlagService;
    // Master Roadmap 2026 item #18 (cost optimization: routing + semantic
    // caching, §G) - see LlmResponseCacheService's own javadoc for the full
    // reasoning; used only at this class's chat() chokepoint below.
    final LlmResponseCacheService responseCacheService;
    // Gated semantic (embedding-similarity) cache layered on top of the
    // exact-match responseCacheService above. ObjectProvider because it's
    // @Profile("knowledge") (needs the sidecar + pgvector) - absent in an
    // agents-only test context and any deployment without that profile, exactly
    // like agentMemoryService. Only consulted when an agent has opted in.
    final ObjectProvider<SemanticResponseCacheService> semanticResponseCacheService;
    // In-core RL subsystem (core/rl). ObjectProvider, matching the optional
    // cross-module posture used for PlatformIssueService above - a turn selects
    // a MODEL_ROUTE bandit arm and records an episode only when both beans are
    // present (they always are in the full app; absent in an agents-only test
    // context) AND the turn carries a client turnRef (the Playground). Off by
    // default: selectArm returns empty until an admin enables the policy, so
    // every existing turn is unaffected.
    final ObjectProvider<RlPolicyPort> rlPolicyPort;

    /** See {@link ArgumentSanitizingToolCallingManager#sanitizeHistory}'s call site below. */
    static final ObjectMapper HISTORY_SANITIZE_MAPPER = com.agenticai.core.json.JsonMappers.plain();

    // Live Vision - per-user daily frame metering for inline
    // attachments whose source is "camera"/"screen", charged BEFORE the model
    // is invoked (see the block after resolveAttachments in chat()), plus
    // best-effort attribution of those frames to an active vision session
    // when the turn carries a visionSessionId. See both classes' javadoc.
    final VisionUsageService visionUsageService;
    final VisionSessionService visionSessionService;
    // Load-test seam (default-off, tenant-gated) - the SINGLE source of
    // truth for whether the current tenant's turn should be faked via
    // LoadTestChatClientFactory instead of reaching any real provider. Same
    // "hard, non-Provider dependency of this module" posture as
    // tenantLimits/featureFlagService above, not ObjectProvider - see
    // LoadTestTenantAllowlist's own javadoc for why this is safe by
    // construction (empty-by-default allowlist, exact tenant-id match only).
    final LoadTestTenantAllowlist loadTestTenantAllowlist;

    /**
     * Server-side turn cancel (V261 sibling, "run control"): every turn's stream polls a Redis flag
     * so an operator's POST .../chat/sessions/{sessionId}/cancel - possibly on another replica -
     * terminates the turn exactly like a client disconnect (the existing {@code doOnCancel}
     * partial-persist path runs unchanged). See the service's javadoc for the flag semantics and
     * why pause/resume is deliberately not a chat verb.
     */
    final ChatTurnCancelService chatTurnCancelService;

    // Extract-only split: the responsibilities below were moved verbatim out of this class into
    // package-private collaborators, built from the same constructor arguments (plain objects, not
    // beans, so the public constructor and every hand-built test fixture stay unchanged); see
    // ChatServiceParts, which builds them.
    final AgentMessageEvents events;
    final ChatIssueRecorder issues;
    final ChatGuardrailGate guardrails;
    final ChatAttachments attachmentResolver;
    final ChatClientResolver clientResolver;
    private final HostedChainFailover hostedFailover;

    /** {@code app.chat.small-model.*}: the tool cap and ranking weights of small-model mode. */
    final SmallModelProperties smallModel;

    /** {@code app.chat.resilience.*}; here for the wait of a structured call. */
    private final ChatResilienceProperties resilienceSettings;

    final RlEpisodeRecorder rlEpisodes;
    final ChatMemoryRecall memoryRecall;
    final ChatTurnExecutor turns;
    final BufferedTurnRunner bufferedTurns;
    private final ChatDelegateCall delegateCalls;
    private final ChatFailedReply failedReplies;
    private final ChatStructuredCall structuredCalls;
    private final ChatSpecialistHandoff specialistHandoffs;
    // The chat() pipeline itself, split by stage (extract-only, same statement order): built
    // last in the constructor, each holds this service and reads its fields at turn time
    // because several of those fields are setter-injected after construction.
    final ChatTurnPreflight preflight;
    private final RlArmSelection rlArmSelection;
    private final ChatClientSelection clientSelection;
    private final ChatPromptAssembler promptAssembler;
    private final ChatTurnDispatcher dispatcher;

    /** Experiment routing, version pin, experiment variants, document modality. */
    final ChatAgentRouting agentRouting;

    /** Tool-trust / agentic-completion / untrusted-data sections, read by the prompt assembler. */
    final PromptDirectiveComposer directiveComposer;

    /** C1 version resolver (lazy provider from the routing facade; null in hand-built fixtures). */
    volatile ObjectProvider<com.agenticai.core.agents.version.AgentVersionResolutionService>
            versionResolver;

    /**
     * Ops kill-switches for the standing per-turn directives in {@link ChatPromptDirectives}
     * (2026-10-01, prompt-path audit): env-level, boot-time, default ON to match historical
     * always-on behavior. Deliberately field-injected rather than constructor-injected - the
     * constructor is already ~45 params and a dozen test harnesses instantiate this service
     * directly; an initialized default means plain {@code new} keeps every directive on (the safe
     * posture) while a {@code ReflectionTestUtils} flip still exercises the off path.
     * Platform-level only by design: no per-agent/tenant override exists because these are safety
     * rails, not preferences - a tenant-level off switch for e.g. the MCP untrusted-data warning
     * would be a self-serve prompt-injection vulnerability.
     */
    @Value("${app.chat.directives.memory.enabled:true}")
    boolean memoryUsageDirectiveEnabled = true;

    @Value("${app.chat.directives.tool-trust.enabled:true}")
    boolean toolTrustDirectiveEnabled = true;

    @Value("${app.chat.directives.mcp-untrusted.enabled:true}")
    boolean mcpUntrustedDirectiveEnabled = true;

    @Value("${app.chat.directives.tool-search.enabled:true}")
    boolean toolSearchDirectiveEnabled = true;

    @Value("${app.chat.directives.context-compaction.enabled:true}")
    boolean contextCompactionDirectiveEnabled = true;

    @Value("${app.chat.directives.browser-v2.enabled:true}")
    boolean browserV2DirectiveEnabled = true;

    // Artifacts convention (see ArtifactsDirective): the one directive here that is OFF by default,
    // because it adds prompt tokens; per-agent opt-in via all-agents or the agent-id list.
    @Value("${app.chat.directives.artifacts.enabled:false}")
    boolean artifactsDirectiveEnabled = false;

    @Value("${app.chat.directives.artifacts.all-agents:false}")
    boolean artifactsDirectiveAllAgents = false;

    @Value("${app.chat.directives.artifacts.agent-ids:}")
    String artifactsDirectiveAgentIds = "";

    /**
     * One "memory used" step event per step-event turn; read through a supplier by {@link
     * ChatMemoryRecall}.
     */
    @Value("${app.chat.sse.memory-events.enabled:true}")
    boolean memoryStepEventsEnabled = true;

    // Latency (per-turn overhead parallelization): the independent, blocking
    // pre-LLM reads (session history, tool resolution, memory recall) fanned out
    // in chat() run on virtual threads (Project Loom, stable since Java 21 - see
    // core/pom.xml's java.version). These are pure I/O waits (Redis / MCP /
    // sidecar), exactly what virtual threads are for: a blocked virtual thread
    // parks its carrier at zero platform-thread cost, so there is no pool to
    // size and no ceiling to starve under concurrent turns. A fresh virtual
    // thread per task also means no thread reuse, so supplyPreTurn's TenantContext
    // save/restore can never leak scope across tasks. Also runs HostedChainFailover's
    // self-heal (JAVA-008); shut down in shutdownPreTurnExecutor (JAVA-026).
    final ExecutorService preTurnExecutor =
            TenantAwareExecutors.newVirtualThreadPerTaskExecutor("chat-pre-turn-");

    /**
     * How often a buffered-depth turn writes an EMPTY SSE chunk while it works.
     *
     * <p>A buffered depth writes nothing to the wire until the whole turn (main completion +
     * self-consistency + Reflexion + guardrail passes) is done, which is exactly the shape every
     * intermediary in front of this service treats as a dead origin: Cloudflare's ~100s
     * origin-response limit (524) sits under the browser's own connect ceiling, and neither can be
     * fixed by tuning the client. An empty chunk is invisible to every consumer - the web client
     * appends it to the assistant bubble as "" and resets its idle timer, the voice bridge and the
     * omnichannel adapters accumulate "" - but it commits the response and keeps the connection
     * demonstrably alive.
     *
     * <p><b>The one tradeoff, deliberate:</b> once any byte is on the wire the HTTP status is
     * committed, so a failure after the first keepalive can no longer be mapped to a 402/503 by
     * {@code GlobalExceptionHandler} and rides the in-band {@code STREAM_ERROR_MARKER} instead (see
     * {@code AgentController#chat}). The interval is therefore sized so the failures that DO carry
     * an actionable status - auth, billing, rate limit, invalid model, all of which reject in well
     * under a second - are long finished before the first beat.
     */
    final Duration bufferedKeepAliveInterval;

    /** Routing collaborator resolved after construction (null = no experiment routing). */
    volatile AgentExperimentService agentExperimentService;

    volatile ChatTtftRecorder ttft = ChatTtftRecorder.NOOP;

    /** Data-analysis sandbox (gap-analysis item 9); null = no mounting. */
    volatile SandboxFileRegistry sandboxFiles;

    volatile ModelModalitiesService modelModalities;

    /** Agent lifecycle hooks (app.agent-hooks, default off); null = none. */
    volatile com.agenticai.core.agents.hooks.AgentHookRuntime agentHooks;

    /**
     * Context compaction (app.context.compaction.*, default OFF): setter-injected like the fields
     * above so the constructor and the hand-built test fixtures stay untouched. Null or disabled =
     * no run is opened, so the prompt, the tool list and the client are exactly what they were
     * without this feature.
     */
    volatile ChatContextCompaction contextCompaction;

    /**
     * Per-tenant prompt-directive overrides; setter-injected so the constructor and hand-built test
     * fixtures stay untouched. The default has no store, i.e. the built-in prompt.
     */
    volatile PromptDirectiveSettings promptDirectiveSettings =
            PromptDirectiveSettings.defaultsOnly();

    /**
     * The sentence a failed reply carries when the provider gave no message of its own: the
     * agent's, its tenant's or the platform's wording of {@code message.generic_failure}, else the
     * shipped one. Read in the calling thread's tenant. Never null, never throws.
     */
    String genericFailureMessage(UUID agentId) {
        try {
            String text =
                    promptDirectiveSettings
                            .forTurn(java.time.LocalDate.now(java.time.ZoneOffset.UTC), agentId)
                            .textOrShipped(AgentChatStreamResponder.GENERIC_FAILURE_KEY);
            return text != null ? text : AgentChatStreamResponder.defaultGenericFailureMessage();
        } catch (RuntimeException e) {
            return AgentChatStreamResponder.defaultGenericFailureMessage();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void usePromptDirectiveSettings(PromptDirectiveSettings settings) {
        this.promptDirectiveSettings = settings;
    }

    volatile ChatHotpath hotpath =
            ChatHotpath.OFF; // flagged pre-first-token trimming; OFF = today's calls

    /** L4 verifier-escalated router; absent bean or flag off = routing untouched. Test seam. */
    void useVerifierEscalationRouter(ObjectProvider<VerifierEscalationRouter> p) {
        VerifierEscalationRouter r = p == null ? null : p.getIfAvailable();
        clientResolver.useEscalationRouter(r);
        bufferedTurns.useEscalationRouter(r);
    }

    /**
     * Resolves the optional collaborators held by the facades. Runs once after construction rather
     * than in the constructor because several of these beans (the specialist handoff, workflow
     * events, experiments) can themselves depend on this service; the lazy {@link ObjectProvider}s
     * make that safe, a constructor-time {@code getIfAvailable()} would not. Hand-built test
     * fixtures never call it and keep the defaults (null / NOOP / OFF).
     */
    @jakarta.annotation.PostConstruct
    void resolveOptionalCollaborators() {
        events.setPublisher(observability.workflowEventPublisher().getIfAvailable());
        ttft = ChatTtftRecorder.of(observability.meterRegistry());
        agentHooks = observability.agentHooks().getIfAvailable();
        sandboxFiles = context.sandboxFileRegistry().getIfAvailable();
        modelModalities = context.modelModalitiesService().getIfAvailable();
        contextCompaction = context.chatContextCompaction().getIfAvailable();
        ChatHotpath h = routing.chatHotpath().getIfAvailable();
        hotpath = h == null ? ChatHotpath.OFF : h;
        clientResolver.useHotpath(hotpath);
        useVerifierEscalationRouter(routing.verifierEscalationRouter());
        agentExperimentService = routing.agentExperimentService().getIfAvailable();
        specialistHandoffs.setHandoffService(routing.specialistHandoffService().getIfAvailable());
    }

    /**
     * Collaborators arrive grouped: the four core ones directly, the rest as the facades {@link
     * ChatModelFacade}, {@link ChatPolicyFacade}, {@link ChatObservabilityFacade}, {@link
     * ChatQualityFacade}, {@link ChatContextFacade} and {@link ChatRoutingFacade}, plus {@link
     * ChatTimeouts}. The optional ones those facades carry are resolved by {@link
     * #resolveOptionalCollaborators()}.
     */
    public AgentChatService(
            AgentService agentService,
            SessionMemoryService sessionMemoryService,
            AgentToolPort agentToolProvider,
            ToolCallLogService toolCallLogService,
            ChatModelFacade model,
            ChatPolicyFacade policy,
            ChatObservabilityFacade observability,
            ChatQualityFacade quality,
            ChatContextFacade context,
            ChatRoutingFacade routing,
            ChatTimeouts timeouts) {
        this.model = model;
        this.policy = policy;
        this.observability = observability;
        this.quality = quality;
        this.context = context;
        this.routing = routing;
        this.versionResolver = routing.agentVersionResolutionService();
        this.agentService = agentService;
        this.sessionMemoryService = sessionMemoryService;
        this.agentToolProvider = agentToolProvider;
        this.tenantLimits = policy.tenantLimits();
        this.freeTierChatClientService = model.freeTierChatClientService();
        this.tenantStatusGuard = policy.tenantStatusGuard();
        this.featureFlagService = policy.featureFlagService();
        this.responseCacheService = quality.responseCacheService();
        this.semanticResponseCacheService = quality.semanticResponseCacheService();
        this.rlPolicyPort = observability.rlPolicyPort();
        this.visionUsageService = context.visionUsageService();
        this.visionSessionService = context.visionSessionService();
        this.loadTestTenantAllowlist = policy.loadTestTenantAllowlist();
        this.chatTurnCancelService = policy.chatTurnCancelService();
        this.bufferedKeepAliveInterval = timeouts.bufferedKeepAliveInterval();
        this.smallModel = timeouts.smallModel();
        this.resilienceSettings = timeouts.resilience();
        // Before the parts: the delegate-call collaborators take its experiment-variants lookup.
        this.agentRouting = new ChatAgentRouting(this);
        ChatServiceParts parts =
                new ChatServiceParts(
                        this,
                        agentService,
                        sessionMemoryService,
                        agentToolProvider,
                        toolCallLogService,
                        model,
                        policy,
                        observability,
                        quality,
                        context,
                        timeouts);
        this.events = parts.events;
        this.issues = parts.issues;
        this.guardrails = parts.guardrails;
        this.attachmentResolver = parts.attachmentResolver;
        this.clientResolver = parts.clientResolver;
        this.hostedFailover = parts.hostedFailover;
        this.rlEpisodes = parts.rlEpisodes;
        this.memoryRecall = parts.memoryRecall;
        this.turns = parts.turns;
        this.bufferedTurns = parts.bufferedTurns;
        this.delegateCalls = parts.delegateCalls;
        this.specialistHandoffs = parts.specialistHandoffs;
        this.directiveComposer = parts.directiveComposer;
        this.failedReplies = parts.failedReplies;
        this.structuredCalls = new ChatStructuredCall(this, agentService, toolCallLogService);
        this.preflight = new ChatTurnPreflight(this);
        this.rlArmSelection = new RlArmSelection(this);
        this.clientSelection =
                new ChatClientSelection(
                        this,
                        parts.followUp,
                        timeouts.continuationProperties(),
                        timeouts.localModel());
        this.promptAssembler = new ChatPromptAssembler(this);
        this.dispatcher = new ChatTurnDispatcher(this);
    }

    /**
     * The turn entry point (ADR-0022 stage 4). The caller builds {@code context} at its entry
     * ({@link TurnContext#forEntry}) from the tenant it already knows; the synchronous part runs
     * under {@link TurnContext#onEntryThread} and the reply is subscribed inside {@code
     * context.scope()} on whichever thread subscribes (no scheduler change).
     *
     * <p>{@code attachmentKey} is NOT trusted: it is re-derived from {@code agentId} server-side
     * and must be a key THIS platform minted for THIS agent, or the turn is refused with a 400 (see
     * {@code requireOwnAttachmentKey}); do not weaken that without reading why it exists. A {@code
     * turnRef} (Playground only) makes the turn consult the MODEL_ROUTE bandit and record an {@code
     * rl_episode}. Vision-frame turns are forced to FAST depth regardless of {@code
     * forceFastDepth}. Step events travel out-of-band and never appear in the returned Flux.
     */
    @Override
    public Flux<String> chat(TurnContext context, ChatTurnRequest request) {
        Flux<String> reply = context.onEntryThread(() -> runTurn(context, request));
        return TenantScopedFlux.subscribeInScope(context.scope(), () -> reply);
    }

    private Flux<String> runTurn(TurnContext entryContext, ChatTurnRequest request) {
        final long ttftStartNanos = System.nanoTime();
        // The request is the source of truth for "this is the continuation after an approval": an
        // entry that built its context without it (the context is built before the request is
        // finished) still gets a turn that screens, selects tools and routes on the original goal.
        final TurnContext context = entryContext.withContinuation(request.continuation());
        // The five stages below are the original body of chat(), in order, same early returns.
        ChatTurnPreflight.Outcome preflightOutcome =
                preflight.run(
                        context,
                        ttftStartNanos,
                        request.subjectKey(),
                        request.userMessage(),
                        request.attachmentKey(),
                        request.inlineAttachments(),
                        request.extraTools(),
                        request.stepEvents());
        if (preflightOutcome.earlyReturn() != null) {
            return preflightOutcome.earlyReturn();
        }
        ChatTurnPreflight.Turn turn = preflightOutcome.turn();
        // The agent is known: its runtime limits (tool steps, timeouts, output cap, continuation
        // passes), with
        // the tenant's defaults under them and this run's caps (an agent job's remaining budget)
        // over them,
        // are decided ONCE here and read by every stage that enforces one.
        turns.resolveRuntime(context, turn.agentId(), request.caps());
        // Phase events (step-event turns only; the shared no-op emitter drops them): which
        // pre-token stage the turn is in, so a slow local model's silent minute reads as
        // progress in the console. `guardrails` fired inside preflight; `model` fires in the
        // dispatcher at subscribe.
        request.stepEvents().phase("routing");
        RlTurnPlan rl = rlArmSelection.select(context, turn, request.turnRef());
        // The run-scoped tool selection; a text-only turn has no tools to narrow.
        List<String> runToolNames = request.suppressAgentTools() ? null : request.toolNames();
        ChatClientSelection.Outcome clientOutcome =
                clientSelection.select(
                        context,
                        turn,
                        rl,
                        request.userMessage(),
                        request.subjectKey(),
                        runToolNames);
        if (clientOutcome.earlyReturn() != null) {
            return clientOutcome.earlyReturn();
        }
        ChatClientSelection.Clients clients = clientOutcome.clients();
        request.stepEvents().phase("context");
        ChatPromptAssembler.AssembledPrompt prompt =
                promptAssembler.assemble(
                        turn,
                        rl,
                        clients,
                        request.sessionId(),
                        request.subjectKey(),
                        request.userMessage(),
                        request.attachmentMimeType(),
                        request.inlineAttachments(),
                        request.extraTools(),
                        request.turnRef(),
                        request.visionSessionId(),
                        request.forceFastDepth(),
                        request.stepEvents(),
                        request.suppressAgentTools(),
                        runToolNames,
                        request.taintSource());
        return dispatcher.stream(
                ttftStartNanos,
                turn,
                rl,
                clients,
                prompt,
                request.agentId(),
                request.sessionId(),
                request.subjectKey(),
                request.userMessage(),
                request.stepEvents());
    }

    /** One GEN_PARAMS arm's temperature/topP preset. */
    record GenParams(double temperature, double topP) {}

    /** See {@link HostedChainFailover#maybeSelfHealInvalidModelRow}. Package-private test seam. */
    void maybeSelfHealInvalidModelRow(HostedChainLink link, Throwable error) {
        hostedFailover.maybeSelfHealInvalidModelRow(link, error);
    }

    /** See {@link HostedChainFailover#syncHealChainExhaustionAndRebuild}. */
    HostedChainLink syncHealChainExhaustionAndRebuild(
            HostedChainLink link,
            Throwable error,
            int maxTokens,
            double temperature,
            double topP,
            String tenantId,
            String subjectKey,
            PerUserBudgetService.Modality modality) {
        return hostedFailover.syncHealChainExhaustionAndRebuild(
                link, error, maxTokens, temperature, topP, tenantId, subjectKey, modality);
    }

    /**
     * Appended verbatim to a partial assistant reply persisted after a mid-stream failure or client
     * cancel, so the truncation is honest in the stored history: {@link ChatTurn} deliberately
     * carries no metadata (see its javadoc), which makes an in-text marker the least invasive way
     * to keep a later history replay (and the user re-reading the session) from mistaking a cut-off
     * reply for a complete one.
     */
    static final String INTERRUPTED_REPLY_MARKER = ChatTurn.INTERRUPTED_REPLY_SUFFIX;

    /** See {@link ChatFailedReply#withFailedReplyPersistence}. Package-private test seam. */
    Flux<String> withFailedReplyPersistence(Flux<String> stream, UUID agentId, String sessionId) {
        return failedReplies.withFailedReplyPersistence(stream, agentId, sessionId, () -> null);
    }

    /**
     * As above, keeping the turn's tool record with the failed reply (the tools ran before it
     * failed).
     */
    Flux<String> withFailedReplyPersistence(
            Flux<String> stream, UUID agentId, String sessionId, Supplier<ToolLedger> toolRecord) {
        return failedReplies.withFailedReplyPersistence(stream, agentId, sessionId, toolRecord);
    }

    /** See {@link ChatFailedReply#buildingTurnStream}. */
    Flux<String> buildingTurnStream(
            UUID agentId, String sessionId, java.util.function.Supplier<Flux<String>> build) {
        return failedReplies.buildingTurnStream(agentId, sessionId, build);
    }

    /**
     * See {@link ChatTurnExecutor#withAssistantTurnPersistence}. Package-private test seam ({@code
     * sessionId} doubles as the already-scoped SEC-004 memory id).
     */
    Flux<String> withAssistantTurnPersistence(
            Flux<String> stream,
            UUID agentId,
            String sessionId,
            StringBuilder accumulated,
            Consumer<String> onCompleteFullAnswer) {
        return turns.withAssistantTurnPersistence(
                stream, agentId, sessionId, sessionId, accumulated, onCompleteFullAnswer);
    }

    /** See {@link ChatTurnExecutor#callWithProviderFallback}. Package-private test seam. */
    String callWithProviderFallback(
            String tenantId,
            Agent agent,
            ChatClient primaryClient,
            Function<ChatClient, String> promptExecutor) {
        return turns.callWithProviderFallback(tenantId, agent, primaryClient, promptExecutor);
    }

    /** The economy-model client for auxiliary calls, or null; see {@link ChatClientResolver}. */
    public ChatClient economyClientFor(UUID agentId) {
        return clientResolver.economyClientFor(agentId);
    }

    /** See {@link ChatDelegateCall#delegateCall(UUID, String, String, String)} for the contract. */
    public String delegateCall(UUID agentId, String question, String contextSummary) {
        return delegateCalls.delegateCall(agentId, question, contextSummary);
    }

    /** See {@link ChatDelegateCall#delegateCall(UUID, String, String, String)} for the contract. */
    public String delegateCall(
            UUID agentId, String question, String contextSummary, String delegatedSessionId) {
        return delegateCalls.delegateCall(agentId, question, contextSummary, delegatedSessionId);
    }

    /** Delegated call shaped by {@link DelegateOptions} (worker directive, tool filter). */
    public String delegateCall(
            UUID agentId, String question, String context, String sessionId, DelegateOptions o) {
        return delegateCalls.delegateCall(agentId, question, context, sessionId, o);
    }

    /**
     * See {@link ChatSpecialistHandoff#delegateToSpecialist} for the full contract (Crew-style
     * chat-time handoff).
     */
    public String delegateToSpecialist(
            HandoffChain chain, String capabilityTag, String subTask, String contextSummary) {
        return specialistHandoffs.delegateToSpecialist(
                chain, capabilityTag, subTask, contextSummary);
    }

    /**
     * Structured counterpart to {@link #chat}'s free-text {@code userMessage} - the input a
     * Workflow Agent node (or any other structured caller) passes once it's opted into {@code
     * AgentSchemaService}-generated parameters instead of one opaque string. Deliberately NOT
     * validated against a generic JSON-Schema library: this record's shape IS the schema {@link
     * AgentSchemaService#generateInputSchema} describes, so validation here is just checking the
     * same fixed fields that service already documents.
     *
     * @param query required - becomes (or is folded into) the underlying {@link #chat} call's
     *     {@code userMessage}.
     * @param contextData optional free-form {@code {type, content}}-shaped map, prepended to {@code
     *     query} the same way {@code WorkflowNodeExecutor#executeKnowledgeNode} already prepends
     *     retrieved knowledge context today.
     * @param knowledgeCollectionIds optional subset of the agent's own {@code
     *     allowedKnowledgeCollectionIds} - validated as a subset, but does NOT yet override
     *     per-call retrieval scoping (that agent-level restriction already applies regardless of
     *     what's passed here); an MVP limitation, not a silent no-op.
     * @param approvalRequired informational for this method - callers that need an approval GATE
     *     (e.g. {@code WorkflowNodeExecutor}) must enforce it themselves before calling this, the
     *     same way the existing generic {@code requiresApproval} node config already halts a run
     *     upstream of any node dispatch.
     */
    public record StructuredAgentInput(
            String query,
            Map<String, Object> contextData,
            List<UUID> knowledgeCollectionIds,
            boolean approvalRequired) {}

    /** One entry of {@link StructuredAgentOutput#toolCalls()}. */
    public record ToolCallSummary(String toolName, String input, String output) {}

    /**
     * Structured counterpart to {@link #chat}'s raw {@code Flux<String>} reply. {@code confidence}
     * is a simple MVP heuristic (1.0 when every tool call this turn made succeeded, lower when any
     * failed) - not a model-reported value, since nothing in this pipeline produces one today.
     * {@code metadata} is currently always empty - reserved for future enrichment (cost,
     * provider/model actually used, etc.) without another schema/shape change.
     */
    public record StructuredAgentOutput(
            String resultText,
            double confidence,
            List<String> sources,
            List<ToolCallSummary> toolCalls,
            Map<String, Object> metadata) {}

    /**
     * The structured-parameter entry point: builds an effective free-text {@code userMessage} from
     * {@code input}, runs it through the same {@link #chat} pipeline as every other caller and
     * wraps the result in a {@link StructuredAgentOutput}. See {@link ChatStructuredCall}.
     *
     * @throws IllegalArgumentException if {@code input} fails the agent's own generated input
     *     schema (blank query, or a knowledge collection id outside this agent's allow-list)
     */
    public StructuredAgentOutput chatWithParams(
            UUID agentId,
            String sessionId,
            String subjectKey,
            StructuredAgentInput input,
            String turnRef) {
        return chatWithParams(
                agentId,
                sessionId,
                subjectKey,
                input,
                turnRef,
                resilienceSettings.getStructuredCallTimeout());
    }

    /**
     * @param timeout how long to block waiting for the full reply; callers that enforce their own
     *     timeout should pass the same value rather than layering two.
     */
    public StructuredAgentOutput chatWithParams(
            UUID agentId,
            String sessionId,
            String subjectKey,
            StructuredAgentInput input,
            String turnRef,
            java.time.Duration timeout) {
        return structuredCalls.chatWithParams(
                agentId, sessionId, subjectKey, input, turnRef, timeout);
    }

    /**
     * Stop accepting pre-turn work when the context closes (JAVA-026). Kept at the end of the class
     * so ArchUnit's frozen cycle baseline, which records call-site line numbers, is not shifted.
     */
    @jakarta.annotation.PreDestroy
    void shutdownPreTurnExecutor() {
        preTurnExecutor.shutdown();
    }
}
