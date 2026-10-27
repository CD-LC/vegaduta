package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.cache.SemanticResponseCacheService;
import com.agenticai.core.agents.turn.quality.AnswerConsistencyChecker;
import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.agents.turn.quality.ReflexionService;
import com.agenticai.core.agents.turn.quality.TaskPlanningService;
import com.agenticai.core.agents.vision.VisionSessionService;
import com.agenticai.core.agents.vision.VisionUsageService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.guardrails.ContentGuardrail;
import com.agenticai.core.guardrails.ContentGuardrailEngine;
import com.agenticai.core.guardrails.GuardrailFailMode;
import com.agenticai.core.guardrails.GuardrailViolationLogger;
import com.agenticai.core.guardrails.impl.JailbreakDetectorGuardrail;
import com.agenticai.core.guardrails.impl.ProfanityGuardrail;
import com.agenticai.core.guardrails.moderation.ModerationService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.rl.api.RlPolicyPort;
import com.agenticai.core.rl.api.RlTurnRecorder;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantStatusGuard;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.FeatureFlagService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * Wiring test for per-agent content-guardrail enforcement in {@link AgentChatService#chat}
 * (write-only-config fix, 2026-07-29). Before that fix, {@link ContentGuardrailEngine} and its
 * guardrail implementations were referenced ONLY by their own unit tests - a tenant enabling
 * guardrails on an agent got silent non-enforcement. This class is the regression tripwire: every
 * test here uses the REAL engine with REAL guardrail implementations (not an engine mock), so if
 * anyone unwires the engine from the chat path again, {@link
 * #enabledGuardrailBlocksViolatingMessageBeforeTheLlm} fails.
 *
 * <p>Same harness shape as {@link AgentChatServiceModerationTest}: BYOK path, no request
 * TenantContext (so plan gating takes the free-tier-floor branch of {@link
 * AgentGuardrailService#resolveEnforceableGuardrails}), FAST depth, caching off, {@code
 * LlmGatewayService#resolveClient} as the observable "the LLM was reached" signal.
 */
class AgentChatServiceContentGuardrailTest {

    private LlmGatewayService llmGatewayService;
    private SessionMemoryService sessionMemoryService;
    private AuditLogService auditLogService;
    private Agent agent;

    @BeforeEach
    void setUp() {
        llmGatewayService = mock(LlmGatewayService.class);
        sessionMemoryService = mock(SessionMemoryService.class);
        auditLogService = mock(AuditLogService.class);

        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(llmGatewayService.resolveClient(any(), any(), any())).thenReturn(chatClient);
        when(sessionMemoryService.getHistory(any())).thenReturn(List.of());

        agent = mock(Agent.class);
        when(agent.getReasoningDepth()).thenReturn(ReasoningDepth.FAST);
        when(agent.getProviderName()).thenReturn(ProviderName.OPENAI);
    }

    /**
     * Builds the service under test around the given engine provider. The violation logger is the
     * REAL {@link GuardrailViolationLogger} over a mocked {@link AuditLogService}, so the tests
     * also prove blocks land on the pre-existing {@code guardrail.input.blocked} audit path; {@link
     * AgentGuardrailService} is likewise real (its collaborators are only touched on config writes
     * / plan lookups for a non-null tenant, neither of which these tests exercise).
     */
    private AgentChatService buildService(ObjectProvider<ContentGuardrailEngine> engineProvider) {
        // The stream wraps its result in takeUntilOther(watchForCancellation): a real Flux runs the
        // operator's non-null check, so the watch must return a Flux, not the mock's default null.
        ChatTurnCancelService cancelService = mock(ChatTurnCancelService.class);
        when(cancelService.watchForCancellation(any(), any())).thenReturn(Flux.never());
        ProviderCredentialResolver credentialResolver = mock(ProviderCredentialResolver.class);
        when(credentialResolver.resolveActiveKey(any())).thenReturn("test-key");

        AgentService agentService = mock(AgentService.class);
        when(agentService.getEntity(any())).thenReturn(agent);
        when(agentService.resolveEffectiveModel(agent)).thenReturn("gpt-4o-mini");

        AgentToolPort agentToolProvider = mock(AgentToolPort.class);
        when(agentToolProvider.resolveToolsFor(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        FeatureFlagService featureFlagService = mock(FeatureFlagService.class);
        when(featureFlagService.evaluateActiveFlags(any())).thenReturn(Map.of());

        AgentGuardrailService agentGuardrailService =
                new AgentGuardrailService(
                        mock(TenantLimits.class),
                        mock(AgentRepository.class),
                        mock(ResourceAccessService.class));

        return AgentChatServiceTestFactory.create(
                agentService,
                credentialResolver,
                llmGatewayService,
                sessionMemoryService,
                mock(ProviderErrorTranslator.class),
                agentToolProvider,
                providerOf(mock(AgentMemoryService.class)),
                providerOf((TenantMemoryService) null),
                mock(AnswerConsistencyChecker.class),
                mock(ReflexionService.class),
                mock(TaskPlanningService.class),
                mock(ToolCallLogService.class),
                mock(TenantLimits.class),
                mock(FreeTierChatClientService.class),
                mock(FileStorageService.class),
                mock(TenantStatusGuard.class),
                mock(LlmQualityJudgeService.class),
                providerOf((PlatformIssueService) null),
                featureFlagService,
                mock(LlmResponseCacheService.class),
                providerOf((SemanticResponseCacheService) null),
                mock(QueryComplexityClassifier.class),
                providerOf((RlPolicyPort) null),
                providerOf((RlTurnRecorder) null),
                providerOf((ModerationService) null),
                engineProvider,
                providerOf(new GuardrailViolationLogger(auditLogService)),
                agentGuardrailService,
                mock(InlineAttachmentProcessor.class),
                mock(ResourceAccessService.class),
                // Live Vision - frame metering/session attribution,
                // not exercised by this suite; bare mocks are sufficient.
                mock(VisionUsageService.class),
                mock(VisionSessionService.class),
                // Hosted-pool invalid-model self-heal seam - absent bean = no self-healing.
                providerOf((com.agenticai.core.providers.PlatformHostedLlmProviderService) null),
                // Load-test seam - unstubbed mock is false for every tenantId,
                // matching this suite's existing (non-load-test) assumptions.
                mock(LoadTestTenantAllowlist.class),
                cancelService,
                java.time.Duration.ofSeconds(90),
                // Buffered-depth total-turn ceiling and SSE keepalive interval
                // (2026-09-17). Neither fires in this suite - every turn here resolves
                // in milliseconds - so both are just non-null values the constructor needs.
                java.time.Duration.ofSeconds(240),
                java.time.Duration.ofSeconds(20));
    }

    /**
     * The real engine over the real pattern-based guardrail implementations. The provider is a
     * plain anonymous {@link ObjectProvider} (not a Mockito stub of {@code
     * getIfAvailable(ArrayList::new)}) because a method-ref argument never equals-matches across
     * call sites - see the same helper in {@code ContentGuardrailEngineTest}.
     */
    private static ContentGuardrailEngine realEngine() {
        List<ContentGuardrail> guardrails =
                List.of(new ProfanityGuardrail(), new JailbreakDetectorGuardrail());
        return new ContentGuardrailEngine(
                new ObjectProvider<>() {
                    @Override
                    public List<ContentGuardrail> getIfAvailable() {
                        return new ArrayList<>(guardrails);
                    }
                });
    }

    @Test
    void enabledGuardrailBlocksViolatingMessageBeforeTheLlm() {
        when(agent.getEnabledGuardrails()).thenReturn(Set.of("profanity"));
        AgentChatService service = buildService(providerOf(realEngine()));

        // Same trigger phrase ContentGuardrailEngineTest already relies on.
        Flux<String> result =
                service.chat(
                        UUID.randomUUID(),
                        "sess-1",
                        "subject-1",
                        "This is damn frustrating!",
                        null,
                        null);

        List<String> emitted = result.collectList().block();
        assertNotNull(emitted);
        assertFalse(emitted.isEmpty(), "expected a refusal message to be emitted");
        assertFalse(
                emitted.get(0) == null || emitted.get(0).isBlank(),
                "expected a non-empty refusal message");

        // The LLM was never resolved/called, and nothing (not even the blocked
        // user message) was written to session memory - identical short-circuit
        // contract to the moderation gate directly above this one.
        verify(llmGatewayService, never()).resolveClient(any(), any(), any());
        verify(sessionMemoryService, never()).appendTurn(any(), any());
        // The block was audited through the pre-existing violation path.
        verify(auditLogService)
                .record(
                        any(),
                        eq("guardrail.input.blocked"),
                        eq("guardrail_violation"),
                        eq("sess-1"),
                        eq(AuditOutcome.REJECTED),
                        any());
    }

    @Test
    void cleanMessagePassesEnabledGuardrailsAndReachesTheLlm() {
        when(agent.getEnabledGuardrails()).thenReturn(Set.of("profanity", "jailbreak"));
        AgentChatService service = buildService(providerOf(realEngine()));

        service.chat(
                UUID.randomUUID(),
                "sess-1",
                "subject-1",
                "Please summarise my open tickets.",
                null,
                null);

        verify(llmGatewayService).resolveClient(any(), any(), any());
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void agentWithNoGuardrailsConfiguredIsUntouched() {
        when(agent.getEnabledGuardrails()).thenReturn(Set.of());
        AgentChatService service = buildService(providerOf(realEngine()));

        service.chat(
                UUID.randomUUID(), "sess-1", "subject-1", "This is damn frustrating!", null, null);

        // No guardrails enabled on the agent -> even a would-violate message
        // proceeds; enforcement is strictly per-agent opt-in config.
        verify(llmGatewayService).resolveClient(any(), any(), any());
    }

    @Test
    void engineFailureFailsClosedByDefaultAndTheTurnNeverReachesTheLlm() {
        when(agent.getEnabledGuardrails()).thenReturn(Set.of("profanity"));
        ContentGuardrailEngine throwingEngine = mock(ContentGuardrailEngine.class);
        when(throwingEngine.evaluate(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("guardrail infrastructure down"));
        AgentChatService service = buildService(providerOf(throwingEngine));

        service.chat(UUID.randomUUID(), "sess-1", "subject-1", "hello there", null, null);

        verify(llmGatewayService, never()).resolveClient(any(), any(), any());
    }

    @Test
    void engineFailureFailsOpenWhenConfiguredOpenAndTheTurnProceeds() {
        GuardrailFailMode.set(GuardrailFailMode.OPEN);
        try {
            when(agent.getEnabledGuardrails()).thenReturn(Set.of("profanity"));
            ContentGuardrailEngine throwingEngine = mock(ContentGuardrailEngine.class);
            when(throwingEngine.evaluate(any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("guardrail infrastructure down"));
            AgentChatService service = buildService(providerOf(throwingEngine));

            service.chat(UUID.randomUUID(), "sess-1", "subject-1", "hello there", null, null);

            verify(llmGatewayService).resolveClient(any(), any(), any());
        } finally {
            GuardrailFailMode.set(GuardrailFailMode.CLOSED);
        }
    }

    @Test
    void absentEngineBeanIsAGenuineNoOp() {
        when(agent.getEnabledGuardrails()).thenReturn(Set.of("profanity"));
        AgentChatService service = buildService(providerOf((ContentGuardrailEngine) null));

        service.chat(
                UUID.randomUUID(), "sess-1", "subject-1", "This is damn frustrating!", null, null);

        verify(llmGatewayService).resolveClient(any(), any(), any());
    }

    @Test
    void resolveEnforceableGuardrailsDropsAboveFloorTypesWithoutTenant() {
        // Direct check of the plan-gating helper's conservative null-tenant
        // branch: only free-tier-available types survive.
        AgentGuardrailService svc =
                new AgentGuardrailService(
                        mock(TenantLimits.class),
                        mock(AgentRepository.class),
                        mock(ResourceAccessService.class));

        var enforceable =
                svc.resolveEnforceableGuardrails(
                        Set.of("profanity", "jailbreak", "pii", "toxicity"), null);

        assertTrue(
                enforceable.contains(com.agenticai.core.guardrails.ContentGuardrailType.PROFANITY));
        assertTrue(
                enforceable.contains(com.agenticai.core.guardrails.ContentGuardrailType.JAILBREAK));
        assertFalse(enforceable.contains(com.agenticai.core.guardrails.ContentGuardrailType.PII));
        assertFalse(
                enforceable.contains(com.agenticai.core.guardrails.ContentGuardrailType.TOXICITY));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
