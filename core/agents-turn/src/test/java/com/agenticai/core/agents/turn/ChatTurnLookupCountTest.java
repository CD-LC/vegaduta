package com.agenticai.core.agents.turn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.loadtest.LoadTestTenantAllowlist;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.tools.ResolvedToolList;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.cache.SemanticResponseCacheService;
import com.agenticai.core.agents.turn.quality.AnswerConsistencyChecker;
import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.agents.turn.quality.ReflexionService;
import com.agenticai.core.agents.turn.quality.TaskPlanningService;
import com.agenticai.core.agents.version.AgentVersionResolutionService;
import com.agenticai.core.agents.vision.VisionSessionService;
import com.agenticai.core.agents.vision.VisionUsageService;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.guardrails.ContentGuardrailEngine;
import com.agenticai.core.guardrails.GuardrailViolationLogger;
import com.agenticai.core.guardrails.moderation.ModerationService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.rl.api.RlPolicyPort;
import com.agenticai.core.rl.api.RlTurnRecorder;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.TenantStatusGuard;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.FeatureFlagService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Counts, for ONE real {@code chat()} turn with mocked collaborators, how many times each per-turn
 * fact is looked up: the tenant's plan, the tenant's feature flags and whether MCP tools are
 * offered. Docs: docs/architecture/HOT-PATH-PERFORMANCE.md. The numbers asserted here are the
 * budget; raise one only with a reason.
 *
 * <p>Before the per-turn plan and flags, this same turn read the plan once in preflight, again in
 * the inbound guardrail check and twice more in the gateway (budget and rate limit), evaluated the
 * flags three times (version pin, effective-agent overlay, experiment variants) and resolved the
 * MCP tools a second time to learn whether any are offered.
 */
class ChatTurnLookupCountTest {

    private static final String TENANT = "acme";

