package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
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
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.guardrails.ContentGuardrailEngine;
import com.agenticai.core.guardrails.GuardrailViolationException;
import com.agenticai.core.guardrails.GuardrailViolationLogger;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

/**
 * BYOK fallback GATING (2026-08-05) - {@link AgentChatService#callWithProviderFallback} used to
 * retry the agent's configured fallback provider on ANY runtime failure, including content-policy
 * refusals and over-length prompts that every provider rejects identically - replaying the user's
 * input against a second provider for no possible benefit. It is now gated on the exact same {@link
 * HostedFailoverPolicy#isRetryable} decision the hosted pool applies (with raw, untranslated
 * exceptions classified through a REAL {@link ProviderErrorTranslator} first), so:
 *
 * <ul>
 *   <li>input-shaped failures (content policy / context length / 413 / 422) fail fast with their
 *       own actionable message - no fallback attempt;
 *   <li>provider-shaped failures - crucially the invalid-model 400/404 - still fall back exactly as
 *       before;
 *   <li>raw non-Upstream exceptions (connection resets etc., which translate to the generic 502)
 *       keep their pre-gate fallback behavior;
 *   <li>the pre-existing {@code ToolStepLimitExceededException}/{@code GuardrailViolationException}
 *       exclusions are untouched.
 * </ul>
 *
 * <p>Same construction harness as {@link AgentChatServiceContentGuardrailTest}; {@code
 * callWithProviderFallback} is package-private purely as this suite's seam. The streaming FAST
 * path's fallback branch applies the identical {@code HostedFailoverPolicy.isRetryable} gate, whose
 * classification table {@link HostedFailoverPolicyTest} already pins.
 */
class AgentChatServiceByokFallbackGatingTest {

    private static final String TENANT = "tenant-1";

    private AgentChatService service;
    private Agent agent;
    private ProviderCredentialResolver credentialResolver;
    private LlmGatewayService llmGatewayService;
    private ChatClient primaryClient;

    @BeforeEach
    void setUp() {
        credentialResolver = mock(ProviderCredentialResolver.class);
        when(credentialResolver.resolveActiveKey(any())).thenReturn("test-key");
        llmGatewayService = mock(LlmGatewayService.class);
        when(llmGatewayService.resolveClient(any(), any(), any()))
                .thenReturn(mock(ChatClient.class));
        primaryClient = mock(ChatClient.class);

        agent = mock(Agent.class);
        when(agent.getId()).thenReturn(UUID.randomUUID());
        when(agent.getProviderName()).thenReturn(ProviderName.OPENAI);
        when(agent.getFallbackProviderName()).thenReturn(ProviderName.OPENROUTER);
        when(agent.getFallbackModel()).thenReturn("fallback-model");

        service = buildService();
    }

    private AgentChatService buildService() {
        return AgentChatServiceTestFactory.create(
                mock(AgentService.class),
                credentialResolver,
                llmGatewayService,
                mock(
                        SessionMemoryService
                                .class), // REAL translator (no explainer/catalog): the gate's
                // classification
                // of raw exceptions is part of what this suite pins.
                new ProviderErrorTranslator(com.agenticai.core.json.JsonMappers.plain(), null),
                mock(AgentToolPort.class),
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
                mock(FeatureFlagService.class),
                mock(LlmResponseCacheService.class),
                providerOf((SemanticResponseCacheService) null),
                mock(QueryComplexityClassifier.class),
                providerOf((RlPolicyPort) null),
                providerOf((RlTurnRecorder) null),
                providerOf((ModerationService) null),
                providerOf((ContentGuardrailEngine) null),
                providerOf((GuardrailViolationLogger) null),
                mock(AgentGuardrailService.class),
                mock(InlineAttachmentProcessor.class),
                mock(ResourceAccessService.class),
                mock(VisionUsageService.class),
                mock(VisionSessionService.class),
                providerOf(
                        (com.agenticai.core.providers.PlatformHostedLlmProviderService)
                                null), // Load-test seam - unstubbed mock is false for every
                // tenantId,
                // matching this suite's existing (non-load-test) assumptions.
                mock(LoadTestTenantAllowlist.class),
                mock(ChatTurnCancelService.class),
                java.time.Duration.ofSeconds(
                        90), // Buffered-depth total-turn ceiling and SSE keepalive interval
                // (2026-09-17). Neither fires in this suite - every turn here resolves
                // in milliseconds - so both are just non-null values the constructor needs.
                java.time.Duration.ofSeconds(240),
                java.time.Duration.ofSeconds(20));
    }

    /**
     * A promptExecutor that throws {@code primaryError} on the first (primary) call and answers on
     * the second (fallback).
     */
    private static Function<ChatClient, String> failsPrimaryThenAnswers(
            RuntimeException primaryError, AtomicInteger attempts) {
        return client -> {
            if (attempts.incrementAndGet() == 1) {
                throw primaryError;
            }
            return "rescued-by-fallback";
        };
    }

    @Test
    void contentPolicyRefusalNeverTriggersFallback() {
        UpstreamProviderException refusal =
                new UpstreamProviderException(
                        400,
                        "The LLM provider rejected the request (HTTP 400):"
                                + " content_policy_violation",
                        "The AI provider declined to respond because the request tripped its"
                                + " content policy. Try rephrasing it.");
        AtomicInteger attempts = new AtomicInteger();

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                service.callWithProviderFallback(
                                        TENANT,
                                        agent,
                                        primaryClient,
                                        failsPrimaryThenAnswers(refusal, attempts)));

