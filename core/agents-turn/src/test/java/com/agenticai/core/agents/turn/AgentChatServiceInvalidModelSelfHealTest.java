package com.agenticai.core.agents.turn;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.hosted.HostedChainLink;
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
import com.agenticai.core.guardrails.moderation.ModerationService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.rl.api.RlPolicyPort;
import com.agenticai.core.rl.api.RlTurnRecorder;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantStatusGuard;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.FeatureFlagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Hosted-pool invalid-model SELF-HEAL trigger (2026-08-05) - {@link
 * AgentChatService#maybeSelfHealInvalidModelRow}, the {@code rungFailureListener} the real chat
 * path attaches to {@code attemptHostedChain}. When a pool rung dies with a 400/404 whose body
 * names a nonexistent/retired model id (detected by the ONE shared phrase list, {@link
 * ProviderErrorTranslator#isInvalidModelMessage}), it must launch {@link
 * PlatformHostedLlmProviderService#reautoSelectModelForRow} for that row asynchronously - and for
 * nothing else: other failure shapes, rows without a parseable id in their health key, and repeat
 * failures inside the per-row cooldown all stay untouched.
 *
 * <p>The heal itself runs on a background thread ({@code CompletableFuture.runAsync}), hence the
 * {@code timeout()} verifications; the negative cases need no waiting because every guard runs
 * synchronously BEFORE anything is ever submitted.
 */
class AgentChatServiceInvalidModelSelfHealTest {

    private AgentChatService service;
    private PlatformHostedLlmProviderService poolService;

    @BeforeEach
    void setUp() {
        poolService = mock(PlatformHostedLlmProviderService.class);
        service = buildService();
    }

    private AgentChatService buildService() {
        return AgentChatServiceTestFactory.create(
                mock(AgentService.class),
                mock(ProviderCredentialResolver.class),
                mock(LlmGatewayService.class),
                mock(SessionMemoryService.class),
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
                providerOf(poolService), // Load-test seam - unstubbed mock is false for every
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
     * A saved-row chain link, health key shape from {@code FreeTierChatClientService#healthKey}.
     */
    private static HostedChainLink savedRowLink(long rowId) {
        return new HostedChainLink(
                mock(ChatClient.class),
                "row:" + rowId,
                "Gemini (free tier)",
                "gemini-2.0-flash-lite",
                0,
                0,
                0);
    }

    private static UpstreamProviderException invalidModelError(int status) {
        return new UpstreamProviderException(
                status,
                "The LLM provider rejected the request (HTTP "
                        + status
                        + "): models/gemini-2.0-flash-lite is not found for API version v1beta",
                "The selected AI model isn't available from this provider anymore.");
    }

    @Test
    void invalidModel404LaunchesAnAsyncSelfHealForThatRow() {
        service.maybeSelfHealInvalidModelRow(savedRowLink(42L), invalidModelError(404));

        verify(poolService, timeout(2000)).reautoSelectModelForRow(eq(42L), anyString());
    }

    @Test
    void invalidModel400LaunchesTooButOnlyOncePerCooldown() {
        // Two failing turns in quick succession (well inside the 10-minute
        // per-row cooldown) must produce exactly ONE heal attempt - a
        // persistently-failing row must not thrash catalog refreshes.
        service.maybeSelfHealInvalidModelRow(savedRowLink(7L), invalidModelError(400));
        service.maybeSelfHealInvalidModelRow(savedRowLink(7L), invalidModelError(400));

        verify(poolService, timeout(2000).times(1)).reautoSelectModelForRow(eq(7L), anyString());
    }

    @Test
    void distinctRowsEachGetTheirOwnCooldownSlot() {
        service.maybeSelfHealInvalidModelRow(savedRowLink(1L), invalidModelError(404));
        service.maybeSelfHealInvalidModelRow(savedRowLink(2L), invalidModelError(404));

        verify(poolService, timeout(2000)).reautoSelectModelForRow(eq(1L), anyString());
        verify(poolService, timeout(2000)).reautoSelectModelForRow(eq(2L), anyString());
    }

    @Test
    void nonInvalidModelFailuresNeverTriggerAHeal() {
        // Rate limit: right status family is absent AND no invalid-model phrase.
        service.maybeSelfHealInvalidModelRow(
                savedRowLink(42L),
                new UpstreamProviderException(
                        429, "HTTP 429: rate_limit_exceeded", "The AI provider is busy."));
        // A 400 without an invalid-model phrase (e.g. a parameter problem).
        service.maybeSelfHealInvalidModelRow(
                savedRowLink(42L),
                new UpstreamProviderException(
                        400,
                        "HTTP 400: unsupported parameter: top_p",
                        "The AI provider couldn't process this request."));
        // A 500 that happens to mention a model is still not a 400/404.
        service.maybeSelfHealInvalidModelRow(
                savedRowLink(42L),
                new UpstreamProviderException(
                        500,
                        "HTTP 500: no healthy upstream, model not found in cluster",
                        "Temporarily unavailable."));
        // Not a provider failure at all.
        service.maybeSelfHealInvalidModelRow(
                savedRowLink(42L), new GuardrailViolationException("some_tool", "blocked"));

        verifyNoInteractions(poolService);
    }

    @Test
    void anUnsavedRowsFallbackHealthKeyIsNeverParsedIntoAHeal() {
        // FreeTierChatClientService#healthKey's unsaved/mocked-row form is
        // "row:<label>:<model>" - nothing to heal, must be skipped, not thrown on.
        HostedChainLink unsaved =
                new HostedChainLink(
                        mock(ChatClient.class),
                        "row:Gemini:gemini-2.0-flash-lite",
                        "Gemini",
                        "gemini-2.0-flash-lite",
                        0,
                        0,
                        0);
        HostedChainLink noKey =
                new HostedChainLink(mock(ChatClient.class), null, "provider", null, 0, 0, 0);

        service.maybeSelfHealInvalidModelRow(unsaved, invalidModelError(404));
        service.maybeSelfHealInvalidModelRow(noKey, invalidModelError(404));

        verifyNoInteractions(poolService);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
