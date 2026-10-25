package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.collab.RunBudget;
import com.agenticai.core.agents.collab.RunBudgetCaps;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.agents.tools.code.CustomCodeToolService;
import com.agenticai.core.agents.tools.code.ToolSandboxClient;
import com.agenticai.core.agents.tools.code.ToolSandboxProperties;
import com.agenticai.core.agents.tools.media.GeneratedAttachmentResolver;
import com.agenticai.core.agents.tools.messaging.ChannelControlPort;
import com.agenticai.core.agents.tools.messaging.ChannelMediaSendPort;
import com.agenticai.core.agents.tools.messaging.ChannelMessageQueryPort;
import com.agenticai.core.agents.tools.messaging.ChannelSendPort;
import com.agenticai.core.agents.tools.messaging.HumanHandoffPort;
import com.agenticai.core.agents.tools.openapi.OpenApiToolExecutor;
import com.agenticai.core.agents.tools.openapi.OpenApiToolSourceService;
import com.agenticai.core.agents.tools.script.UserToolScriptEvaluator;
import com.agenticai.core.agents.tools.script.UserToolScriptService;
import com.agenticai.core.agents.tools.search.RedisToolActivationStore;
import com.agenticai.core.agents.tools.search.ToolActivationRegistry;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agentspi.MandatePaymentToolSource;
import com.agenticai.core.agentspi.MediaToolSource;
import com.agenticai.core.agentspi.WorkspaceToolSource;
import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.billing.commerce.CheckoutSessionService;
import com.agenticai.core.codeintel.CodeIntelClient;
import com.agenticai.core.devices.DeviceDataIngestionService;
import com.agenticai.core.documentgen.service.DocumentGenerationService;
import com.agenticai.core.graphmemory.EntityExtractionService;
import com.agenticai.core.graphmemory.GraphMemoryService;
import com.agenticai.core.graphmemory.MemoryWriteArbitrationService;
import com.agenticai.core.guardrails.GuardrailService;
import com.agenticai.core.guardrails.TenantGuardrailRuleEvaluator;
import com.agenticai.core.guardrails.ToolCallVerifierService;
import com.agenticai.core.knowledge.KnowledgeCollectionService;
import com.agenticai.core.knowledge.KnowledgeIngestionService;
import com.agenticai.core.knowledge.KnowledgeSearchService;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.sdlc.E2bCredentialService;
import com.agenticai.core.sdlc.SdlcSandboxService;
import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The shared plumbing and the per-area resolvers behind the {@link ToolSource} beans. These used to
 * be built inside {@code AgentToolProvider}'s ~65-argument constructor and wired through a dozen
 * setters; here each is a bean with only the collaborators it needs, and every optional
 * cross-module capability stays an {@link ObjectProvider} so slim contexts remain constructible.
 */