        assertSame(
                refusal,
                thrown,
                "the already-classified refusal (with its actionable message) must surface as-is");
        assertEquals(
                1,
                attempts.get(),
                "the fallback provider must never be asked to re-refuse the same input");
        verify(credentialResolver, never()).resolveActiveKey(any());
        verify(llmGatewayService, never()).resolveClient(any(), any(), any());
    }

    @Test
    void contextLengthFailureNeverTriggersFallback() {
        UpstreamProviderException tooLong =
                new UpstreamProviderException(
                        400,
                        "The LLM provider rejected the request (HTTP 400): This model's maximum"
                                + " context length is 8192 tokens",
                        "That request is too long for the selected model.");
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(
                RuntimeException.class,
                () ->
                        service.callWithProviderFallback(
                                TENANT,
                                agent,
                                primaryClient,
                                failsPrimaryThenAnswers(tooLong, attempts)));

        assertEquals(1, attempts.get());
        verify(llmGatewayService, never()).resolveClient(any(), any(), any());
    }

    @Test
    void invalidModel400StillTriggersFallback() {
        // The exact case the gate must NOT over-block: a dead/retired model id
        // is a property of THIS provider's row - the fallback provider (its own
        // model) genuinely can rescue the turn.
        UpstreamProviderException invalidModel =
                new UpstreamProviderException(
                        400,
                        "The LLM provider rejected the request (HTTP 400): The model"
                                + " `gpt-nonexistent` does not exist",
                        "The selected AI model isn't available from this provider anymore.");
        AtomicInteger attempts = new AtomicInteger();

        String answer =
                service.callWithProviderFallback(
                        TENANT,
                        agent,
                        primaryClient,
                        failsPrimaryThenAnswers(invalidModel, attempts));

        assertEquals("rescued-by-fallback", answer);
        assertEquals(2, attempts.get());
        verify(llmGatewayService).resolveClient(any(), any());
    }

    @Test
    void rawUntranslatedContentPolicyErrorIsClassifiedBeforeTheFallbackDecision() {
        // The non-streaming paths hand this method RAW exceptions (translation
        // normally happens in the caller's onErrorMap, downstream of here) - the
        // gate must classify first, not just check instanceof. Spring AI's
        // RestClient error shape: message is literally "<status> - <body>".
        RuntimeException raw =
                new RuntimeException(
                        "400 - {\"error\":{\"message\":\"content_policy_violation: your input was"
                                + " rejected\"}}");
        AtomicInteger attempts = new AtomicInteger();

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                service.callWithProviderFallback(
                                        TENANT,
                                        agent,
                                        primaryClient,
                                        failsPrimaryThenAnswers(raw, attempts)));

        assertEquals(1, attempts.get());
        verify(llmGatewayService, never()).resolveClient(any(), any(), any());
        // What surfaces is the CLASSIFIED exception - real status, consumer message intact.
        UpstreamProviderException classified = (UpstreamProviderException) thrown;
        assertEquals(400, classified.getUpstreamStatus());
        assertTrue(classified.getConsumerMessage().contains("content policy"));
    }

    @Test
    void genericConnectionErrorKeepsItsPreGateFallbackBehavior() {
        // Unstructured raw failures translate to the generic 502, which is
        // retryable - the gate must not regress the pre-existing recovery.
        RuntimeException raw = new RuntimeException("connection reset by peer");
        AtomicInteger attempts = new AtomicInteger();

        String answer =
                service.callWithProviderFallback(
                        TENANT, agent, primaryClient, failsPrimaryThenAnswers(raw, attempts));

        assertEquals("rescued-by-fallback", answer);
        assertEquals(2, attempts.get());
    }

    @Test
    void preExistingNonProviderExclusionsAreUntouched() {
        GuardrailViolationException guardrail =
                new GuardrailViolationException("some_tool", "blocked");
        AtomicInteger guardrailAttempts = new AtomicInteger();
        assertSame(
                guardrail,
                assertThrows(
                        GuardrailViolationException.class,
                        () ->
                                service.callWithProviderFallback(
                                        TENANT,
                                        agent,
                                        primaryClient,
                                        failsPrimaryThenAnswers(guardrail, guardrailAttempts))));
        assertEquals(1, guardrailAttempts.get());

        ToolStepLimitExceededException stepLimit =
                new ToolStepLimitExceededException(UUID.randomUUID(), "sess-1", 25);
        AtomicInteger stepLimitAttempts = new AtomicInteger();
        assertSame(
                stepLimit,
                assertThrows(
                        ToolStepLimitExceededException.class,
                        () ->
                                service.callWithProviderFallback(
                                        TENANT,
                                        agent,
                                        primaryClient,
                                        failsPrimaryThenAnswers(stepLimit, stepLimitAttempts))));
        assertEquals(1, stepLimitAttempts.get());
    }

    @Test
    void noConfiguredFallbackStillRethrowsImmediately() {
        when(agent.getFallbackProviderName()).thenReturn(null);
        UpstreamProviderException retryable =
                new UpstreamProviderException(
                        429, "HTTP 429: rate_limit_exceeded", "The AI provider is busy.");
        AtomicInteger attempts = new AtomicInteger();

        assertSame(
                retryable,
                assertThrows(
                        RuntimeException.class,
                        () ->
                                service.callWithProviderFallback(
                                        TENANT,
                                        agent,
                                        primaryClient,
                                        failsPrimaryThenAnswers(retryable, attempts))));
        assertEquals(1, attempts.get());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
