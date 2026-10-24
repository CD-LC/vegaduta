package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Defensive MCP hardening (2026-08-05) - the standing "MCP tool output is untrusted data" system
 * directive {@link AgentChatService#mcpUntrustedDataDirective} contributes to every chat turn.
 *
 * <p>Two layers, matching the shape of the class's other per-turn directives (see {@link
 * AgentChatServiceContentGuardrailTest} for the harness this borrows): a pure gate test against the
 * package-private static method directly, and an end-to-end test proving it is actually wired into
 * the real {@code chat()} call - {@code AgentToolProvider#anyMcpToolsOfferedFor} is the only signal
 * this is gated on (see that method's javadoc for why tool origin can't be read back off the
 * resolved {@code List<ToolCallback>} itself).
 */
class AgentChatServiceMcpUntrustedDataDirectiveTest {

    // --- pure gate ---------------------------------------------------------------------

    @Test
    void directiveIsPresentWhenMcpToolsAreOffered() {
        String directive = ChatPromptDirectives.mcpUntrustedDataDirective(true);
        assertNotNull(directive);
        assertTrue(directive.toLowerCase(java.util.Locale.ROOT).contains("untrusted"));
    }

    @Test
    void directiveIsAbsentWhenNoMcpToolsAreOffered() {
        assertNull(ChatPromptDirectives.mcpUntrustedDataDirective(false));
    }

    // --- wired into a real chat() turn --------------------------------------------------

    private LlmGatewayService llmGatewayService;
    private SessionMemoryService sessionMemoryService;
    private Agent agent;
    private ChatClient chatClient;

    @BeforeEach
    void setUp() {
        llmGatewayService = mock(LlmGatewayService.class);
        sessionMemoryService = mock(SessionMemoryService.class);
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(llmGatewayService.resolveClient(any(), any(), any())).thenReturn(chatClient);
        when(sessionMemoryService.getHistory(any())).thenReturn(List.of());

        agent = mock(Agent.class);
        when(agent.getReasoningDepth()).thenReturn(ReasoningDepth.FAST);
        when(agent.getProviderName()).thenReturn(ProviderName.OPENAI);
        when(agent.getEnabledGuardrails()).thenReturn(Set.of());
        when(agent.getSystemPrompt()).thenReturn("You are a helpful assistant.");
    }

    private AgentChatService buildService(boolean anyMcpToolsOffered) {
        ProviderCredentialResolver credentialResolver = mock(ProviderCredentialResolver.class);
        when(credentialResolver.resolveActiveKey(any())).thenReturn("test-key");

        AgentService agentService = mock(AgentService.class);
        when(agentService.getEntity(any())).thenReturn(agent);
        when(agentService.resolveEffectiveModel(agent)).thenReturn("gpt-4o-mini");

        AgentToolPort agentToolProvider = mock(AgentToolPort.class);
        when(agentToolProvider.resolveToolsFor(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(agentToolProvider.anyMcpToolsOfferedFor(any(), any(), any(), any()))
                .thenReturn(anyMcpToolsOffered);
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
                NeverCancelledTurns.service(),
                java.time.Duration.ofSeconds(90),
                // Buffered-depth total-turn ceiling and SSE keepalive interval
                // (2026-09-17). Neither fires in this suite - every turn here resolves
                // in milliseconds - so both are just non-null values the constructor needs.
                java.time.Duration.ofSeconds(240),
                java.time.Duration.ofSeconds(20));
    }

    @Test
    void mcpDirectiveIsPrependedWhenAnMcpToolIsOffered() {
        AgentChatService service = buildService(true);

        service.chat(UUID.randomUUID(), "sess-1", "subject-1", "hello", null, null);

        List<Message> messages = capturedMessages();
        assertTrue(
                hasMcpDirective(messages),
                "expected the MCP untrusted-data directive to be prepended");
    }

    @Test
    void mcpDirectiveIsAbsentWhenNoMcpToolIsOffered() {
        AgentChatService service = buildService(false);

        service.chat(UUID.randomUUID(), "sess-1", "subject-1", "hello", null, null);

        List<Message> messages = capturedMessages();
        assertFalse(
                hasMcpDirective(messages),
                "did not expect the MCP untrusted-data directive on a pure built-in-tool turn");
    }

    @Test
    void mcpDirectiveIsAbsentWhenTheKillSwitchIsOff() {
        // app.chat.directives.mcp-untrusted.enabled=false - the ops
        // kill-switch added 2026-10-01. The flags are field-injected with
        // all-on initializers (see their javadoc on AgentChatService), so a
        // directly-constructed fixture starts all-on and the flag is flipped
        // by reflection, exactly what a Spring bean + ReflectionTestUtils
        // test of the same wiring would do.
        AgentChatService service = buildService(true);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "mcpUntrustedDirectiveEnabled", false);

        service.chat(UUID.randomUUID(), "sess-1", "subject-1", "hello", null, null);

        List<Message> messages = capturedMessages();
        assertFalse(
                hasMcpDirective(messages),
                "kill-switch off must suppress the MCP directive even when MCP tools are offered");
    }

    @SuppressWarnings("unchecked")
    private List<Message> capturedMessages() {
        // streamFast chains .messages(...) off .system(...)'s return value, not off
        // prompt() directly - with RETURNS_DEEP_STUBS each distinct method in the
        // chain gets its own fixed child mock, so verifying on chatClient.prompt()
        // itself watches the wrong instance (it only ever sees the .system(...) call).
        ChatClient.ChatClientRequestSpec afterSystem =
                chatClient.prompt().system(org.mockito.ArgumentMatchers.anyString());
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(afterSystem).messages(captor.capture());
        return captor.getValue();
    }

    private static boolean hasMcpDirective(List<Message> messages) {
        return messages.stream()
                .filter(m -> m instanceof SystemMessage)
                .map(m -> ((SystemMessage) m).getText())
                .anyMatch(text -> text != null && text.contains("MCP servers"));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