    private LlmGatewayService gateway;
    private TenantLimits tenantLimits;
    private FeatureFlagService flags;
    private AgentToolPort toolProvider;
    private AgentVersionResolutionService versions;
    private Agent agent;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of(TENANT, "u1", Set.of()));
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        gateway = mock(LlmGatewayService.class);
        when(gateway.resolveClient(any(), any(), any())).thenReturn(chatClient);
        tenantLimits = mock(TenantLimits.class);
        when(tenantLimits.plan(TENANT)).thenReturn(TenantPlan.PREMIUM);
        flags = mock(FeatureFlagService.class);
        when(flags.evaluateActiveFlags(TENANT)).thenReturn(Map.of());
        toolProvider = mock(AgentToolPort.class);
        versions = mock(AgentVersionResolutionService.class);
        when(versions.pinAndApply(any(), any(), any())).thenAnswer(i -> i.getArgument(1));

        agent = mock(Agent.class);
        when(agent.getReasoningDepth()).thenReturn(ReasoningDepth.FAST);
        when(agent.getProviderName()).thenReturn(ProviderName.OPENAI);
        when(agent.getEnabledGuardrails()).thenReturn(Set.of("profanity", "jailbreak"));
        when(agent.getSystemPrompt()).thenReturn("You are a helpful assistant.");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void oneTurnReadsThePlanOnceAndHandsItToTheGateway() {
        when(toolProvider.resolveToolsFor(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ResolvedToolList.of(List.of(), false));

        build().chat(UUID.randomUUID(), "s1", "subject", "hello", null, null);

        // Preflight reads it; the inbound guardrail check (two guardrails configured) and the
        // gateway's budget and rate-limit checks reuse it.
        verify(tenantLimits, times(1)).plan(TENANT);
        verify(gateway)
                .resolveClient(eq(TENANT), any(GatewayCallSpec.class), eq(TenantPlan.PREMIUM));
    }

    @Test
    void oneTurnEvaluatesTheTenantsFlagsOnce() {
        when(toolProvider.resolveToolsFor(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ResolvedToolList.of(List.of(), false));

        AgentChatService service = build();
        service.versionResolver = providerOf(versions);
        service.chat(UUID.randomUUID(), "s1", "subject", "hello", null, null);

        verify(flags, times(1)).evaluateActiveFlags(TENANT);
        // The pin and the overlay answer from that one evaluation, never from a fresh one.
        verify(versions).pinAndApply(any(), any(), eq(Map.of()));
        verify(versions, never()).explicitPublishEnabled();
    }

    @Test
    void withoutVersioningTheFlagsAreStillEvaluatedOnce() {
        when(toolProvider.resolveToolsFor(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ResolvedToolList.of(List.of(), false));

        build().chat(UUID.randomUUID(), "s1", "subject", "hello", null, null);

        verify(flags, times(1)).evaluateActiveFlags(TENANT);
    }

    @Test
    void theMcpAnswerComesFromTheResolutionThatAlreadyRan() {
        when(toolProvider.resolveToolsFor(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ResolvedToolList.of(List.of(), true));

        build().chat(UUID.randomUUID(), "s1", "subject", "hello", null, null);

        verify(toolProvider, never()).anyMcpToolsOfferedFor(any(), any(), any(), any());
    }

    @Test
    void aToolListThatDoesNotCarryTheAnswerStillGetsAskedTheOldWay() {
        when(toolProvider.resolveToolsFor(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(toolProvider.anyMcpToolsOfferedFor(any(), any(), any(), any())).thenReturn(true);

        build().chat(UUID.randomUUID(), "s1", "subject", "hello", null, null);

        verify(toolProvider, times(1)).anyMcpToolsOfferedFor(any(), any(), any(), any());
    }

    private AgentChatService build() {
        ProviderCredentialResolver credentialResolver = mock(ProviderCredentialResolver.class);
        when(credentialResolver.resolveActiveKey(any())).thenReturn("test-key");
        AgentService agentService = mock(AgentService.class);
        when(agentService.getEntity(any())).thenReturn(agent);
        when(agentService.resolveEffectiveModel(agent)).thenReturn("gpt-4o-mini");
        SessionMemoryService sessionMemoryService = mock(SessionMemoryService.class);
        when(sessionMemoryService.getHistory(any())).thenReturn(List.of());
        TenantStatusGuard usableTenants = mock(TenantStatusGuard.class);
        when(usableTenants.isUsable(any())).thenReturn(true);
        AgentGuardrailService guardrailService =
                new AgentGuardrailService(
                        tenantLimits,
                        mock(AgentRepository.class),
                        mock(ResourceAccessService.class));
        return AgentChatServiceTestFactory.create(
                agentService,
                credentialResolver,
                gateway,
                sessionMemoryService,
                mock(ProviderErrorTranslator.class),
                toolProvider,
                providerOf(mock(AgentMemoryService.class)),
                providerOf((TenantMemoryService) null),
                mock(AnswerConsistencyChecker.class),
                mock(ReflexionService.class),
                mock(TaskPlanningService.class),
                mock(ToolCallLogService.class),
                tenantLimits,
                mock(FreeTierChatClientService.class),
                mock(FileStorageService.class),
                usableTenants,
                mock(LlmQualityJudgeService.class),
                providerOf((PlatformIssueService) null),
                flags,
                mock(LlmResponseCacheService.class),
                providerOf((SemanticResponseCacheService) null),
                mock(QueryComplexityClassifier.class),
                providerOf((RlPolicyPort) null),
                providerOf((RlTurnRecorder) null),
                providerOf((ModerationService) null),
                providerOf(passingEngine()),
                providerOf(mock(GuardrailViolationLogger.class)),
                guardrailService,
                mock(InlineAttachmentProcessor.class),
                mock(ResourceAccessService.class),
                mock(VisionUsageService.class),
                mock(VisionSessionService.class),
                providerOf((com.agenticai.core.providers.PlatformHostedLlmProviderService) null),
                mock(LoadTestTenantAllowlist.class),
                NeverCancelledTurns.service(),
                java.time.Duration.ofSeconds(90),
                java.time.Duration.ofSeconds(240),
                java.time.Duration.ofSeconds(20));
    }

    private static ContentGuardrailEngine passingEngine() {
        ContentGuardrailEngine engine = mock(ContentGuardrailEngine.class);
        when(engine.evaluate(any(), any(), any(), any()))
                .thenReturn(com.agenticai.core.guardrails.ContentGuardrailCheckResult.pass());
        return engine;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
