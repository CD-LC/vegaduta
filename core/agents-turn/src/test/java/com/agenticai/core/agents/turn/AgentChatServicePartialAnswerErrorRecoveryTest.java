package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.loadtest.LoadTestTenantAllowlist;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.ChatTurn;
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
import com.agenticai.core.guardrails.GuardrailViolationLogger;
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
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * "Half response" fix (2026-08-10) - live user report: a tool call that fails (errors or times out)
 * AFTER the model has already streamed some text used to just kill the SSE stream mid-turn, because
 * BOTH of {@code streamFast}'s existing recovery paths (the Gemini thought-signature retry and the
 * fallback-provider retry) are deliberately gated on {@code accumulated.isEmpty()} - neither one is
 * safe to run once tokens have already reached the client (see their own comments in {@link
 * AgentChatService}: a retry there means a brand-new provider call replaying the ENTIRE prompt from
 * scratch, which would append a second, independent answer after the first rather than continue
 * it).
 *
 * <p>This pins the new, final safety net: once {@code accumulated} is non-empty, an
 * otherwise-unhandled error no longer reaches the client raw - the stream instead emits {@link
 * AgentChatService#INTERRUPTED_REPLY_MARKER} (the exact same honest-truncation copy {@code
 * persistInterruptedAssistantTurn} already writes to session memory on a hard error/cancel, per
 * ADR-007) and completes NORMALLY. Drives the real {@code chat()} -&gt; {@code streamFast()}
 * pipeline end-to-end (not just {@code withAssistantTurnPersistence} in isolation, which cannot see
 * this mid-chain {@code onErrorResume}) - harness borrowed from {@link
 * AgentChatServiceVoiceToolResolutionTest} / {@link AgentChatServiceModerationTest}, including the
 * RETURNS_DEEP_STUBS fixed-child-mock-per-chain-step pattern they document - abandoned here in
 * favor of {@code AnswerConsistencyCheckerTest}'s explicit per-level-mock approach, which is what
 * actually made the terminal {@code .content()} stub land on the same mock the real call site
 * invokes (deep-stub matcher chaining under a single outer {@code when(...)} does not reliably wire
 * up a stubbed return for the FINAL call the way it does for {@code verify()}).
 */