@Configuration(proxyBeanMethods = false)
class ToolResolverConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ToolResolverConfiguration.class);

    @Bean
    AgentToolGuard agentToolGuard(
            ToolCallLogRepository toolCallLogRepository,
            AgentService agentService,
            GuardrailService guardrailService,
            ApprovalPolicyService approvalPolicyService,
            HumanApprovalGateService approvalGateService,
            @Value("${app.guardrails.max-tool-calls-per-turn:15}") int maxToolCallsPerTurn,
            ObjectProvider<ToolCallVerifierService> toolCallVerifierService,
            ObjectProvider<PlatformIssueService> issueService,
            ObjectProvider<TenantGuardrailRuleEvaluator> tenantGuardrailRuleEvaluator,
            ObjectProvider<com.agenticai.core.guardrails.procedures.ProcedureGuardEvaluator>
                    procedureGuardEvaluator,
            ToolCircuitBreakerService toolCircuitBreakerService,
            // Tool classification gate + per-tool metrics + tenant PII redaction of tool output.
            // Absent bean = the legacy unguarded path.
            ObjectProvider<AgentToolGovernance> agentToolGovernance,
            // MCP Apps (app.mcp.apps.enabled, default off): absent bean = feature off.
            ObjectProvider<com.agenticai.core.agents.tools.mcpapps.McpAppsService> mcpApps,
            // app.guardrails.approval-park.*: what a held call tells the model. Absent bean = the
            // module's code defaults (same text).
            ObjectProvider<com.agenticai.core.guardrails.ApprovalParkProperties> approvalPark,
            // app.guardrails.step-limit.*: what a call past the ceiling gets, and the autonomy
            // tiers'
            // scaling of it. Absent bean = the module's code defaults (same values).
            ObjectProvider<com.agenticai.core.guardrails.ToolStepLimitProperties> stepLimits,
            // app.tools.output.*: the one cap on a tool result. Absent bean = the module's code
            // defaults.
            ObjectProvider<com.agenticai.core.guardrails.ToolOutputProperties> toolOutput,
            // app.chat.tool-ledger.*: the compact record of what the turn's tools did, stored with
            // the
            // assistant turn. Absent bean = the module's code defaults (same values).
            ObjectProvider<com.agenticai.core.agents.turn.ToolLedgerProperties> toolLedger,
            // The one resolver of the per-tenant / per-agent runtime limits (tool steps, tool
            // timeout, tool
            // output cap, ...). Absent bean = every agent runs under the guard's own settings.
            ObjectProvider<com.agenticai.core.agents.runtime.AgentRuntime> runtimeSettings,
            // The prompt registry: the tool results the guard hands the model (blocked, held for
            // approval,
            // withheld, budget used up) resolve through it. Absent bean = the shipped wording.
            ObjectProvider<com.agenticai.core.prompts.PromptResolver> prompts,
            // The end of the conversation, for the RIGOROUS tool-call verifier
            // (app.guardrails.tool-verifier.context-messages). Absent bean = the current message
            // alone.
            ObjectProvider<com.agenticai.core.guardrails.ConversationTailSource> conversationTail,
            // The settings catalog: a tenant's chat.toolLedger.* decide whether the turn's tools
            // are recorded in the
            // ledger. Absent bean = app.chat.tool-ledger.* as configured.
            ObjectProvider<com.agenticai.core.settings.SettingsResolver> settings) {
        AgentToolGuard guard =
                new AgentToolGuard(
                        toolCallLogRepository,
                        agentService,
                        guardrailService,
                        approvalPolicyService,
                        approvalGateService,
                        maxToolCallsPerTurn,
                        toolCallVerifierService,
                        issueService,
                        tenantGuardrailRuleEvaluator,
                        procedureGuardEvaluator,
                        toolCircuitBreakerService,
                        agentToolGovernance.getIfAvailable());
        guard.setMcpApps(mcpApps);
        guard.setApprovalPark(approvalPark);
        guard.setStepLimitProperties(stepLimits);
        guard.setOutputProperties(toolOutput);
        guard.setToolLedger(toolLedger);
        guard.setRuntimeResolver(runtimeSettings);
        guard.setPromptResolver(prompts);
        guard.setConversationTailSource(conversationTail);
        guard.setSettings(settings);
        return guard;
    }

    /**
     * The conversation tail the RIGOROUS verifier reads, from the session memory the turn itself
     * reads.
     */
    @Bean
    com.agenticai.core.guardrails.ConversationTailSource conversationTailSource(
            ObjectProvider<com.agenticai.core.agents.memory.SessionMemoryService> sessionMemory) {
        return new SessionConversationTailSource(sessionMemory);
    }

    /**
     * One RunBudget per turn, shared by the delegation tools and deep_research (see {@link
     * TurnRunBudgets}); the per-agent cap (T4) lowers the platform cap when the agent has its own.
     */
    @Bean
    TurnRunBudgets turnRunBudgets(
            AgentService agentService,
            @Value("${app.guardrails.run-budget.usd:2.0}") BigDecimal runBudgetUsd,
            @Value("${app.guardrails.run-budget.input-usd-per-m:3.00}")
                    BigDecimal runBudgetInputUsdPerM,
            @Value("${app.guardrails.run-budget.output-usd-per-m:15.00}")
                    BigDecimal runBudgetOutputUsdPerM,
            @Value("${app.guardrails.run-budget.avg-branch-tokens:8000}")
                    long runBudgetAvgBranchTokens,
            @Value("${app.guardrails.run-budget.per-agent-override-enabled:true}")
                    boolean perAgentRunBudgetEnabled,
            @Value("${app.guardrails.run-budget.max-per-agent-usd:10}")
                    BigDecimal maxPerAgentRunBudgetUsd) {
        BigDecimal maxPerAgent =
                maxPerAgentRunBudgetUsd == null ? BigDecimal.ZERO : maxPerAgentRunBudgetUsd;
        return new TurnRunBudgets(
                (UUID turnAgentId) ->
                        new RunBudget(
                                effectiveRunCap(
                                        agentService,
                                        perAgentRunBudgetEnabled,
                                        maxPerAgent,
                                        runBudgetUsd,
                                        turnAgentId),
                                runBudgetInputUsdPerM,
                                runBudgetOutputUsdPerM,
                                runBudgetAvgBranchTokens));
    }

    /**
     * Platform cap lowered by the agent's own cap when one is set; any read failure keeps the
     * platform cap.
     */
    private static BigDecimal effectiveRunCap(
            AgentService agentService,
            boolean perAgentRunBudgetEnabled,
            BigDecimal maxPerAgentRunBudgetUsd,
            BigDecimal globalCapUsd,
            UUID agentId) {
        if (!perAgentRunBudgetEnabled || agentId == null) {
            return globalCapUsd;
        }
        try {
            return RunBudgetCaps.effectiveCap(
                    globalCapUsd,
                    agentService.getRunBudgetUsd(agentId),
                    true,
                    maxPerAgentRunBudgetUsd);
        } catch (RuntimeException e) {
            log.debug("Could not read run cap for agent {}; using the platform cap", agentId, e);
            return globalCapUsd;
        }
    }

    /**
     * On-demand tool loading ({@code app.tools.search.*}, default OFF): once an agent resolves more
     * than {@code threshold} tools, only the always-on tools, {@code search_tools}, {@code
     * call_tool} and tools already searched for in this session are offered; the rest are reachable
     * through search. See {@link ToolSearchDeferral}.
     */
    @Bean
    ToolSearchDeferral toolSearchDeferral(
            @Value("${app.tools.search.enabled:false}") boolean enabled,
            @Value("${app.tools.search.threshold:25}") int threshold,
            @Value("${app.tools.search.always-on:" + ToolSearchDeferral.DEFAULT_ALWAYS_ON + "}")
                    String alwaysOn,
            @Value("${app.tools.search.max-carry-over:6}") int maxCarryOver,
            @Value("${app.tools.search.activation-store:auto}") String activationStore,
            @Value("${app.tools.search.activation-ttl:PT2H}") Duration activationTtl,
            ObjectProvider<StringRedisTemplate> redisProvider,
            GuardrailService guardrailService) {
        ToolSearchDeferral deferral = new ToolSearchDeferral();
        deferral.configure(enabled, threshold, alwaysOn, maxCarryOver);
        deferral.activations(activationRegistry(activationStore, activationTtl, redisProvider));
        deferral.withCatalogScreen(guardrailService);
        return deferral;
    }

    /**
     * {@code app.tools.search.activation-store=auto|memory|redis}: auto (default) uses Redis when a
     * {@link StringRedisTemplate} bean exists (activations survive restarts and follow a session
     * across replicas), else per-replica memory; memory never resolves the template; redis
     * requires it and degrades to memory with a warning, matching {@code app.mcp.registry-store}.
     */
    static ToolActivationRegistry activationRegistry(
            String store, Duration ttl, ObjectProvider<StringRedisTemplate> redisProvider) {
        String mode = store == null ? "" : store.trim();
        boolean wantsRedis = "redis".equalsIgnoreCase(mode) || "auto".equalsIgnoreCase(mode);
        if (!wantsRedis) {
            return new ToolActivationRegistry(
                    ToolActivationRegistry.DEFAULT_MAX_SESSIONS, ttl, Clock.systemUTC());
        }
        StringRedisTemplate redis = redisProvider == null ? null : redisProvider.getIfAvailable();
        if (redis != null) {
            log.info("Tool activation carry-over stored in Redis ({})", mode.toLowerCase());
            return new ToolActivationRegistry(new RedisToolActivationStore(redis), ttl);
        }
        if ("redis".equalsIgnoreCase(mode)) {
            log.warn(
                    "app.tools.search.activation-store=redis but no StringRedisTemplate is available - using in-memory activations");
        } else {
            log.info("Tool activation carry-over held in per-replica memory (no StringRedisTemplate)");
        }
        return new ToolActivationRegistry(
                ToolActivationRegistry.DEFAULT_MAX_SESSIONS, ttl, Clock.systemUTC());
    }

    /** Swarm goal verifier ({@code app.swarm.verifier.*}, default OFF). */
    @Bean
    com.agenticai.core.agents.collab.SwarmVerifierConfig swarmVerifierConfig(
            @Value("${app.swarm.verifier.enabled:false}") boolean enabled,
            @Value("${app.swarm.verifier.max-repair-rounds:1}") int maxRepairRounds,
            @Value("${app.swarm.verifier.result-chars:1500}") int resultChars,
            @Value("${app.swarm.verifier.brief-chars:1200}") int briefChars,
            @Value("${app.swarm.verifier.timeout-seconds:30}") int timeoutSeconds) {
        return new com.agenticai.core.agents.collab.SwarmVerifierConfig(
                enabled, maxRepairRounds, resultChars, briefChars, timeoutSeconds);
    }

    @Bean
    AgentMcpToolResolver agentMcpToolResolver(
            AgentToolGuard toolGuard,
            // MUST qualify: core/mcpserver's McpServerToolConfig adds a SECOND ToolCallbackProvider
            // bean (the INBOUND server exposing agents to external clients, NOT tools FOR agents),
            // so an unqualified lookup throws NoUniqueBeanDefinitionException and crashes ALL tool
            // resolution. We want ONLY the Spring AI MCP *client* bean (mcpToolCallbacks).
            @Qualifier("mcpToolCallbacks") ObjectProvider<ToolCallbackProvider> mcpToolCallbacks,
            McpRegistryService mcpRegistryService,
            ObjectProvider<DynamicMcpConnectionManager> dynamicMcpConnectionManager,
            ObjectProvider<List<McpSyncClient>> staticMcpSyncClients,
            ObjectProvider<PlatformIssueService> issueService,
            MeterRegistry meterRegistry,
            @Value("${app.tools.mcp-discovery-cache-ttl-seconds:300}")
                    long mcpDiscoveryCacheTtlSeconds) {
        return new AgentMcpToolResolver(
                toolGuard,
                mcpToolCallbacks,
                mcpRegistryService,
                dynamicMcpConnectionManager,
                staticMcpSyncClients,
                issueService,
                meterRegistry,
                Duration.ofSeconds(mcpDiscoveryCacheTtlSeconds));
    }

    @Bean
    AgentKnowledgeToolResolver agentKnowledgeToolResolver(
            AgentToolGuard toolGuard,
            ObjectProvider<KnowledgeSearchService> knowledgeSearchService,
            ObjectProvider<KnowledgeCollectionService> knowledgeCollectionService,
            ObjectProvider<KnowledgeIngestionService> knowledgeIngestionService,
            ObjectProvider<com.agenticai.core.knowledge.KnowledgeUrlIngester> knowledgeUrlIngester,
            ObjectProvider<AgentMemoryService> agentMemoryService,
            ObjectProvider<TenantMemoryService> tenantMemoryService,
            AgentService agentService,
            HumanApprovalGateService approvalGateService,
            ObjectProvider<GraphMemoryService> graphMemoryService,
            EntityExtractionService entityExtractionService,
            MemoryWriteArbitrationService memoryWriteArbitrationService,
            ObjectProvider<CodeIntelClient> codeIntelClient,
            ObjectProvider<com.agenticai.core.agents.consent.PurposeAccessPolicy> purposePolicy) {
        AgentKnowledgeToolResolver resolver =
                new AgentKnowledgeToolResolver(
                        toolGuard,
                        new KnowledgeServiceProviders(
                                knowledgeSearchService,
                                knowledgeCollectionService,
                                knowledgeIngestionService,
                                knowledgeUrlIngester),
                        agentMemoryService,
                        tenantMemoryService,
                        agentService,
                        approvalGateService,
                        graphMemoryService,
                        entityExtractionService,
                        memoryWriteArbitrationService,
                        codeIntelClient);
        // The optional PurposeAccessPolicy (implemented in core/app); absent = no purpose gating.
        resolver.setPurposePolicy(purposePolicy);
        return resolver;
    }

    @Bean
    AgentChannelToolResolver agentChannelToolResolver(
            AgentToolGuard toolGuard,
            ObjectProvider<ChannelMessageQueryPort> channelMessageQueryPort,
            GeneratedAttachmentResolver generatedAttachmentResolver,
            ObjectProvider<ChannelMediaSendPort> channelMediaSendPort,
            ObjectProvider<HumanHandoffPort> humanHandoffPort,
            ObjectProvider<ChannelSendPort> channelSendPort,
            ObjectProvider<ChannelControlPort> channelControlPort,
            ObjectProvider<DeviceDataIngestionService> deviceDataIngestionService) {
        return new AgentChannelToolResolver(
                toolGuard,
                channelMessageQueryPort,
                generatedAttachmentResolver,
                channelMediaSendPort,
                humanHandoffPort,
                channelSendPort,
                channelControlPort,
                deviceDataIngestionService);
    }

    @Bean
    AgentMediaToolResolver agentMediaToolResolver(
            AgentToolGuard toolGuard,
            AgentService agentService,
            ObjectProvider<MediaToolSource> mediaToolSources,
            ObjectProvider<DocumentGenerationService> documentGenerationService,
            com.agenticai.core.agents.tools.audio.SpeakBackendRegistry speakBackendRegistry) {
        return new AgentMediaToolResolver(
                toolGuard,
                agentService,
                mediaToolSources.orderedStream().toList(),
                documentGenerationService,
                speakBackendRegistry);
    }

    @Bean
    AgentIntegrationToolResolver agentIntegrationToolResolver(
            AgentToolGuard toolGuard,
            AgentService agentService,
            ObjectProvider<WorkspaceToolSource> workspaceToolSources,
            ObjectProvider<E2bCredentialService> e2bCredentialService,
            ObjectProvider<SdlcSandboxService> sdlcSandboxService,
            ObjectProvider<CheckoutSessionService> checkoutSessionService,
            // Implemented in core/agents-browser (BuiltInWebTools); absent = no web tools.
            ObjectProvider<WebToolPort> webTools,
            ObjectProvider<MandatePaymentToolSource> mandatePaymentToolSource,
            TurnRunBudgets turnRunBudgets,
            ToolWiringOptions options) {
        AgentIntegrationToolResolver resolver =
                new AgentIntegrationToolResolver(
                        toolGuard,
                        agentService,
                        workspaceToolSources.orderedStream().toList(),
                        e2bCredentialService,
                        sdlcSandboxService,
                        checkoutSessionService,
                        webTools,
                        mandatePaymentToolSource,
                        turnRunBudgets::forTurn);
        options.applyTo(resolver);
        return resolver;
    }

    @Bean
    AgentCustomToolResolver agentCustomToolResolver(
            AgentToolGuard toolGuard,
            AgentService agentService,
            ObjectProvider<AgentChatService> agentChatService,
            UserToolScriptService userToolScriptService,
            UserToolScriptEvaluator userToolScriptEvaluator,
            CustomCodeToolService customCodeToolService,
            OpenApiToolSourceService openApiToolSourceService,
            OpenApiToolExecutor openApiToolExecutor,
            SkillService skillService,
            ObjectProvider<ToolSandboxClient> skillSandboxClient,
            ObjectProvider<ToolSandboxProperties> skillSandboxProperties,
            ObjectProvider<com.agenticai.core.approval.HumanApprovalGateService>
                    approvalGateService,
            TurnRunBudgets turnRunBudgets,
            @Value("${app.research.deep.enabled:false}") boolean researchEnabled,
            @Value("${app.tools.run-subtasks.enabled:true}") boolean runSubtasksEnabled,
            ToolWiringOptions options,
            ObjectProvider<com.agenticai.core.agents.collab.CollaboratorToolFactory>
                    collaboratorToolFactory) {
        AgentCustomToolResolver resolver =
                new AgentCustomToolResolver(
                        toolGuard,
                        agentService,
                        agentChatService,
                        userToolScriptService,
                        userToolScriptEvaluator,
                        customCodeToolService,
                        openApiToolSourceService,
                        openApiToolExecutor,
                        skillService,
                        skillSandboxClient,
                        skillSandboxProperties,
                        turnRunBudgets::forTurn);
        resolver.setResearchEnabled(researchEnabled);
        resolver.setApprovalGateService(approvalGateService);
        resolver.setRunSubtasksEnabled(runSubtasksEnabled);
        resolver.setCollaboratorToolFactory(collaboratorToolFactory);
        options.applyTo(resolver);
        return resolver;
    }

    @Bean
    AgentCrewBrowserToolResolver agentCrewBrowserToolResolver(
            AgentToolGuard toolGuard,
            // Implemented in core/agents-browser (BrowserToolAdapter); absent = no browser tools.
            ObjectProvider<BrowserToolPort> browserTools,
            ObjectProvider<com.agenticai.core.agents.crew.SpecialistHandoffService>
                    specialistHandoffService,
            ObjectProvider<AgentChatService> agentChatService,
            @Value("${app.research.deep.enabled:false}") boolean researchEnabled) {
        AgentCrewBrowserToolResolver resolver =
                new AgentCrewBrowserToolResolver(
                        toolGuard, browserTools, specialistHandoffService, agentChatService);
        resolver.setResearchEnabled(researchEnabled);
        return resolver;
    }

    @Bean
    AgentDataTableToolResolver agentDataTableToolResolver(
            AgentToolGuard toolGuard,
            AgentService agentService,
            ObjectProvider<com.agenticai.core.tables.DataTableService> dataTables) {
        AgentDataTableToolResolver resolver =
                new AgentDataTableToolResolver(toolGuard, agentService);
        resolver.setDataTables(dataTables);
        return resolver;
    }

    @Bean
    AgentScheduledTaskToolResolver agentScheduledTaskToolResolver(
            AgentToolGuard toolGuard,
            AgentService agentService,
            ObjectProvider<com.agenticai.core.agents.tools.scheduling.ScheduleTaskToolFactory>
                    factory) {
        AgentScheduledTaskToolResolver resolver =
                new AgentScheduledTaskToolResolver(toolGuard, agentService);
        resolver.setFactory(factory);
        return resolver;
    }

    @Bean
    AgentBackgroundJobToolResolver agentBackgroundJobToolResolver(
            AgentToolGuard toolGuard,
            AgentService agentService,
            ObjectProvider<com.agenticai.core.agents.tools.scheduling.BackgroundJobToolFactory>
                    factory) {
        AgentBackgroundJobToolResolver resolver =
                new AgentBackgroundJobToolResolver(toolGuard, agentService);
        resolver.setFactory(factory);
        return resolver;
    }

    @Bean
    AgentWorkflowDraftToolResolver agentWorkflowDraftToolResolver(
            AgentToolGuard toolGuard,
            AgentService agentService,
            ObjectProvider<com.agenticai.core.agents.tools.workflowdraft.WorkflowDraftToolFactory>
                    factory) {
        AgentWorkflowDraftToolResolver resolver =
                new AgentWorkflowDraftToolResolver(toolGuard, agentService);
        resolver.setFactory(factory);
        return resolver;
    }

    @Bean
    AgentCodingTaskToolResolver agentCodingTaskToolResolver(
            AgentToolGuard toolGuard,
            AgentService agentService,
            ObjectProvider<com.agenticai.core.agents.tools.coding.CodingTaskToolFactory> factory) {
        AgentCodingTaskToolResolver resolver =
                new AgentCodingTaskToolResolver(toolGuard, agentService);
        resolver.setFactory(factory);
        return resolver;
    }
}
