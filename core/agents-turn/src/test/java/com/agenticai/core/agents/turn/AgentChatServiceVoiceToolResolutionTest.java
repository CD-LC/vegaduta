package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
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
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.chat.ProviderErrorTranslator;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Voice-triggered tool execution audit (2026-08-06). The audit's claim under verification: a voice
 * turn is not a separate, tool-blind code path - it reaches the exact same {@link
 * AgentToolProvider#resolveToolsFor} resolution and the exact same {@code
 * ChatClient#toolCallbacks(...)} wiring a text/ Playground turn does, because {@link
 * AgentChatService#chat} has no channel/source parameter anywhere in its overload chain to
 * special-case on.
 *
 * <p>Both real voice surfaces call the identical six-argument overload this test drives:
 *
 * <ul>
 *   <li>{@code VoiceCallController#turn} (core/voice, push-to-talk) calls {@code
 *       agentChatService.chat(agentId, resolvedSessionId, subjectKey, resolvedUserText, null,
 *       null)} in-process (VoiceCallController.java:594).
 *   <li>{@code voice-agent/app/llm_core_bridge.py}'s {@code CoreChatBridgeLLM._run()} POSTs the
 *       same shape - {@code {"message": ...}} (+ optional {@code sessionId}) - to {@code
 *       AgentController#chat} (core/agents), which itself calls this same overload from a
 *       JWT-authenticated principal.
 * </ul>
 *
 * <p>This test does not re-verify {@code VoiceCallController} plumbing (covered by {@code
 * VoiceCallControllerTest}) - it verifies the other half of the chain: that once {@code
 * AgentChatService#chat} is invoked with that exact shape, whatever {@code AgentToolProvider}
 * resolves for the agent (a {@code CustomCodeToolCallback}, an MCP connector callback, a delegation
 * tool, etc.) is not dropped anywhere between resolution and the live LLM call - it lands in {@code
 * .toolCallbacks(...)} on the same {@code ChatClient} request every other caller (web console,
 * omnichannel, Quick Task) builds. Harness borrowed from {@link
 * AgentChatServiceMcpUntrustedDataDirectiveTest}, which documents the RETURNS_DEEP_STUBS
 * chain-capture gotcha this also relies on.
 */
class AgentChatServiceVoiceToolResolutionTest {

    private static final UUID AGENT_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");

    private ChatClient chatClient;
    private AgentToolPort agentToolProvider;
    private ToolCallback resolvedVoiceTool;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        agentToolProvider = mock(AgentToolPort.class);

        // Stands in for whatever AgentToolProvider#resolveToolsFor actually
        // returns in production for a tool-bearing agent - a
        // CustomCodeToolCallback, an MCP-backed callback, a
        // DelegateToAgentToolCallback, etc. The test's assertions only
        // depend on this being A ToolCallback that resolution hands back,
        // not on which concrete implementation it is. A real name is needed
        // (distinct from remember_fact/media-generation names) because
        // AgentChatService#memoryUsageDirective scans the resolved list's
        // ToolDefinition#name() on every turn - exactly the kind of
        // production behavior this test wants to exercise for real, not
        // stub around.
        ToolDefinition toolDefinition = mock(ToolDefinition.class);
        when(toolDefinition.name()).thenReturn("smart_home_set_light_state");
        resolvedVoiceTool = mock(ToolCallback.class);
        when(resolvedVoiceTool.getToolDefinition()).thenReturn(toolDefinition);
    }

    private AgentChatService buildService() {
        // The stream wraps its result in takeUntilOther(watchForCancellation): a real Flux runs the
        // operator's non-null check, so the watch must return a Flux, not the mock's default null.
        ChatTurnCancelService cancelService = mock(ChatTurnCancelService.class);
        when(cancelService.watchForCancellation(any(), any()))
                .thenReturn(reactor.core.publisher.Flux.never());
        LlmGatewayService llmGatewayService = mock(LlmGatewayService.class);
        when(llmGatewayService.resolveClient(any(), any(), any())).thenReturn(chatClient);

        SessionMemoryService sessionMemoryService = mock(SessionMemoryService.class);
        when(sessionMemoryService.getHistory(any())).thenReturn(List.of());

        Agent agent = mock(Agent.class);
        when(agent.getReasoningDepth()).thenReturn(ReasoningDepth.FAST);
        when(agent.getProviderName()).thenReturn(ProviderName.OPENAI);
        when(agent.getEnabledGuardrails()).thenReturn(Set.of());
        when(agent.getSystemPrompt()).thenReturn("You are a helpful voice-enabled assistant.");

        ProviderCredentialResolver credentialResolver = mock(ProviderCredentialResolver.class);
        when(credentialResolver.resolveActiveKey(any())).thenReturn("test-key");

        AgentService agentService = mock(AgentService.class);
        when(agentService.getEntity(any())).thenReturn(agent);
        when(agentService.resolveEffectiveModel(agent)).thenReturn("gpt-4o-mini");

        // The crux of the audit claim: resolution is agent-config-driven
        // only - no channel argument exists to gate this on, so it is
        // stubbed identically to how a text-turn test would stub it. This
        // agent's resolved tool list includes the marker tool above.
        when(agentToolProvider.resolveToolsFor(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(resolvedVoiceTool));
        when(agentToolProvider.anyMcpToolsOfferedFor(any(), any(), any(), any())).thenReturn(false);

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
                providerOf((ContentGuardrailEngine) null),
                providerOf(mock(GuardrailViolationLogger.class)),
                agentGuardrailService,
                mock(InlineAttachmentProcessor.class),
                mock(ResourceAccessService.class),
                mock(VisionUsageService.class),
                mock(VisionSessionService.class),
                providerOf((com.agenticai.core.providers.PlatformHostedLlmProviderService) null),
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
    void aVoiceShapedTurnResolvesToolsThroughTheSameProviderCallATextTurnUses() {
        AgentChatService service = buildService();
        String sessionId = "voice-sess-1";
        String subjectKey = "voice-subject-1";
        String userMessage = "turn the living room lights on";

        // Exactly VoiceCallController.java:594's call shape:
        // agentChatService.chat(agentId, resolvedSessionId, subjectKey, resolvedUserText, null,
        // null)
        service.chat(AGENT_ID, sessionId, subjectKey, userMessage, null, null);

        // No special "voice" resolution path exists to divert to - this is
        // literally the only resolveToolsFor call AgentChatService ever
        // makes, invoked with this turn's real agent/session/subject/message
        // and the same chatClient the turn is about to stream from.
        // Sixth argument (2026-09-17): THIS turn's dispatched reasoning depth, which
        // AgentToolProvider now gates RIGOROUS's per-tool verifier on instead of re-reading
        // the agent's stored depth. This turn is not force-fast, so it is the agent's own.
        verify(agentToolProvider)
                .resolveToolsFor(
                        any(),
                        eq(AGENT_ID),
                        eq(sessionId),
                        eq(subjectKey),
                        eq(userMessage),
                        eq(chatClient),
                        any(),
                        eq(ReasoningDepth.FAST));
    }

    @Test
    void theResolvedToolReachesTheActualLlmToolCallbacksWiringOnAVoiceShapedTurn() {
        AgentChatService service = buildService();

        service.chat(
                AGENT_ID,
                "voice-sess-2",
                "voice-subject-2",
                "what's on my calendar today",
                null,
                null);

        List<ToolCallback> toolsSentToLlm = capturedToolCallbacks();
        assertTrue(
                toolsSentToLlm.contains(resolvedVoiceTool),
                "expected the agent's resolved tool (e.g. a CustomCodeToolCallback/MCP tool) to"
                    + " reach ChatClient#toolCallbacks(...) on a voice-shaped turn exactly as it"
                    + " would on a text turn");
    }

    @SuppressWarnings("unchecked")
    private List<ToolCallback> capturedToolCallbacks() {
        // See AgentChatServiceMcpUntrustedDataDirectiveTest#capturedMessages -
        // with RETURNS_DEEP_STUBS each distinct method in the fluent chain
        // gets its OWN fixed child mock, so the verify() must target the
        // exact mock instance streamFast's chain leaves toolCallbacks(...)
        // on: chatClient.prompt().system(...).messages(...).user(...).
        ChatClient.ChatClientRequestSpec afterUser =
                chatClient
                        .prompt()
                        .system(anyString())
                        .messages(anyList())
                        .user(any(Consumer.class));
        ArgumentCaptor<List<ToolCallback>> captor = ArgumentCaptor.forClass(List.class);
        verify(afterUser).toolCallbacks(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