class AgentChatServicePartialAnswerErrorRecoveryTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.StreamResponseSpec streamResponseSpec;
    private SessionMemoryService sessionMemoryService;

    private AgentChatService buildService(ProviderErrorTranslator providerErrorTranslator) {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(anyList())).thenReturn(requestSpec);
        when(requestSpec.toolContext(org.mockito.ArgumentMatchers.anyMap())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);

        LlmGatewayService llmGatewayService = mock(LlmGatewayService.class);
        when(llmGatewayService.resolveClient(any(), any(), any())).thenReturn(chatClient);

        sessionMemoryService = mock(SessionMemoryService.class);
        when(sessionMemoryService.getHistory(any())).thenReturn(List.of());

        ProviderCredentialResolver credentialResolver = mock(ProviderCredentialResolver.class);
        when(credentialResolver.resolveActiveKey(any())).thenReturn("test-key");

        Agent agent = mock(Agent.class);
        when(agent.getReasoningDepth()).thenReturn(ReasoningDepth.FAST);
        when(agent.getProviderName()).thenReturn(ProviderName.OPENAI);
        when(agent.getSystemPrompt()).thenReturn("You are a helpful assistant.");
        // No fallback provider configured - isolates this test to the NEW
        // catch-all path, rather than the pre-existing fallback-provider
        // retry (which is correctly gated to never fire once accumulated is
        // non-empty anyway, but leaving this null keeps the scenario honest:
        // the realistic case from the bug report has nothing left to fall
        // back to either).
        when(agent.getFallbackProviderName()).thenReturn(null);

        AgentService agentService = mock(AgentService.class);
        when(agentService.getEntity(any())).thenReturn(agent);
        when(agentService.resolveEffectiveModel(agent)).thenReturn("gpt-4o-mini");

        AgentToolPort agentToolProvider = mock(AgentToolPort.class);
        when(agentToolProvider.resolveToolsFor(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        FeatureFlagService featureFlagService = mock(FeatureFlagService.class);
        when(featureFlagService.evaluateActiveFlags(any())).thenReturn(Map.of());

        return AgentChatServiceTestFactory.create(
                agentService,
                credentialResolver,
                llmGatewayService,
                sessionMemoryService,
                providerErrorTranslator,
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
                providerOf((ContentGuardrailEngine) null),
                providerOf((GuardrailViolationLogger) null),
                mock(AgentGuardrailService.class),
                mock(InlineAttachmentProcessor.class),
                mock(ResourceAccessService.class),
                mock(VisionUsageService.class),
                mock(VisionSessionService.class),
                providerOf((com.agenticai.core.providers.PlatformHostedLlmProviderService) null),
                mock(LoadTestTenantAllowlist.class),
                neverCancelled(),
                java.time.Duration.ofSeconds(90),
                // Buffered-depth total-turn ceiling and SSE keepalive interval
                // (2026-09-17). Neither fires in this suite - every turn here resolves
                // in milliseconds - so both are just non-null values the constructor needs.
                java.time.Duration.ofSeconds(240),
                java.time.Duration.ofSeconds(20));
    }

    private void stubStreamedContent(Flux<String> content) {
        when(streamResponseSpec.content()).thenReturn(content);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @Test
    void toolCallErrorAfterPartialTextCompletesGracefullyInsteadOfPropagatingRaw() {
        ProviderErrorTranslator providerErrorTranslator = mock(ProviderErrorTranslator.class);
        RuntimeException toolFailure = new RuntimeException("tool call timed out");
        UpstreamProviderException translated =
                new UpstreamProviderException(
                        502, "tool call timed out", "Something went wrong, please retry.");
        when(providerErrorTranslator.translate(any(), any())).thenReturn(translated);

        AgentChatService service = buildService(providerErrorTranslator);
        stubStreamedContent(
                Flux.concat(Flux.just("Let me check that for you..."), Flux.error(toolFailure)));

        Flux<String> result =
                service.chat(
                        AGENT_ID, "sess-partial-1", "subject-1", "what's the weather", null, null);

        // The defining assertion: this must NOT throw. Before the fix,
        // .block() here rethrows the raw/translated provider error and the
        // SSE stream dies mid-turn - exactly the "half response" bug.
        List<String> emitted = result.collectList().block();

        assertNotNull(emitted);
        assertEquals(
                List.of("Let me check that for you...", AgentChatService.INTERRUPTED_REPLY_MARKER),
                emitted,
                "expected the already-streamed prefix followed by ONE graceful closing chunk, not"
                        + " an error");

        String fullText = String.join("", emitted);
        assertTrue(
                fullText.contains(AgentChatService.INTERRUPTED_REPLY_MARKER),
                "the graceful chunk must reuse the existing honest-truncation marker, not new"
                        + " copy");

        // The stream completed normally, so persistence goes through the
        // ordinary complete-path (audit/appendTurn/quality-judge), not the
        // doOnError partial-persist branch - either way the stored text ends
        // up honestly marked as interrupted, per ADR-007. Two turns are
        // appended per a normal chat() call regardless of this fix (the user
        // turn, appended up-front, and the assistant turn) - find the
        // assistant one rather than assuming call count.
        ArgumentCaptor<ChatTurn> turn = ArgumentCaptor.forClass(ChatTurn.class);
        org.mockito.Mockito.verify(sessionMemoryService, org.mockito.Mockito.times(2))
                .appendTurn(any(), turn.capture());
        ChatTurn assistantTurn =
                turn.getAllValues().stream()
                        .filter(
                                t ->
                                        t.role()
                                                == com.agenticai.core.agents.memory.MessageRole
                                                        .ASSISTANT)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "expected an assistant turn to be persisted"));
        assertTrue(assistantTurn.content().endsWith(AgentChatService.INTERRUPTED_REPLY_MARKER));
        assertFalse(
                assistantTurn.content().equals(AgentChatService.INTERRUPTED_REPLY_MARKER),
                "the marker must be appended to the real partial answer, not replace it");
    }

    /** A cancel-watch that never fires; a bare mock returns null and NPEs in takeUntilOther. */
    private static ChatTurnCancelService neverCancelled() {
        ChatTurnCancelService cancel = mock(ChatTurnCancelService.class);
        when(cancel.watchForCancellation(any(), any())).thenReturn(Flux.never());
        return cancel;
    }
}
