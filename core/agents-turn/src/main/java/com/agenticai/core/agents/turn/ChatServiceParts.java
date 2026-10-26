package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.sharing.ResourceAccessService;

/**
 * Builds the plain (non-bean) collaborators {@link AgentChatService} delegates to, from the same
 * constructor arguments and in the same order its constructor used to build them inline
 * (extract-only split). The service copies each one into its own final field; the pipeline stages
 * that hold the service itself ({@link ChatTurnPreflight} and the others) are still built by the
 * service, last.
 *
 * <p>The directive gates and the memory step-event switch are read through suppliers on the
 * service, not snapshotted here: these objects are built before {@code @Value} field injection
 * runs, so a supplier defers the read to turn time (see {@link
 * ChatPromptDirectives.DirectiveGates}).
 */
final class ChatServiceParts {

    final AgentMessageEvents events;
    final ChatIssueRecorder issues;
    final ChatGuardrailGate guardrails;
    final ChatAttachments attachmentResolver;
    final ChatClientResolver clientResolver;
    final HostedChainFailover hostedFailover;
    final RlEpisodeRecorder rlEpisodes;
    final ChatMemoryRecall memoryRecall;
    final ChatTurnExecutor turns;
    final BufferedTurnRunner bufferedTurns;
    final ChatDelegateCall delegateCalls;
    final ChatSpecialistHandoff specialistHandoffs;
    final PromptDirectiveComposer directiveComposer;
    final ChatFailedReply failedReplies;
    final FollowUpTools followUp;

