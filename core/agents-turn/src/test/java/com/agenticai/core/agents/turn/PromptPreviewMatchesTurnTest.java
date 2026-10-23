package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The prompt preview is the prompt a turn sends: the same fixture agent is run through a real
 * {@code chat()} (the system text and the per-turn system block are captured at the model client)
 * and through {@link PromptPreviewService#preview}; both must be identical, with and without
 * overrides, and every block of the preview carries the key, scope and version that produced it.
 */
class PromptPreviewMatchesTurnTest {

    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private LlmGatewayService llmGatewayService;
    private SessionMemoryService sessionMemoryService;
    private Agent agent;
    private ChatClient chatClient;
    private AgentService agentService;
    private AgentToolPort agentToolProvider;
    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final PromptProperties props = new PromptProperties();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, props);

    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("Does " + name).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static final List<ToolCallback> TOOLS =
            List.of(tool("remember_fact"), tool("search_tools"), tool("browser_snapshot"), tool("search_code"));

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
        when(agent.getSystemPrompt()).thenReturn("You are a support agent.");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private AgentChatService buildService() {
        ProviderCredentialResolver credentialResolver = mock(ProviderCredentialResolver.class);
        when(credentialResolver.resolveActiveKey(any())).thenReturn("test-key");

        agentService = mock(AgentService.class);
        when(agentService.getEntity(any())).thenReturn(agent);
        when(agentService.requireAccess(any(), any())).thenReturn(agent);
        when(agentService.resolveEffectiveModel(agent)).thenReturn("gpt-4o-mini");

        agentToolProvider = mock(AgentToolPort.class);
        when(agentToolProvider.resolveToolsFor(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(TOOLS);
        when(agentToolProvider.resolveToolsFor(any(), any(), any(), any(), any(), any(ReasoningDepth.class))).thenReturn(TOOLS);
        when(agentToolProvider.anyMcpToolsOfferedFor(any(), any(), any(), any())).thenReturn(true);
        when(agentToolProvider.skillsPromptBlock(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn("\n\n## Skills\nuse load_skill");
        when(agentToolProvider.proceduresPromptBlock(any())).thenReturn("\n\n## Procedures\nfollow them");
        FeatureFlagService featureFlagService = mock(FeatureFlagService.class);
        when(featureFlagService.evaluateActiveFlags(any())).thenReturn(Map.of());

        AgentGuardrailService agentGuardrailService =
                new AgentGuardrailService(mock(TenantLimits.class), mock(AgentRepository.class), mock(ResourceAccessService.class));

        AgentChatService service =
                AgentChatServiceTestFactory.create(
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
                        java.time.Duration.ofSeconds(240),
                        java.time.Duration.ofSeconds(20));
        service.usePromptDirectiveSettings(PromptDirectiveSettings.forTest(resolver));
        return service;
    }

    private String sentSystemText() {
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(chatClient.prompt()).system(system.capture());
        return system.getValue();
    }

    @SuppressWarnings("unchecked")
    private String sentMemoryBlock() {
        ChatClient.ChatClientRequestSpec afterSystem = chatClient.prompt().system(org.mockito.ArgumentMatchers.anyString());
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(afterSystem).messages(captor.capture());
        return captor.getValue().stream()
                .filter(m -> m instanceof SystemMessage)
                .map(m -> ((SystemMessage) m).getText())
                .findFirst()
                .orElse(null);
    }

    private void assertPreviewIsWhatTheTurnSent(AgentChatService service) {
        service.chat(AGENT_ID, "sess-1", "subject-1", "hello", null, null);
        String system = sentSystemText();
        String memory = sentMemoryBlock();

        PromptPreviewService.Preview preview =
                new PromptPreviewService(service, props)
                        .preview(AGENT_ID, new PromptPreviewService.Request("hello", null, false, false));

        assertThat(preview.systemPrompt()).isEqualTo(system);
        assertThat(preview.memoryBlock()).isEqualTo(memory);
        assertThat(preview.tools()).extracting(PromptPreviewService.ToolView::name)
                .containsExactly("remember_fact", "search_tools", "browser_snapshot", "search_code");
        // The blocks are the prompt, cut at their seams.
        String joinedMemory =
                preview.blocks().stream().filter(b -> b.message().equals("memory")).map(PromptPreviewService.BlockView::text)
                        .collect(java.util.stream.Collectors.joining("\n\n"));
        assertThat(joinedMemory).isEqualTo(memory == null ? "" : memory);
        assertThat(preview.blocks().stream().filter(b -> b.message().equals("system")).map(PromptPreviewService.BlockView::id))
                .containsExactly("agent.system_prompt", "procedures", "skills");
    }

    @Test
    void withNoOverridesThePreviewIsExactlyWhatARealTurnSends() {
        AgentChatService service = buildService();

        assertPreviewIsWhatTheTurnSent(service);

        PromptPreviewService.Preview preview =
                new PromptPreviewService(service, props).preview(AGENT_ID, new PromptPreviewService.Request("hello", null, false, false));
        assertThat(preview.blocks()).filteredOn(b -> b.key() != null).extracting(PromptPreviewService.BlockView::key)
                .containsExactly(
                        // the skills block carries the provenance of its opening wording
                        "skills.header",
                        "directive.current_date",
                        "directive.memory_usage",
                        "directive.tool_trust",
                        "directive.mcp_untrusted",
                        "directive.tool_search",
                        "directive.browser_v2");
        assertThat(preview.blocks()).filteredOn(b -> b.key() != null).allSatisfy(b -> {
            assertThat(b.source()).isEqualTo("default");
            assertThat(b.version()).isNull();
            assertThat(b.hash()).hasSize(12);
            assertThat(b.estimatedTokens()).isPositive();
        });
        assertThat(preview.promptEstimatedTokens()).isPositive();
        assertThat(preview.toolsEstimatedTokens()).isPositive();
        assertThat(preview.overBudget()).isFalse();
    }

    @Test
    void withPlatformOverridesThePreviewStillMatchesAndTagsTheScope() {
        store.put(new PromptOverride(PromptScope.PLATFORM, "", "directive.tool_search", "en", "Search first, house rule.", true, 4, "root", Instant.now()));
        store.put(new PromptOverride(PromptScope.PLATFORM, "", "directive.browser_v2", "en", null, false, 2, "root", Instant.now()));
        AgentChatService service = buildService();

        assertPreviewIsWhatTheTurnSent(service);

        PromptPreviewService.Preview preview =
                new PromptPreviewService(service, props).preview(AGENT_ID, new PromptPreviewService.Request("hello", null, false, false));
        assertThat(preview.memoryBlock()).contains("Search first, house rule.").doesNotContain("Browser tools:");
        PromptPreviewService.BlockView search =
                preview.blocks().stream().filter(b -> "directive.tool_search".equals(b.key())).findFirst().orElseThrow();
        assertThat(search.source()).isEqualTo("platform");
        assertThat(search.scope()).isEqualTo("platform");
        assertThat(search.version()).isEqualTo(4);
        assertThat(preview.blocks()).noneMatch(b -> "directive.browser_v2".equals(b.key()));
    }

    @Test
    void theSmallModelFlagSelectsTheCompactWording() {
        AgentChatService service = buildService();

        PromptPreviewService.Preview preview =
                new PromptPreviewService(service, props).preview(AGENT_ID, new PromptPreviewService.Request("hello", null, true, false));

        assertThat(preview.smallModel()).isTrue();
        assertThat(preview.blocks()).extracting(PromptPreviewService.BlockView::key).contains("directive.tool_trust.compact")
                .doesNotContain("directive.tool_trust");
    }

    @Test
    void thePreviewFlagsAPromptOverTheConfiguredTokenBudgetWithoutTruncatingIt() {
        PromptProperties tight = new PromptProperties();
        tight.setSystemPromptTokenBudget(10);
        AgentChatService service = buildService();

        PromptPreviewService.Preview preview =
                new PromptPreviewService(service, tight).preview(AGENT_ID, new PromptPreviewService.Request("hello", null, false, false));

        assertThat(preview.tokenBudget()).isEqualTo(10);
        assertThat(preview.overBudget()).isTrue();
        assertThat(preview.promptEstimatedTokens()).isGreaterThan(10);
        assertThat(preview.memoryBlock()).contains("Today's date is");
        assertThat(preview.tokenEstimateNote()).contains("rule of thumb");
    }

    @Test
    void thePreviewTouchesNoSessionAndCallsNoModel() {
        AgentChatService service = buildService();

        new PromptPreviewService(service, props).preview(AGENT_ID, new PromptPreviewService.Request("hello", null, false, false));

        org.mockito.Mockito.verify(sessionMemoryService, org.mockito.Mockito.never()).appendTurn(any(), any());
        org.mockito.Mockito.verifyNoInteractions(llmGatewayService);
        org.mockito.Mockito.verifyNoInteractions(chatClient);
    }

    @Test
    void thePreviewNeedsEditorAccessToTheAgent() {
        AgentChatService service = buildService();
        when(agentService.requireAccess(any(), any())).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new PromptPreviewService(service, props).preview(AGENT_ID, null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        org.mockito.Mockito.verify(agentService).requireAccess(AGENT_ID, com.agenticai.core.sharing.PermissionLevel.EDITOR);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
