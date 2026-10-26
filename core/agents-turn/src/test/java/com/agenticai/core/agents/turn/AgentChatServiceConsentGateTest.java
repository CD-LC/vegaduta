package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.consent.ConversationConsentGate;
import com.agenticai.core.agents.core.Agent;
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
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.guardrails.ContentGuardrailEngine;
import com.agenticai.core.guardrails.GuardrailViolationLogger;
import com.agenticai.core.guardrails.moderation.ModerationLayer;
import com.agenticai.core.guardrails.moderation.ModerationOutcome;
import com.agenticai.core.guardrails.moderation.ModerationPolicyPack;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * The DPDP-oriented consent gate in {@link AgentChatService#chat}: when the gate returns a reply
 * the whole turn is replaced (no moderation, model, tools or memory write); when it returns null,
 * or no gate bean exists, the turn is exactly what it was before; a failing gate fails the turn
 * instead of answering a contact whose consent state is unknown. Set-up mirrors {@link
 * AgentChatServiceModerationTest}.
 */
class AgentChatServiceConsentGateTest {

    private LlmGatewayService llmGatewayService;
    private SessionMemoryService sessionMemoryService;
    private ModerationService moderationService;
    private AgentService agentService;
    private AgentChatService service;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        ProviderCredentialResolver credentialResolver = mock(ProviderCredentialResolver.class);
        llmGatewayService = mock(LlmGatewayService.class);
        sessionMemoryService = mock(SessionMemoryService.class);
        ProviderErrorTranslator providerErrorTranslator = mock(ProviderErrorTranslator.class);
        AgentToolPort agentToolProvider = mock(AgentToolPort.class);
        AnswerConsistencyChecker answerConsistencyChecker = mock(AnswerConsistencyChecker.class);
        ReflexionService reflexionService = mock(ReflexionService.class);
        TaskPlanningService taskPlanningService = mock(TaskPlanningService.class);
        ToolCallLogService toolCallLogService = mock(ToolCallLogService.class);
        TenantLimits tenantLimits = mock(TenantLimits.class);
        FreeTierChatClientService freeTierChatClientService = mock(FreeTierChatClientService.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        TenantStatusGuard tenantStatusGuard = mock(TenantStatusGuard.class);
        LlmQualityJudgeService llmQualityJudgeService = mock(LlmQualityJudgeService.class);
        FeatureFlagService featureFlagService = mock(FeatureFlagService.class);
        LlmResponseCacheService responseCacheService = mock(LlmResponseCacheService.class);
        QueryComplexityClassifier queryComplexityClassifier = mock(QueryComplexityClassifier.class);
        moderationService = mock(ModerationService.class);

        // The one interaction we assert "was / was not called" against: a
        // deep-stub ChatClient so streamFast's fluent chain constructs without
        // NPEs (the ALLOW test verifies resolveClient was reached, and never
        // subscribes to the returned Flux).
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(llmGatewayService.resolveClient(any(), any(), any())).thenReturn(chatClient);
        when(credentialResolver.resolveActiveKey(any())).thenReturn("test-key");

        // Pre-turn reads: keep them cheap and non-null so the turn can proceed.
        when(sessionMemoryService.getHistory(any())).thenReturn(List.of());
        when(agentToolProvider.resolveToolsFor(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(featureFlagService.evaluateActiveFlags(any())).thenReturn(Map.of());

        Agent agent = mock(Agent.class);
        when(agent.getReasoningDepth()).thenReturn(ReasoningDepth.FAST);
        when(agent.getProviderName()).thenReturn(ProviderName.OPENAI);
        when(agentService.getEntity(any())).thenReturn(agent);
        when(agentService.resolveEffectiveModel(agent)).thenReturn("gpt-4o-mini");

        // The stream wraps its result in takeUntilOther(watchForCancellation): a real Flux runs the
        // operator's non-null check, so the watch must return a Flux, not the mock's default null.
        ChatTurnCancelService cancelService = mock(ChatTurnCancelService.class);
        when(cancelService.watchForCancellation(any(), any())).thenReturn(Flux.never());

        service =
                AgentChatServiceTestFactory.create(
                        agentService,
                        credentialResolver,
                        llmGatewayService,
                        sessionMemoryService,
                        providerErrorTranslator,
                        agentToolProvider,
                        providerOf(
                                mock(
                                        AgentMemoryService
                                                .class)), // present is fine; empty recall either
                        // way
                        providerOf((TenantMemoryService) null),
                        answerConsistencyChecker,
                        reflexionService,
                        taskPlanningService,
                        toolCallLogService,
                        tenantLimits,
                        freeTierChatClientService,
                        fileStorageService,
                        tenantStatusGuard,
                        llmQualityJudgeService,
                        providerOf((PlatformIssueService) null),
                        featureFlagService,
                        responseCacheService,
                        providerOf((SemanticResponseCacheService) null),
                        queryComplexityClassifier,
                        providerOf((RlPolicyPort) null),
                        providerOf((RlTurnRecorder) null),
                        providerOf(moderationService),
                        // Content-guardrail enforcement (write-only-config fix) - absent
                        // here so this class stays focused on the moderation gate alone;
                        // AgentChatServiceContentGuardrailTest covers the guardrail gate.
                        providerOf((ContentGuardrailEngine) null),
                        providerOf((GuardrailViolationLogger) null),
                        mock(AgentGuardrailService.class),
                        mock(InlineAttachmentProcessor.class),
                        // Cross-user resource sharing retrofit (Round 2) - only used by
                        // delegateCall (not exercised by this moderation-focused test
                        // class), so a bare mock is sufficient.
                        mock(ResourceAccessService.class),
                        // Live Vision - frame metering/session attribution,
                        // not exercised by this suite; bare mocks are sufficient.
                        mock(VisionUsageService.class),
                        mock(VisionSessionService.class),
                        // Hosted-pool invalid-model self-heal seam - absent bean = no self-healing.
                        providerOf(
                                (com.agenticai.core.providers.PlatformHostedLlmProviderService)
                                        null),
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

    @Test
    void consentGateReplyReplacesTheWholeTurn() {
        ConversationConsentGate gate = mock(ConversationConsentGate.class);
        UUID agentId = UUID.randomUUID();
        when(gate.intercept(agentId, "sess-1", "subject-1", "hello"))
                .thenReturn(Flux.just("Please read this notice and reply YES"));
        service.preflight.consentGate = gate;

        List<String> emitted = service.chat(agentId, "sess-1", "subject-1", "hello", null, null)
                .collectList()
                .block();

        assertEquals(List.of("Please read this notice and reply YES"), emitted);
        // Nothing downstream of the gate ran: no moderation, no model, no memory read or write,
        // no tools. (The tenant kill-switch check and the agent's configuration load come before
        // the gate by design; neither touches the contact's data.)
        verify(moderationService, never()).moderate(any(), any());
        verify(llmGatewayService, never()).resolveClient(any(), any(), any());
        verify(sessionMemoryService, never()).appendTurn(any(), any());
        verify(sessionMemoryService, never()).getHistory(any());
    }

    @Test
    void consentGateReturningNullLetsTheTurnReachTheLlm() {
        ConversationConsentGate gate = mock(ConversationConsentGate.class);
        when(gate.intercept(any(), any(), any(), any())).thenReturn(null);
        when(moderationService.moderate(any(), any())).thenReturn(ModerationOutcome.allow());
        service.preflight.consentGate = gate;

        service.chat(UUID.randomUUID(), "sess-1", "subject-1", "hello there", null, null);

        verify(gate).intercept(any(), any(), any(), any());
        verify(llmGatewayService).resolveClient(any(), any(), any());
    }

    @Test
    void withoutAConsentGateBeanTheTurnIsUnchanged() {
        when(moderationService.moderate(any(), any())).thenReturn(ModerationOutcome.allow());

        service.chat(UUID.randomUUID(), "sess-1", "subject-1", "hello there", null, null);

        verify(llmGatewayService).resolveClient(any(), any(), any());
    }

    @Test
    void aFailingGateFailsTheTurnInsteadOfAnsweringAnUnconsentedContact() {
        ConversationConsentGate gate = mock(ConversationConsentGate.class);
        when(gate.intercept(any(), any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        service.preflight.consentGate = gate;

        assertThrows(
                IllegalStateException.class,
                () -> service.chat(UUID.randomUUID(), "sess-1", "subject-1", "hi", null, null));
        verify(llmGatewayService, never()).resolveClient(any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