    ChatServiceParts(
            AgentChatService svc,
            AgentService agentService,
            SessionMemoryService sessionMemoryService,
            AgentToolPort agentToolProvider,
            ToolCallLogService toolCallLogService,
            ChatModelFacade model,
            ChatPolicyFacade policy,
            ChatObservabilityFacade observability,
            ChatQualityFacade quality,
            ChatContextFacade context,
            ChatTimeouts timeouts) {
        ProviderCredentialResolver credentialResolver = model.credentialResolver();
        LlmGatewayService llmGatewayService = model.llmGatewayService();
        ProviderErrorTranslator providerErrorTranslator = model.providerErrorTranslator();
        LlmQualityJudgeService llmQualityJudgeService = model.llmQualityJudgeService();
        FreeTierChatClientService freeTierChatClientService = model.freeTierChatClientService();
        TenantLimits tenantLimits = policy.tenantLimits();
        ResourceAccessService resourceAccessService = policy.resourceAccessService();
        this.events = new AgentMessageEvents();
        this.events.useLimits(timeouts.limits());
        this.issues = new ChatIssueRecorder(agentService, observability.platformIssueService());
        this.guardrails =
                new ChatGuardrailGate(
                        policy.moderationService(),
                        policy.contentGuardrailEngine(),
                        policy.guardrailViolationLogger(),
                        policy.agentGuardrailService());
        this.attachmentResolver =
                new ChatAttachments(
                        context.fileStorageService(),
                        context.inlineAttachmentProcessor(),
                        agentService);
        this.clientResolver =
                new ChatClientResolver(
                        agentService,
                        credentialResolver,
                        llmGatewayService,
                        freeTierChatClientService,
                        tenantLimits,
                        model.queryComplexityClassifier());
        this.hostedFailover =
                new HostedChainFailover(
                        model.platformHostedLlmProviderService(),
                        freeTierChatClientService,
                        svc.preTurnExecutor);
        this.hostedFailover.useSelfHealCooldown(timeouts.resilience().getHostedSelfHealCooldown());
        this.clientResolver.useLimits(timeouts.limits());
        this.rlEpisodes = new RlEpisodeRecorder(observability.rlTurnRecorder());
        this.memoryRecall =
                new ChatMemoryRecall(
                        context.agentMemoryService(),
                        context.tenantMemoryService(),
                        agentService,
                        () -> svc.memoryStepEventsEnabled);
        this.turns =
                new ChatTurnExecutor(
                        credentialResolver,
                        llmGatewayService,
                        providerErrorTranslator,
                        sessionMemoryService,
                        toolCallLogService,
                        llmQualityJudgeService,
                        guardrails,
                        issues,
                        events,
                        timeouts.streamIdleTimeout());
        this.turns.maxContinuations = timeouts.maxContinuations();
        this.turns.fastTurnTimeout = timeouts.fastTurnTimeout();
        this.turns.preTurnMaxWait = timeouts.preTurnMaxWait();
        this.turns.runtime = timeouts.runtime();
        this.turns.continuation = timeouts.continuation();
        this.followUp = new FollowUpTools(toolCallLogService, timeouts.followUp());
        this.turns.finish = timeouts.finish();
        this.turns.resilience = timeouts.resilience();
        this.turns.toolLedger = timeouts.toolLedger();
        this.turns.parity = timeouts.parity();
        this.turns.cachePolicy = new ChatCachePolicy(timeouts.cache(), timeouts.behaviour());
        this.turns.retryPolicy = timeouts.retryPolicy();
        this.turns.behaviour = timeouts.behaviour();
        this.turns.replayGate = timeouts.replayGate();
        this.bufferedTurns =
                new BufferedTurnRunner(
                        new BufferedTurnCalls(turns, providerErrorTranslator, toolCallLogService),
                        guardrails,
                        issues,
                        events,
                        providerErrorTranslator,
                        sessionMemoryService,
                        llmQualityJudgeService,
                        quality.answerConsistencyChecker(),
                        quality.reflexionService(),
                        quality.taskPlanningService(),
                        timeouts.bufferedTurnTimeout(),
                        timeouts.bufferedKeepAliveInterval());
        // The prompt registry's view for an agent, for every text the turn pipeline writes besides the
        // standing directives (refusals, nudges, framing lines). Read at call time: the settings bean
        // is injected after construction.
        java.util.function.Function<java.util.UUID, ResolvedDirectives> promptsForAgent =
                agentId ->
                        svc.promptDirectiveSettings.forTurn(
                                java.time.LocalDate.now(java.time.ZoneOffset.UTC), agentId);
        this.guardrails.prompts = promptsForAgent;
        this.issues.prompts = promptsForAgent;
        this.turns.prompts = promptsForAgent;
        this.memoryRecall.usePrompts(promptsForAgent);
        this.bufferedTurns.usePrompts(promptsForAgent);
        ChatPromptDirectives.DirectiveGates directiveGates =
                new ChatPromptDirectives.DirectiveGates(
                        () -> svc.toolTrustDirectiveEnabled,
                        () -> svc.mcpUntrustedDirectiveEnabled,
                        () -> svc.memoryUsageDirectiveEnabled,
                        () -> svc.toolSearchDirectiveEnabled,
                        () -> svc.browserV2DirectiveEnabled,
                        // Read at call time: the settings bean is injected after construction.
                        delegateAgentId ->
                                svc.promptDirectiveSettings.forTurn(
                                        java.time.LocalDate.now(java.time.ZoneOffset.UTC), delegateAgentId));
        // The turn a delegated or specialist agent runs for another agent's tool call: screening, hooks, journal,
        // guardrails and the continuation policy, like a buffered turn. Hooks and the tenant plan are read at turn
        // time (the hook runtime and the hot path are set after construction).
        SubAgentTurn subTurn =
                new SubAgentTurn(
                        new BufferedTurnCalls(turns, providerErrorTranslator, toolCallLogService),
                        guardrails,
                        () -> svc.agentHooks,
                        tenantId -> svc.hotpath.plan(svc.tenantLimits, tenantId),
                        llmQualityJudgeService,
                        providerErrorTranslator);
        this.delegateCalls =
                new ChatDelegateCall(
                        agentService,
                        resourceAccessService,
                        credentialResolver,
                        llmGatewayService,
                        agentToolProvider,
                        llmQualityJudgeService,
                        providerErrorTranslator,
                        turns,
                        svc.agentRouting::resolveExperimentVariants,
                        directiveGates,
                        subTurn);
        this.specialistHandoffs =
                new ChatSpecialistHandoff(
                        agentService,
                        resourceAccessService,
                        credentialResolver,
                        llmGatewayService,
                        agentToolProvider,
                        llmQualityJudgeService,
                        providerErrorTranslator,
                        turns,
                        svc.agentRouting::resolveExperimentVariants,
                        directiveGates,
                        subTurn);
        this.directiveComposer =
                PromptDirectiveComposer.standard(
                        () -> svc.toolTrustDirectiveEnabled, () -> svc.mcpUntrustedDirectiveEnabled);
        this.failedReplies = new ChatFailedReply(sessionMemoryService);
        this.failedReplies.prompts = promptsForAgent;
    }
}
