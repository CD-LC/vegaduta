package com.agenticai.core.agents.e2e;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.limits.AgentRuntimeProperties;
import com.agenticai.core.agents.limits.AgentRuntimeSettingsResolver;
import com.agenticai.core.agents.runtime.RuntimeLimits;
import com.agenticai.core.agents.limits.TenantRuntimeLimitsService;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.tools.AgentToolCallApprovalExecutor;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.tools.ToolHarnessBridge;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatFinishProperties;
import com.agenticai.core.agents.turn.ChatResilienceProperties;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.agents.turn.TurnHarnessBridge;
import com.agenticai.core.agents.turn.approval.ApprovalContinuationClaims;
import com.agenticai.core.agents.turn.approval.ApprovalContinuationFactory;
import com.agenticai.core.agents.turn.approval.ApprovalContinuationLauncher;
import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import com.agenticai.core.agents.turn.approval.ApprovalContinuationPrompt;
import com.agenticai.core.approval.ApprovalActionExecutor;
import com.agenticai.core.approval.ApprovalHarnessBridge;
import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalStatus;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.chat.LlmModelSupport;
import com.agenticai.core.chat.LlmRetryPolicy;
import com.agenticai.core.chat.LlmRetryProperties;
import com.agenticai.core.chat.ResilientToolCallingManagerFactory;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.guardrails.PatternBasedGuardrailService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.TenantStatusGuard;
import com.agenticai.core.tenancy.FeatureFlagService;
import com.agenticai.core.tenancy.PlatformSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEvent;

/**
 * Runs a REAL chat turn end to end, through the real guard chain, with only the LLM replaced by a
 * {@link ScriptedLlm}. Built to answer one question the unit tests cannot: "a gated tool call parks
 * the turn; the human approves it; does the agent then finish the job?"
 *
 * <p><b>Real (production classes, production wiring order):</b>
 *
 * <ul>
 *   <li>{@link AgentChatService}: tenant gate, SEC-004 scoped memory key ({@code
 *       ChatTurnPreflight}), history replay and prompt assembly ({@code ChatPromptAssembler}), tool
 *       resolution, the FAST stream and its continuation loop, assistant-turn persistence.
 *   <li>{@link AgentToolProvider} with its {@code ToolResolver}, {@code ToolRelevanceSelector} and
 *       {@code AgentToolGuard}, which wraps each tool in the production chain: {@code
 *       GuardedToolCallback} (step ceiling, approval gate, output validation, pattern guardrail),
 *       {@code LoggingToolCallback}, tenant propagation.
 *   <li>{@link HumanApprovalGateService}, its recorder, {@code ApprovalPolicyService} (tenant
 *       policy decides what is gated), the executor registry and {@link
 *       AgentToolCallApprovalExecutor}, which replays the approved call through a fresh {@code
 *       resolveToolCandidatesFor}, and the continuation launcher/factory/prompt/claims that start
 *       the turn which carries the task on once a human has decided.
 *   <li>{@link SessionMemoryService} with its real key logic, over {@link InMemoryRedis}.
 *   <li>Spring AI's ChatClient and tool-calling manager ({@code
 *       ResilientToolCallingManagerFactory}).
 * </ul>
 *
 * <p><b>Fake:</b> the LLM provider ({@link ScriptedLlm}); persistence (maps for approval requests
 * and Redis); audit, notification, metrics and event sinks; the agent entity; the tool-call log
 * table. The tools are {@link RecordingTool}s so a test can count real side effects.
 *
 * <p><b>Deliberately not exercised</b> (so a green run here is not a claim about them): the HTTP
 * approval controller and its authorization (the harness calls {@link
 * HumanApprovalGateService#approve}, which the controller calls); the semantic LLM judge that is
 * the production default tool-output guardrail (the harness uses the deterministic pattern
 * guardrail); tool classification, hooks, taint, argument policies and the effect journal; the
 * per-tool circuit breaker; the BUFFERED reasoning depths (CAREFUL/RIGOROUS/PLANNED); and Spring
 * context assembly (a real context needs Postgres; the wiring order is reproduced by hand in the
 * constructor).
 *
 * <p>Typical use:
 *
 * <pre>{@code
 * try (ApprovalTurnHarness h = ApprovalTurnHarness.builder().tool(sendInvoice).gateTool("send_invoice")
 *         .responder(model).build()) {
 *     h.chat("Send the invoice");          // parks
 *     h.approvePending();                  // real approval service, tool runs
 *     h.awaitContinuation();               // the turn the SERVER starts by itself
 *     ... assert on h.llm().requests(), h.tools...
 * }
 * }</pre>
 */
public final class ApprovalTurnHarness implements AutoCloseable {

    public static final String DEFAULT_TENANT_ID = "tenant-a";
    public static final String DEFAULT_SUBJECT = "user-1";
    public static final String APPROVER = "admin-1";

    /** The model name cost routing sends a simple message to, when {@link Builder#costRouting} is on. */
    public static final String ECONOMY_MODEL = "economy-model";

    /** The model name the agent runs on. */
    public static final String PRIMARY_MODEL = "gpt-4o-mini";

    /** How a stored turn is recognised as the platform's continuation after a decision. */
    public static final String CONTINUATION_MARKER = "<approval_result";

    private final UUID agentId;
    private final String tenantId;
    private final String subject;
    private final String sessionId;
    private final InMemoryRedis redis = new InMemoryRedis();
    private final SessionMemoryService memory;
    private final ScriptedLlm llm;
    private final AgentChatService chatService;
    private final ApprovalHarnessBridge approvals;
    private final HumanApprovalGateService gate;
    private final AgentToolCallApprovalExecutor executor;
    private final ApprovalContinuationProperties continuationProperties;
    private final ApprovalContinuationLauncher continuationLauncher;
    private final ApprovalContinuationFactory continuationFactory;
    private final ApprovalContinuationPrompt continuationPrompt;
    private final ApprovalContinuationClaims continuationClaims;
    private final AgentToolProvider toolProvider;
    private final List<RecordingTool> tools;
    private final TenantContext tenant;
    private final NotificationService notifications = mock(NotificationService.class);
    private final List<ToolCallLog> toolCallLogRows = new CopyOnWriteArrayList<>();
    private final List<String> toolSelectionMessages = new CopyOnWriteArrayList<>();
    private final List<String> gatewayModels = new CopyOnWriteArrayList<>();
    private final ToolCallLogService toolCallLogService;
    private final AgentRuntimeSettingsResolver runtime;
    /** The settings catalog the turn reads its behaviour settings from; null unless {@link Builder#settingsCatalog}. */
    private final com.agenticai.core.settings.DefaultSettingsResolver settingsResolver;
    /** The catalog again, for the pieces that are built before it exists (the tool guard asks it lazily). */
    private final java.util.concurrent.atomic.AtomicReference<com.agenticai.core.settings.SettingsResolver> settingsRef =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final com.agenticai.core.settings.InMemorySettingsStore settingsStore =
            new com.agenticai.core.settings.InMemorySettingsStore();
    /** The scoped history size just before the last human decision (see {@link #awaitContinuation}). */
    private volatile int historySizeBeforeDecision;
    /** The audit log the tool guard writes its reviewable decisions to (see {@link Builder#classifications()}). */
    private final com.agenticai.core.audit.AuditLogService auditLog = mock(com.agenticai.core.audit.AuditLogService.class);

    private ApprovalTurnHarness(Builder b) {
        this.sessionId = b.sessionId;
        this.agentId = b.agentId;
        this.tenantId = b.tenantId;
        this.subject = b.subject;
        this.tenant = TenantContext.of(tenantId, subject, Set.of());
        this.tools = List.copyOf(b.tools);
        TenantContext.set(tenant);

        // --- the scripted LLM and the ChatClient built over it, the way the provider factories do
        // ---
        ToolCallingManager toolCallingManager =
                new ResilientToolCallingManagerFactory(
                                new ObjectMapper(),
                                null,
                                null,
                                b.toolLoop,
                                b.maxToolCallsPerTurn,
                                b.stepLimits.getAutonomousMultiplier(),
                                b.stepLimits.getGraceCalls(),
                                true,
                                "")
                        .create();
        this.llm = new ScriptedLlm(b.responder, toolCallingManager);
        ChatClient chatClient = chatClientOver(llm);

        // --- agent, tenant, credentials: stubs for lookups the turn makes ---
        Agent agent = mock(Agent.class);
        if (b.costRouting) {
            when(agent.isCostRoutingEnabled()).thenReturn(true);
            when(agent.getEconomyProviderName()).thenReturn(ProviderName.ANTHROPIC);
            when(agent.getEconomyModel()).thenReturn(ECONOMY_MODEL);
        }
        when(agent.getReasoningDepth()).thenReturn(b.depth);
        when(agent.isResponseCachingEnabled()).thenReturn(b.responseCache != null);
        when(agent.getProviderName()).thenReturn(ProviderName.OPENAI);
        when(agent.getEnabledGuardrails()).thenReturn(b.enabledGuardrails);
        when(agent.getSystemPrompt()).thenReturn(b.systemPrompt);
        if (b.fallbackProvider) {
            when(agent.getFallbackProviderName()).thenReturn(ProviderName.ANTHROPIC);
            when(agent.getFallbackModel()).thenReturn("fallback-model");
        }
        AgentService agentService = mock(AgentService.class);
        when(agentService.getEntity(any())).thenReturn(agent);
        when(agentService.resolveEffectiveModel(agent)).thenReturn(PRIMARY_MODEL);
        when(agentService.getAutonomyTier(any())).thenReturn(b.autonomy);
        when(agentService.getAllowedToolNames(any())).thenReturn(b.allowedTools);
        when(agentService.getReasoningDepth(any())).thenReturn(b.depth);
        // The one resolver of the per-tenant / per-agent runtime limits, real, over the layers the test sets.
        // With none set every limit is the platform value the harness was built with, so the existing tests
        // run through the same resolver and must not notice it.
        AgentRuntimeProperties runtimeProperties = new AgentRuntimeProperties();
        b.runtimeTuning.accept(runtimeProperties);
        TenantRuntimeLimitsService tenantRuntimeLimits = mock(TenantRuntimeLimitsService.class);
        when(tenantRuntimeLimits.current()).thenReturn(b.tenantLimits);
        when(agentService.getRuntimeLimits(any())).thenReturn(b.agentLimits);
        AgentRuntimeSettingsResolver runtime =
                new AgentRuntimeSettingsResolver(
                        runtimeProperties,
                        new AgentRuntimeSettingsResolver.PlatformKeys(
                                b.maxToolCallsPerTurn,
                                b.bufferedTurn != null ? b.bufferedTurn : Duration.ofSeconds(240),
                                b.maxContinuations),
                        b.stepLimits,
                        b.toolOutput,
                        b.toolLoop,
                        tenantRuntimeLimits,
                        agentService);
        this.runtime = runtime;

        TenantLimits tenantLimits = mock(TenantLimits.class);
        when(tenantLimits.plan(any())).thenReturn(TenantPlan.PREMIUM);
        TenantStatusGuard tenantStatusGuard = mock(TenantStatusGuard.class);
        when(tenantStatusGuard.isUsable(any())).thenReturn(true);
        FeatureFlagService featureFlags = mock(FeatureFlagService.class);
        when(featureFlags.evaluateActiveFlags(any())).thenReturn(Map.of());
        ProviderCredentialResolver credentials = mock(ProviderCredentialResolver.class);
        when(credentials.resolveActiveKey(any())).thenReturn("test-key");
        LlmGatewayService gateway = mock(LlmGatewayService.class);
        when(gateway.resolveClient(any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            gatewayModels.add(inv.<com.agenticai.core.chat.gateway.GatewayCallSpec>getArgument(1).model());
                            return chatClient;
                        });
        // The fallback provider and the delegated calls resolve their client through the two-argument form.
        when(gateway.resolveClient(any(), any())).thenReturn(chatClient);

        // --- session memory: the real service over an in-memory Redis ---
        PlatformSettingsService platformSettings = mock(PlatformSettingsService.class);
        when(platformSettings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        this.memory =
                new SessionMemoryService(
                        redis.template(),
                        new ObjectMapper().findAndRegisterModules(),
                        platformSettings,
                        new StaticListableBeanFactory()
                                .getBeanProvider(
                                        com.agenticai.core.agents.memory.SessionSummaryService
                                                .class),
                        24,
                        40,
                        32_000,
                        8_000,
                        0);
        this.memory.setToolLedgerProperties(b.toolLedger);

        // --- approval + tools: the production cycle (gate -> executor -> provider -> guard ->
        // gate) ---
        this.approvals = new ApprovalHarnessBridge();
        for (String gated : b.gatedTools) {
            approvals.requireApprovalFor("mcp_tool." + gated);
        }
        AtomicReference<AgentToolProvider> providerRef = new AtomicReference<>();
        AtomicReference<com.agenticai.core.agents.turn.ApprovalContinuationPort> launcherRef = new AtomicReference<>();
        this.continuationProperties = new ApprovalContinuationProperties();
        b.continuationTuning.accept(continuationProperties);
        org.springframework.beans.factory.support.StaticListableBeanFactory textBeans =
                new org.springframework.beans.factory.support.StaticListableBeanFactory();
        textBeans.addBean("continuationProperties", continuationProperties);
        ObjectProvider<ApprovalContinuationProperties> continuationTexts =
                textBeans.getBeanProvider(ApprovalContinuationProperties.class);
        this.executor =
                new AgentToolCallApprovalExecutor(
                        HarnessProviders.lazy(providerRef),
                        approvals.requestRepository(),
                        HarnessProviders.provider(notifications),
                        HarnessProviders.provider(memory),
                        new StaticListableBeanFactory()
                                .getBeanProvider(
                                        com.agenticai.core.agents.job.AgentJobLookup.class),
                        HarnessProviders.lazy(launcherRef),
                        continuationTexts,
                        new ObjectMapper(),
                        approvals.eventPublisher());
        List<ApprovalActionExecutor> allExecutors = new ArrayList<>(List.of(executor));
        for (java.util.function.Function<ApprovalHarnessBridge, ApprovalActionExecutor> extra : b.extraExecutors) {
            allExecutors.add(extra.apply(approvals));
        }
        for (String actionType : b.gatedActions) {
            approvals.requireApprovalFor(actionType);
        }
        for (String actionType : b.allowedActions) {
            approvals.allowWithoutApproval(actionType);
        }
        this.gate = approvals.gateService(allExecutors);
        // The gate reports how every approval ended through the same port the application wires: this is what makes
        // a code-workspace run, an apply or a channel send resume the agent without its executor knowing how.
        gate.setOutcomePort(
                new com.agenticai.core.agents.tools.ApprovalOutcomeFollowUp(
                        HarnessProviders.provider(memory),
                        HarnessProviders.lazy(launcherRef),
                        continuationTexts,
                        new ObjectMapper()));
        List<ToolCallback> allTools = new ArrayList<ToolCallback>(tools);
        for (java.util.function.Function<HumanApprovalGateService, ToolCallback> factory : b.selfGatingTools) {
            allTools.add(factory.apply(gate));
        }
        // The tool-call log is the one thing besides session memory the continuation reads (which
        // tools the conversation already used), so the stub keeps what the guard chain writes.
        ToolCallLogRepository toolCallLogs = mock(ToolCallLogRepository.class);
        when(toolCallLogs.save(any()))
                .thenAnswer(
                        inv -> {
                            ToolCallLog row = inv.getArgument(0);
                            toolCallLogRows.add(row);
                            return row;
                        });
        when(toolCallLogs
                        .findTop200ByAgentIdAndSessionIdAndStartedAtGreaterThanEqualOrderByStartedAtAsc(
                                any(), anyString(), any()))
                .thenAnswer(
                        inv ->
                                toolCallLogRows.stream()
                                        .filter(r -> inv.getArgument(0).equals(r.getAgentId()))
                                        .filter(r -> inv.getArgument(1).equals(r.getSessionId()))
                                        .filter(
                                                r ->
                                                        r.getStartedAt() != null
                                                                && !r.getStartedAt()
                                                                        .isBefore(
                                                                                inv.getArgument(
                                                                                        2,
                                                                                        java.time
                                                                                                .Instant
                                                                                                .class)))
                                        .toList());
        // The whole log of one run, oldest first: what a turn reads back as evidence of its tool calls.
        when(toolCallLogs.findByAgentIdAndSessionIdOrderByStartedAtAsc(any(), anyString()))
                .thenAnswer(
                        inv ->
                                toolCallLogRows.stream()
                                        .filter(r -> inv.getArgument(0).equals(r.getAgentId()))
                                        .filter(r -> inv.getArgument(1).equals(r.getSessionId()))
                                        .toList());
        this.toolCallLogService = new ToolCallLogService(toolCallLogs);
        com.agenticai.core.agents.tools.AgentToolGovernance governance = null;
        if (b.classifications) {
            // The classification registry over the compiled-in declarations (no stored rows), a real lethal-trifecta
            // service over it and the agent's allow-list, the platform guardrail tuning and an audit log: the governance
            // the application wires, so a tool is judged by its REAL classification.
            com.agenticai.core.agents.tools.classification.ToolClassificationRepository classificationRows =
                    mock(com.agenticai.core.agents.tools.classification.ToolClassificationRepository.class);
            when(classificationRows.findByToolName(anyString())).thenReturn(java.util.Optional.empty());
            when(classificationRows.findPatternRows()).thenReturn(List.of());
            io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            com.agenticai.core.agents.tools.classification.ToolClassificationRegistry classificationRegistry =
                    new com.agenticai.core.agents.tools.classification.ToolClassificationRegistry(classificationRows, meters);
            governance =
                    new com.agenticai.core.agents.tools.AgentToolGovernance(
                            HarnessProviders.provider(classificationRegistry),
                            HarnessProviders.provider(meters),
                            new StaticListableBeanFactory()
                                    .getBeanProvider(com.agenticai.core.chat.gateway.LlmGatewayToolOutputRedactor.class),
                            true,
                            "");
            governance.setTrifectaEnforcement(
                    new com.agenticai.core.agents.tools.TrifectaEnforcementService(
                            HarnessProviders.provider(
                                    new com.agenticai.core.agents.tools.classification.LethalTrifectaDetector(
                                            classificationRegistry, HarnessProviders.provider(meters))),
                            HarnessProviders.provider(agentService),
                            new StaticListableBeanFactory()
                                    .getBeanProvider(com.agenticai.core.agents.tools.AgentToolPort.class),
                            HarnessProviders.provider(meters),
                            "warn",
                            true,
                            false,
                            true));
            governance.setGuardrailTuning(b.guardrailTuning);
            governance.setAuditLog(HarnessProviders.provider(auditLog));
        }
        this.toolProvider =
                ToolHarnessBridge.provider(
                        agentService,
                        new PatternBasedGuardrailService(b.toolOutput),
                        approvals.policyService(),
                        gate,
                        toolCallLogs,
                        b.maxToolCallsPerTurn,
                        allTools,
                        message -> {
                            // A replay's lookup-by-name resolves with no message: not a turn.
                            if (message != null) {
                                toolSelectionMessages.add(message);
                            }
                        },
                        b.stepLimits,
                        b.toolOutput,
                        b.toolLedger,
                        runtime,
                        settingsRef::get,
                        governance);
        providerRef.set(toolProvider);

        ChatFinishProperties finishProperties = new ChatFinishProperties();
        b.finishTuning.accept(finishProperties);
        // The behaviour settings catalog over the SAME property objects the runtime reads, so an override in the
        // store is the only thing that can make a use site differ from its property.
        if (b.settingsCatalog) {
            com.agenticai.core.settings.SettingsProperties noCache = new com.agenticai.core.settings.SettingsProperties();
            noCache.setCacheTtl(Duration.ZERO);
            this.settingsResolver =
                    new com.agenticai.core.settings.DefaultSettingsResolver(
                            new com.agenticai.core.settings.SettingsRegistry(
                                    List.of(
                                            new com.agenticai.core.agents.limits.StepLimitSettings(b.stepLimits),
                                            new com.agenticai.core.agents.settings.ProductControlsCatalog(
                                                    b.semanticCache,
                                                    new com.agenticai.core.agents.turn.ChatLimitsProperties(),
                                                    b.followUp,
                                                    b.memory,
                                                    b.toolLedger),
                                            new com.agenticai.core.agents.settings.BehaviourSettingsCatalog(
                                                    continuationProperties,
                                                    new com.agenticai.core.approval.ApprovalExpiryProperties(),
                                                    finishProperties,
                                                    b.retry,
                                                    b.cache,
                                                    b.followUp,
                                                    b.continuationPolicy,
                                                    b.toolLoop,
                                                    b.routing,
                                                    b.memory,
                                                    b.guardrailTuning,
                                                    new com.agenticai.core.agents.auxiliary.AuxiliaryModelProperties()))),
                            settingsStore,
                            List.of(),
                            noCache,
                            null);
            settingsRef.set(this.settingsResolver);
            // The guardrail checks read their strictness settings through this static holder, as the application's
            // GuardrailSettingsConfiguration sets it at startup; released in close().
            com.agenticai.core.guardrails.GuardrailSettings.use(this.settingsResolver);
            runtime.useSettings(settingsRef::get);
            this.memory.setLedgerSettings(this.settingsResolver);
        } else {
            this.settingsResolver = null;
        }
        com.agenticai.core.agents.turn.quality.QueryComplexityClassifier costClassifier = null;
        if (b.costRouting) {
            costClassifier =
                    new com.agenticai.core.agents.turn.quality.QueryComplexityClassifier(
                            new StaticListableBeanFactory().getBeanProvider(com.agenticai.core.agents.decision.DecisionEngine.class),
                            false,
                            0.75,
                            new StaticListableBeanFactory().getBeanProvider(com.agenticai.core.agents.turn.quality.RoutingSimpleProperties.class));
            if (settingsResolver != null) {
                costClassifier.setSettingsResolver(settingsResolver);
            }
        }
        this.chatService =
                TurnHarnessBridge.chatService(
                        agentService,
                        credentials,
                        gateway,
                        memory,
                        toolProvider,
                        tenantLimits,
                        tenantStatusGuard,
                        featureFlags,
                        b.maxContinuations,
                        new TurnHarnessBridge.Extras(
                                b.responseCache,
                                b.judge,
                                finishProperties,
                                new TurnHarnessBridge.Tuning(
                                        b.streamIdle,
                                        b.bufferedTurn,
                                        b.resilience,
                                        new LlmRetryPolicy(b.retry, () -> 0.0),
                                        b.realErrorTranslation,
                                        b.continuationPolicy,
                                        b.followUp,
                                        costClassifier,
                                        b.toolLedger,
                                        b.parity,
                                        b.cache,
                                        runtime,
                                        settingsResolver),
                                toolCallLogService,
                                b.guardrailEngine));
        // The server-side continuation, wired as in production: the launcher starts an ordinary
        // chat turn through the same AgentChatService the tests drive directly.
        this.continuationFactory =
                new ApprovalContinuationFactory(
                        new ObjectMapper(),
                        gate,
                        HarnessProviders.provider(toolCallLogService),
                        continuationProperties);
        this.continuationPrompt = new ApprovalContinuationPrompt(continuationProperties);
        this.continuationClaims =
                new ApprovalContinuationClaims(
                        HarnessProviders.provider(redis.template()), continuationProperties);
        this.continuationLauncher =
                new ApprovalContinuationLauncher(
                        chatService,
                        continuationFactory,
                        continuationPrompt,
                        continuationClaims,
                        continuationProperties);
        if (settingsResolver != null) {
            continuationLauncher.setSettingsResolver(settingsResolver);
        }
        launcherRef.set(continuationLauncher);
    }

    /**
     * A tenant-scope override in the harness's settings catalog ({@link Builder#settingsCatalog}), as the tenant admin's
     * write leaves it: the very next turn reads it.
     */
    public void tenantSetting(String key, Object value) {
        writeSetting(com.agenticai.core.settings.SettingScope.TENANT, "", key, value);
    }

    /** An agent-scope override for the harness's agent. */
    public void agentSetting(String key, Object value) {
        writeSetting(com.agenticai.core.settings.SettingScope.AGENT, agentId.toString(), key, value);
    }

    private void writeSetting(com.agenticai.core.settings.SettingScope scope, String scopeId, String key, Object value) {
        if (settingsResolver == null) {
            throw new IllegalStateException("build the harness with settingsCatalog() to set settings");
        }
        TenantContext.set(tenant);
        settingsStore.saveCurrent(
                new com.agenticai.core.settings.SettingOverride(
                        scope, scopeId, key, value, 1, "test", java.time.Instant.now()));
    }

    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------------ driving a conversation

    /** One user message through the real turn pipeline; the reply as the separate chunks the person's client receives. */
    public List<String> chatChunks(String userMessage) {
        TenantContext.set(tenant);
        ChatTurnRequest request =
                ChatTurnRequest.builder(agentId, sessionId, subject, userMessage).build();
        List<String> chunks =
                chatService
                        .chat(TurnContext.forEntry(tenant, request), request)
                        .collectList()
                        .block(Duration.ofSeconds(60));
        return chunks == null ? List.of() : chunks;
    }

    /**
     * One user message through the real turn pipeline for a client that asked for step events: the reply, how the
     * stream ended, and the step events the turn sent beside it (see {@link StepEventTurn}).
     */
    public StepEventTurn chatWithStepEvents(String userMessage) {
        return chatWithStepEvents(userMessage, null);
    }

    /** As {@link #chatWithStepEvents(String)} for the turn the platform starts after a human decided {@code continuation}'s call. */
    public StepEventTurn chatWithStepEvents(
            String userMessage, com.agenticai.core.agents.turn.ApprovalContinuation continuation) {
        TenantContext.set(tenant);
        com.agenticai.core.agents.tools.AgentStepEventEmitter events =
                com.agenticai.core.agents.tools.AgentStepEventEmitter.active();
        ChatTurnRequest.Builder builder =
                ChatTurnRequest.builder(agentId, sessionId, subject, userMessage).stepEvents(events);
        ChatTurnRequest request = (continuation == null ? builder : builder.continuation(continuation)).build();
        String reply = null;
        Throwable failure = null;
        try {
            List<String> chunks =
                    chatService
                            .chat(TurnContext.forEntry(tenant, request), request)
                            .collectList()
                            .block(Duration.ofSeconds(60));
            reply = chunks == null ? "" : String.join("", chunks);
        } catch (RuntimeException e) {
            failure = e;
        }
        events.complete();
        List<String> wire = events.asFlux().collectList().block(Duration.ofSeconds(5));
        return StepEventTurn.of(reply, failure, wire == null ? List.of() : wire);
    }

    /** One user message through the real turn pipeline; returns the streamed reply text. */
    public String chat(String userMessage) {
        return chat(userMessage, sessionId);
    }

    public String chat(String userMessage, String onSession) {
        return chat(userMessage, onSession, null);
    }

    /**
     * As {@link #chat(String)} for a turn with an enclosing deadline - an agent job's own timeout - which
     * the buffered depths use as their time limit when it is longer than {@code buffered-turn-timeout}.
     */
    public String chatWithDeadline(String userMessage, Duration fromNow) {
        return chat(userMessage, sessionId, java.time.Instant.now().plus(fromNow));
    }

    /**
     * As {@link #chat(String)} for a run that carries request-layer {@code caps} (an agent job's remaining tool
     * budget) and an enclosing deadline, the way {@code AgentJobTurns} builds its turn. Either may be null.
     */
    public String chatWithCaps(String userMessage, RuntimeLimits caps, Duration deadlineFromNow) {
        TenantContext.set(tenant);
        ChatTurnRequest request =
                ChatTurnRequest.builder(agentId, sessionId, subject, userMessage).caps(caps).build();
        TurnContext context =
                TurnContext.forEntry(tenant, request)
                        .withDeadline(deadlineFromNow == null ? null : java.time.Instant.now().plus(deadlineFromNow));
        List<String> chunks =
                chatService.chat(context, request).collectList().block(Duration.ofSeconds(60));
        return chunks == null ? "" : String.join("", chunks);
    }

    /**
     * One user message with a run-scoped tool selection ({@code ChatRequest.toolNames}): the run may use only these
     * of the agent's tools. {@code null} is "no selection", an empty list "no tools for this run".
     */
    public String chatWithTools(String userMessage, List<String> toolNames) {
        TenantContext.set(tenant);
        ChatTurnRequest request =
                ChatTurnRequest.builder(agentId, sessionId, subject, userMessage).toolNames(toolNames).build();
        List<String> chunks =
                chatService
                        .chat(TurnContext.forEntry(tenant, request), request)
                        .collectList()
                        .block(Duration.ofSeconds(60));
        return chunks == null ? "" : String.join("", chunks);
    }

    /** As {@link #chatWithTools}; the error the turn ends with (the refusal of an unusable selection), or null. */
    public Throwable chatWithToolsFailure(String userMessage, List<String> toolNames) {
        try {
            chatWithTools(userMessage, toolNames);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    /** The resolver the turns and the tool guard read their runtime limits from. */
    public AgentRuntimeSettingsResolver runtime() {
        return runtime;
    }

    /** The error the turn's reply stream ends with, or {@code null} when it completes. */
    public Throwable chatFailure(String userMessage) {
        TenantContext.set(tenant);
        ChatTurnRequest request =
                ChatTurnRequest.builder(agentId, sessionId, subject, userMessage).build();
        try {
            chatService.chat(TurnContext.forEntry(tenant, request), request)
                    .collectList()
                    .block(Duration.ofSeconds(60));
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private String chat(String userMessage, String onSession, java.time.Instant deadline) {
        TenantContext.set(tenant);
        ChatTurnRequest request =
                ChatTurnRequest.builder(agentId, onSession, subject, userMessage).build();
        TurnContext context = TurnContext.forEntry(tenant, request).withDeadline(deadline);
        List<String> chunks =
                chatService.chat(context, request).collectList().block(Duration.ofSeconds(60));
        return chunks == null ? "" : String.join("", chunks);
    }

    /**
     * The newest approval request still waiting for a human, or fails. A turn that parked a gated
     * call must have left exactly one.
     */
    public ApprovalRequest pendingApproval() {
        List<ApprovalRequest> pending =
                approvals.requests().stream()
                        .filter(r -> r.getStatus() == ApprovalStatus.PENDING)
                        .toList();
        if (pending.size() != 1) {
            throw new AssertionError(
                    "expected exactly one PENDING approval request, found "
                            + pending.size()
                            + ": "
                            + describe(pending));
        }
        return pending.get(0);
    }

    /** The human clicks Approve: the real service, which runs the real executor. */
    public ApprovalRequest approvePending() {
        ApprovalRequest pending = pendingApproval();
        historySizeBeforeDecision = storedTranscript().size();
        try {
            return gate.approve(pending.getId(), APPROVER);
        } finally {
            // The approving thread must not keep tenant state or a replay grant (a pooled thread in
            // production); the harness thread is reused by the next assertion.
            ApprovalReplayGrant.clear();
            TenantContext.set(tenant);
        }
    }

    /** The human clicks Deny with {@code reason}: the real service and executor hook. */
    public ApprovalRequest rejectPending(String reason) {
        ApprovalRequest pending = pendingApproval();
        historySizeBeforeDecision = storedTranscript().size();
        try {
            return gate.reject(pending.getId(), APPROVER, reason);
        } finally {
            ApprovalReplayGrant.clear();
            TenantContext.set(tenant);
        }
    }

    /**
     * Waits for the turn the SERVER starts after a decision, with no message from the client, and
     * returns the assistant reply that turn produced. The continuation is recognised the way the
     * web recognises it: a stored USER turn that opens with the {@value
     * #CONTINUATION_MARKER} tag, followed by an assistant turn. Fails with the transcript when none
     * appears in time, which is exactly the symptom of "approved, and nothing happened".
     */
    public String awaitContinuation() {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        List<ChatTurn> history = storedTranscript();
        while (System.nanoTime() < deadline) {
            history = storedTranscript();
            for (int i = Math.min(historySizeBeforeDecision, history.size()); i < history.size(); i++) {
                ChatTurn turn = history.get(i);
                if (turn.role() == MessageRole.USER
                        && turn.content() != null
                        && turn.content().startsWith(CONTINUATION_MARKER)) {
                    for (int j = i + 1; j < history.size(); j++) {
                        if (history.get(j).role() == MessageRole.ASSISTANT) {
                            return history.get(j).content();
                        }
                    }
                }
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the continuation", e);
            }
        }
        StringBuilder transcript = new StringBuilder();
        for (ChatTurn t : history) {
            transcript.append(t.role()).append(": ").append(t.content()).append(" | ");
        }
        throw new AssertionError(
                "no server-side continuation turn appeared within 15s of the human's decision; the model was called "
                        + llm.modelCalls()
                        + " time(s) in total. Stored conversation: "
                        + transcript);
    }

    // --------------------------------------------------------------------------- inspection

    public ScriptedLlm llm() {
        return llm;
    }

    public RecordingTool tool(String name) {
        return tools.stream()
                .filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no harness tool named " + name));
    }

    public UUID agentId() {
        return agentId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String tenantId() {
        return tenantId;
    }

    public String subject() {
        return subject;
    }

    /** The real turn service, for a caller (an agent job) that runs turns itself. */
    public AgentChatService chatService() {
        return chatService;
    }

    public HumanApprovalGateService approvalService() {
        return gate;
    }

    public AgentToolCallApprovalExecutor executor() {
        return executor;
    }

    /** The model each client the turns asked the LLM gateway for was built for, in order. */
    public List<String> modelsRequested() {
        return List.copyOf(gatewayModels);
    }

    /** The message each tool resolution selected tools for, in order (one per turn). */
    public List<String> toolSelectionMessages() {
        return List.copyOf(toolSelectionMessages);
    }

    public ApprovalContinuationFactory continuationFactory() {
        return continuationFactory;
    }

    public ApprovalContinuationPrompt continuationPrompt() {
        return continuationPrompt;
    }

    public ApprovalContinuationClaims continuationClaims() {
        return continuationClaims;
    }

    public ApprovalContinuationProperties continuationProperties() {
        return continuationProperties;
    }

    /** Makes every tool call logged so far {@code by} older, as if that much time had passed since the conversation used them. */
    public void ageToolCallLog(Duration by) {
        for (ToolCallLog row : toolCallLogRows) {
            org.springframework.test.util.ReflectionTestUtils.setField(row, "startedAt", row.getStartedAt().minus(by));
        }
    }

    public ToolCallLogService toolCallLogService() {
        return toolCallLogService;
    }

    public InMemoryRedis redis() {
        return redis;
    }

    public List<ApprovalRequest> approvalRequests() {
        return approvals.requests();
    }

    /** The status of every approval request recorded so far, oldest first. */
    public List<ApprovalStatus> requestStatuses() {
        return approvals.requests().stream().map(ApprovalRequest::getStatus).toList();
    }

    /**
     * Delivers approval-stack events (e.g. "execution recorded") to a listener, like Spring would.
     */
    public void onApprovalEvent(java.util.function.Consumer<ApplicationEvent> listener) {
        approvals.onEvent(listener);
    }

    public ApprovalHarnessBridge approvals() {
        return approvals;
    }

    /**
     * The key the turn reads and writes this conversation's history under: the SEC-004 scoped id
     * {@code ChatTurnPreflight} derives. The same value production computes, from the same inputs.
     */
    public String scopedMemoryKey() {
        return SessionMemoryService.scopedSessionId(tenantId, agentId, subject, sessionId);
    }

    /** The conversation's history as the NEXT turn will replay it (read through the scoped key). */
    public List<ChatTurn> historyTheNextTurnWillSee() {
        return memory.getHistory(scopedMemoryKey());
    }

    /**
     * Everything stored for the conversation, including the replies that are kept out of what the
     * model is shown again (a failed reply): the transcript a person reloads.
     */
    public List<ChatTurn> storedTranscript() {
        return memory.getFullHistory(scopedMemoryKey());
    }

    /**
     * The history stored under the RAW client session id, where a writer that skips scoping lands.
     */
    public List<ChatTurn> historyUnderRawSessionId() {
        return memory.getHistory(sessionId);
    }

    @Override
    public void close() {
        TenantContext.clear();
        ApprovalReplayGrant.clear();
        if (settingsResolver != null) {
            com.agenticai.core.guardrails.GuardrailSettings.release(settingsResolver);
        }
    }

    /** The audit log the tool guard recorded its reviewable decisions in (a mock; needs {@link Builder#classifications()}). */
    public com.agenticai.core.audit.AuditLogService auditLog() {
        return auditLog;
    }

    // ------------------------------------------------------------------------------ wiring

    /**
     * A ChatClient over {@code model}, built through the same public seams the provider factories
     * use.
     */
    public static ChatClient chatClientOver(ScriptedLlm model) {
        LlmModelSupport support =
                new LlmModelSupport(
                        new StaticListableBeanFactory().getBeanProvider(ObservationRegistry.class),
                        new StaticListableBeanFactory()
                                .getBeanProvider(com.agenticai.core.chat.DefaultAgentAdvisor.class),
                        2,
                        Duration.ofMillis(1),
                        Duration.ofMillis(2),
                        true,
                        "MISTRAL",
                        "low",
                        4,
                        Duration.ofMinutes(1),
                        false);
        return support.chatClientBuilder(model).defaultAdvisors(support.advisors()).build();
    }

    private static String describe(List<ApprovalRequest> requests) {
        StringBuilder out = new StringBuilder();
        for (ApprovalRequest r : requests) {
            out.append(r.getActionType()).append('/').append(r.getStatus()).append(' ');
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------------- builder

    public static final class Builder {
        private final List<RecordingTool> tools = new ArrayList<>();
        private final List<String> gatedTools = new ArrayList<>();
        private final List<java.util.function.Function<HumanApprovalGateService, ToolCallback>> selfGatingTools =
                new ArrayList<>();
        private final List<java.util.function.Function<ApprovalHarnessBridge, ApprovalActionExecutor>> extraExecutors =
                new ArrayList<>();
        private final List<String> gatedActions = new ArrayList<>();
        private final List<String> allowedActions = new ArrayList<>();
        private ScriptedLlm.Responder responder;
        private AgentAutonomyTier autonomy = AgentAutonomyTier.SEMI_AUTONOMOUS;
        private String systemPrompt = "You are a helpful assistant.";
        private String sessionId = "sess-" + UUID.randomUUID();
        private UUID agentId = UUID.randomUUID();
        private String tenantId = DEFAULT_TENANT_ID;
        private String subject = DEFAULT_SUBJECT;
        private int maxContinuations = 8;
        private int maxToolCallsPerTurn = 15;
        private java.util.function.Consumer<ApprovalContinuationProperties> continuationTuning =
                p -> {};
        private ReasoningDepth depth = ReasoningDepth.FAST;
        private com.agenticai.core.agents.turn.cache.LlmResponseCacheService responseCache;
        private com.agenticai.core.agents.turn.quality.LlmQualityJudgeService judge;
        private java.util.function.Consumer<ChatFinishProperties> finishTuning = p -> {};
        private final com.agenticai.core.guardrails.ToolStepLimitProperties stepLimits =
                new com.agenticai.core.guardrails.ToolStepLimitProperties();
        private final com.agenticai.core.guardrails.ToolOutputProperties toolOutput =
                new com.agenticai.core.guardrails.ToolOutputProperties();
        private final com.agenticai.core.chat.ToolLoopProperties toolLoop =
                new com.agenticai.core.chat.ToolLoopProperties();

        private final com.agenticai.core.agents.turn.ToolLedgerProperties toolLedger =
                new com.agenticai.core.agents.turn.ToolLedgerProperties();
        private final com.agenticai.core.agents.turn.ChatParityProperties parity =
                new com.agenticai.core.agents.turn.ChatParityProperties();
        private final com.agenticai.core.agents.turn.ChatCacheProperties cache =
                new com.agenticai.core.agents.turn.ChatCacheProperties();
        private final com.agenticai.core.agents.turn.quality.RoutingSimpleProperties routing =
                new com.agenticai.core.agents.turn.quality.RoutingSimpleProperties();
        private final com.agenticai.core.agents.memory.MemoryLimitsProperties memory =
                new com.agenticai.core.agents.memory.MemoryLimitsProperties();
        private final com.agenticai.core.agents.turn.cache.SemanticCacheProperties semanticCache =
                new com.agenticai.core.agents.turn.cache.SemanticCacheProperties();
        private boolean settingsCatalog;
        private boolean classifications;
        private Set<String> allowedTools = Set.of();
        private final com.agenticai.core.guardrails.GuardrailTuningProperties guardrailTuning =
                new com.agenticai.core.guardrails.GuardrailTuningProperties();
        private Set<String> enabledGuardrails = Set.of();
        private com.agenticai.core.guardrails.ContentGuardrailEngine guardrailEngine;
        private boolean costRouting;
        private java.time.Duration streamIdle;
        private java.time.Duration bufferedTurn;
        private final com.agenticai.core.agents.turn.ContinuationProperties continuationPolicy =
                new com.agenticai.core.agents.turn.ContinuationProperties();
        private final com.agenticai.core.agents.turn.FollowUpProperties followUp =
                new com.agenticai.core.agents.turn.FollowUpProperties();
        private ChatResilienceProperties resilience = new ChatResilienceProperties();
        private final LlmRetryProperties retry = defaultNoWaitRetry();
        private boolean realErrorTranslation;
        private boolean fallbackProvider;
        private RuntimeLimits agentLimits = RuntimeLimits.none();
        private RuntimeLimits tenantLimits = RuntimeLimits.none();
        private java.util.function.Consumer<AgentRuntimeProperties> runtimeTuning = p -> {};

        /**
         * The turn reads its curated behaviour settings (docs/settings.md) from a real catalog over this harness's
         * property objects; {@link ApprovalTurnHarness#tenantSetting} and {@link ApprovalTurnHarness#agentSetting} then
         * change what the next turn does. Without it every setting is its platform value.
         */
        public Builder settingsCatalog() {
            this.settingsCatalog = true;
            return this;
        }

        /**
         * The tool guard judges tools by their REAL classification (the compiled-in declarations, no stored rows) and
         * holds a real lethal-trifecta service over the agent's allow-list ({@link #allowedTools}), the platform
         * guardrail tuning ({@link #guardrailTuning}) and an audit log ({@link ApprovalTurnHarness#auditLog()}).
         * Without it the guard has no classification at all, as in every other harness test.
         */
        public Builder classifications() {
            this.classifications = true;
            return this;
        }

        /** The agent's tool allow-list (empty = Auto-select); what the trifecta analysis reads. */
        public Builder allowedTools(String... toolNames) {
            this.allowedTools = Set.of(toolNames);
            return this;
        }

        /** The platform's {@code app.guardrails.*} tuning object (also the settings catalog's platform values). */
        public Builder guardrailTuning(java.util.function.Consumer<com.agenticai.core.guardrails.GuardrailTuningProperties> tuning) {
            tuning.accept(this.guardrailTuning);
            return this;
        }

        /** The agent's own runtime-limit overrides ({@code app.agent-runtime}; the agent layer). */
        public Builder agentLimits(RuntimeLimits limits) {
            this.agentLimits = limits;
            return this;
        }

        /** The tenant's runtime-limit defaults (the tenant layer). */
        public Builder tenantLimits(RuntimeLimits limits) {
            this.tenantLimits = limits;
            return this;
        }

        /** Overrides {@code app.agent-runtime.*} (the bounds, the new platform defaults) for this harness. */
        public Builder runtimeProperties(java.util.function.Consumer<AgentRuntimeProperties> tuning) {
            this.runtimeTuning = tuning;
            return this;
        }

        /** Retries with no real waiting: one attempt, so a test opts into retries explicitly. */
        private static LlmRetryProperties defaultNoWaitRetry() {
            LlmRetryProperties p = new LlmRetryProperties();
            p.setInitialBackoff(java.time.Duration.ofMillis(1));
            p.setMaxBackoff(java.time.Duration.ofMillis(2));
            return p;
        }

        /** {@code app.llm.http.stream-idle-timeout} and {@code buffered-turn-timeout} for this harness. */
        public Builder timeouts(java.time.Duration streamIdle, java.time.Duration bufferedTurn) {
            this.streamIdle = streamIdle;
            this.bufferedTurn = bufferedTurn;
            return this;
        }

        /**
         * Overrides {@code app.chat.continuation.*} (when a stopped reply is re-prompted, and the patterns
         * that say so) for this harness. The harness agent runs on a BYOK OPENAI provider, which is not a
         * weak tool caller, so a test of the wording check sets the scope to ALL_MODELS or lists OPENAI.
         */
        public Builder continuationPolicy(
                java.util.function.Consumer<com.agenticai.core.agents.turn.ContinuationProperties> tuning) {
            tuning.accept(continuationPolicy);
            return this;
        }

        /**
         * Cost routing on: the agent has an economy model ({@link ApprovalTurnHarness#ECONOMY_MODEL}) and a
         * message the heuristic classifier judges simple is sent to it instead of the primary one.
         */
        public Builder costRouting() {
            this.costRouting = true;
            return this;
        }

        /**
         * The agent has these content guardrails switched on (for example {@code "profanity"}), enforced by
         * the real {@link com.agenticai.core.guardrails.ContentGuardrailEngine} over {@code implementations}.
         */
        public Builder contentGuardrails(
                Set<String> enabled, java.util.List<com.agenticai.core.guardrails.ContentGuardrail> implementations) {
            this.enabledGuardrails = Set.copyOf(enabled);
            java.util.List<com.agenticai.core.guardrails.ContentGuardrail> impls = java.util.List.copyOf(implementations);
            this.guardrailEngine =
                    new com.agenticai.core.guardrails.ContentGuardrailEngine(
                            new ObjectProvider<>() {
                                @Override
                                public java.util.List<com.agenticai.core.guardrails.ContentGuardrail> getIfAvailable() {
                                    return new ArrayList<>(impls);
                                }
                            });
            return this;
        }

        /** Overrides {@code app.chat.tool-ledger.*} (the stored, replayed record of what tools did) for this harness. */
        public Builder toolLedger(
                java.util.function.Consumer<com.agenticai.core.agents.turn.ToolLedgerProperties> tuning) {
            tuning.accept(toolLedger);
            return this;
        }

        /** Overrides {@code app.chat.cache.*} (what the response caches may serve and what they key on) for this harness. */
        public Builder cache(
                java.util.function.Consumer<com.agenticai.core.agents.turn.ChatCacheProperties> tuning) {
            tuning.accept(cache);
            return this;
        }

        /** Overrides {@code app.chat.parity.*} (the explicit stream-versus-buffered differences) for this harness. */
        public Builder parity(
                java.util.function.Consumer<com.agenticai.core.agents.turn.ChatParityProperties> tuning) {
            tuning.accept(parity);
            return this;
        }

        /** Overrides {@code app.chat.follow-up.*} (short follow-up handling) for this harness. */
        public Builder followUp(
                java.util.function.Consumer<com.agenticai.core.agents.turn.FollowUpProperties> tuning) {
            tuning.accept(followUp);
            return this;
        }

        /** Overrides {@code app.chat.resilience.*} for this harness. */
        public Builder resilience(java.util.function.Consumer<ChatResilienceProperties> tuning) {
            tuning.accept(resilience);
            return this;
        }

        /** Overrides {@code app.llm.retry.*} (backoff is clamped to a few milliseconds by default). */
        public Builder retry(java.util.function.Consumer<LlmRetryProperties> tuning) {
            tuning.accept(retry);
            return this;
        }

        /** Provider errors are classified by the real translator (the default mock maps every error to null). */
        public Builder realErrorTranslation() {
            this.realErrorTranslation = true;
            return this;
        }

        /** The agent has a BYOK fallback provider, which the harness answers with the same scripted model. */
        public Builder fallbackProvider() {
            this.fallbackProvider = true;
            return this;
        }

        public Builder tool(RecordingTool tool) {
            tools.add(tool);
            return this;
        }

        /**
         * A tool that holds its OWN approval inside {@code call()} (a code-workspace run, a channel send) rather than
         * leaving it to the tool guard; built over the harness's real gate, wrapped by the same guard chain as any tool.
         */
        public Builder selfGatingTool(java.util.function.Function<HumanApprovalGateService, ToolCallback> factory) {
            selfGatingTools.add(factory);
            return this;
        }

        /** An extra executor registered next to the tool-replay one (for a code-workspace or channel action type). */
        public Builder executor(java.util.function.Function<ApprovalHarnessBridge, ApprovalActionExecutor> factory) {
            extraExecutors.add(factory);
            return this;
        }

        /** Tenant policy: this action type is explicitly allowed to run without a human. */
        public Builder allowWithoutApproval(String actionType) {
            allowedActions.add(actionType);
            return this;
        }

        /** Tenant policy: this action type (not a tool name) needs a human. */
        public Builder gateAction(String actionType) {
            gatedActions.add(actionType);
            return this;
        }

        /** Tenant policy: calls to {@code toolName} need a human ({@code mcp_tool.<name>}). */
        public Builder gateTool(String toolName) {
            gatedTools.add(toolName);
            return this;
        }

        public Builder responder(ScriptedLlm.Responder responder) {
            this.responder = responder;
            return this;
        }

        public Builder autonomy(AgentAutonomyTier autonomy) {
            this.autonomy = autonomy;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /** The agent id; set it when another component (an agent job) already names the agent. */
        public Builder agentId(UUID agentId) {
            this.agentId = agentId;
            return this;
        }

        /** The tenant and human subject the conversation runs as. */
        public Builder tenant(String tenantId, String subject) {
            this.tenantId = tenantId;
            this.subject = subject;
            return this;
        }

        /** {@code app.chat.continuation.max-passes}; the production default is 8. */
        public Builder maxContinuations(int maxContinuations) {
            this.maxContinuations = maxContinuations;
            return this;
        }

        /** {@code app.agents.max-tool-calls-per-turn}; the production default is 15. */
        public Builder maxToolCallsPerTurn(int maxToolCallsPerTurn) {
            this.maxToolCallsPerTurn = maxToolCallsPerTurn;
            return this;
        }

        /** Overrides {@code app.approvals.continuation.*} for this harness. */
        public Builder continuation(
                java.util.function.Consumer<ApprovalContinuationProperties> tuning) {
            this.continuationTuning = tuning;
            return this;
        }

        /** The agent's reasoning depth; FAST (the default) streams, the others buffer the whole reply. */
        public Builder depth(ReasoningDepth depth) {
            this.depth = depth;
            return this;
        }

        /**
         * Turns response caching on for the agent and uses {@code cache} (a mock the test verifies)
         * as the exact-match response cache. Without it the agent has caching off.
         */
        public Builder responseCache(
                com.agenticai.core.agents.turn.cache.LlmResponseCacheService cache) {
            this.responseCache = cache;
            return this;
        }

        /** The quality judge (a mock the test verifies); default is an inert mock. */
        public Builder judge(com.agenticai.core.agents.turn.quality.LlmQualityJudgeService judge) {
            this.judge = judge;
            return this;
        }

        /** Overrides {@code app.guardrails.step-limit.*} for this harness. */
        public Builder stepLimits(
                java.util.function.Consumer<com.agenticai.core.guardrails.ToolStepLimitProperties> tuning) {
            tuning.accept(stepLimits);
            return this;
        }

        /** Overrides {@code app.tools.output.*} for this harness. */
        public Builder toolOutput(
                java.util.function.Consumer<com.agenticai.core.guardrails.ToolOutputProperties> tuning) {
            tuning.accept(toolOutput);
            return this;
        }

        /** Overrides {@code app.chat.tools.*} (round cap, stop wording) for this harness. */
        public Builder toolLoop(java.util.function.Consumer<com.agenticai.core.chat.ToolLoopProperties> tuning) {
            tuning.accept(toolLoop);
            return this;
        }

        /** Overrides {@code app.chat.finish.*} for this harness. */
        public Builder finish(java.util.function.Consumer<ChatFinishProperties> tuning) {
            this.finishTuning = tuning;
            return this;
        }

        public ApprovalTurnHarness build() {
            if (responder == null) {
                throw new IllegalStateException("a responder (the scripted model) is required");
            }
            return new ApprovalTurnHarness(this);
        }
    }
}
