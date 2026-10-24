package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.turn.AgentLabCompletionService.LabCompletionRequest;
import com.agenticai.core.agents.turn.AgentLabCompletionService.LabTurnMessage;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The lab's optional with-tools mode: off as shipped (a request for tools is refused, never answered without
 * them), the tool-less completion untouched when it is not asked for, and - when asked for and enabled - the
 * agent's tools come from the tool pipeline a chat turn uses and are attached to the prompt.
 */
class AgentLabCompletionServiceWithToolsTest {

    private static final String TENANT = "t1";

    private final AgentService agentService = mock(AgentService.class);
    private final TenantLimits limits = mock(TenantLimits.class);
    private final FreeTierChatClientService pool = mock(FreeTierChatClientService.class);
    private final ProviderCredentialResolver credentials = mock(ProviderCredentialResolver.class);
    private final LlmGatewayService gateway = mock(LlmGatewayService.class);
    private final ProviderErrorTranslator translator = mock(ProviderErrorTranslator.class);
    private final AgentToolPort toolPort = mock(AgentToolPort.class);
    private final UUID agentId = UUID.randomUUID();

    private AgentLabCompletionService service;

    /** A model that records the prompt it was handed. */
    private static final class RecordingChatModel implements ChatModel {
        Prompt seen;

        @Override
        public ChatResponse call(Prompt prompt) {
            this.seen = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))));
        }
    }

    private static ToolCallback tool(String name) {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
        when(tool.getToolDefinition()).thenReturn(definition);
        return tool;
    }

    @BeforeEach
    void setUp() {
        Agent agent = new Agent("Support copilot", "Stored prompt.", ProviderName.ANTHROPIC, "claude-sonnet-5",
            900, 0.7, 0.95, false, ExperienceMode.ADVANCED);
        when(agentService.getEntity(agentId)).thenReturn(agent);
        when(agentService.resolveEffectiveModel(agent)).thenReturn("claude-sonnet-5");
        when(limits.plan(TENANT)).thenReturn(TenantPlan.PREMIUM);
        when(credentials.resolveActiveKey(any())).thenReturn("key");
        service = new AgentLabCompletionService(agentService, limits, pool, credentials, gateway, translator);
        TenantContext.set(TenantContext.of(TENANT, "u1", Set.of()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private LabCompletionRequest request(boolean withTools) {
        return new LabCompletionRequest(
            agentId, null, List.of(), "Refund INV-1", null, null, "u1", "lab:run-1:0", withTools);
    }

    @Test
    void asShippedARequestForToolsIsRefusedBeforeAnythingIsSpent() {
        service.useToolPort(toolPort);

        IllegalArgumentException refused =
            assertThrows(IllegalArgumentException.class, () -> service.complete(request(true)));

        assertTrue(refused.getMessage().contains("app.agent-lab.with-tools.enabled"), refused.getMessage());
        assertFalse(service.withToolsAvailable());
        verifyNoInteractions(gateway, pool, toolPort);
    }

    @Test
    void enabledButWithoutTheToolPipelineItFailsInsteadOfAnsweringWithoutTools() {
        service.withToolsEnabled = true;

        assertThrows(IllegalStateException.class, () -> service.complete(request(true)));
        assertFalse(service.withToolsAvailable());
        verifyNoInteractions(gateway, pool);
    }

    @Test
    void theDefaultRequestIsToollessAndNeverTouchesTheToolPipeline() {
        RecordingChatModel model = new RecordingChatModel();
        when(gateway.resolveClient(eq(TENANT), any(GatewayCallSpec.class))).thenReturn(ChatClient.builder(model).build());
        service.useToolPort(toolPort);
        service.withToolsEnabled = true; // even with the mode on, a caller that does not ask stays tool-less

        String out = service.complete(new LabCompletionRequest(
            agentId, null, List.of(), "Q", null, null, "u1", "lab:run-1:0"));

        assertEquals("answer", out);
        verify(toolPort, never()).resolveToolsFor(any(), anyString(), anyString(), anyString(), any(), any());
        assertTrue(model.seen.getOptions() == null
            || !(model.seen.getOptions() instanceof ToolCallingChatOptions o) || o.getToolCallbacks().isEmpty());
    }

    @Test
    void withToolsEnabledTheAgentsToolsComeFromTheChatPipelineAndRideOnThePrompt() {
        RecordingChatModel model = new RecordingChatModel();
        ChatClient client = ChatClient.builder(model).build();
        ToolCallback refund = tool("issue_refund");
        when(toolPort.resolveToolsFor(agentId, "lab:run-1:0", "u1", "Refund INV-1", client, ReasoningDepth.FAST))
            .thenReturn(List.of(refund));
        service.useToolPort(toolPort);
        service.withToolsEnabled = true;

        String out = service.invokeWithTools(
            client, "Be careful.", List.of(new LabTurnMessage("user", "Hi")), "Refund INV-1", agentId,
            "lab:run-1:0", "u1");

        assertEquals("answer", out);
        verify(toolPort).resolveToolsFor(agentId, "lab:run-1:0", "u1", "Refund INV-1", client, ReasoningDepth.FAST);
        ToolCallingChatOptions options = assertInstanceOf(ToolCallingChatOptions.class, model.seen.getOptions());
        assertEquals(List.of(refund), options.getToolCallbacks());
        assertEquals("Be careful.", model.seen.getInstructions().get(0).getText());
    }

    @Test
    void withToolsRequestedThroughCompleteUsesTheClientTheLabBuiltForTheAgent() {
        RecordingChatModel model = new RecordingChatModel();
        ChatClient client = ChatClient.builder(model).build();
        when(gateway.resolveClient(eq(TENANT), any(GatewayCallSpec.class))).thenReturn(client);
        ToolCallback search = tool("search_knowledge_base");
        when(toolPort.resolveToolsFor(any(), anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(List.of(search));
        service.useToolPort(toolPort);
        service.withToolsEnabled = true;

        service.complete(request(true));

        ToolCallingChatOptions options = assertInstanceOf(ToolCallingChatOptions.class, model.seen.getOptions());
        assertEquals(List.of(search), options.getToolCallbacks());
        assertTrue(service.withToolsAvailable());
    }

    @Test
    void anAgentWithNoToolsStillGetsAPlainCompletion() {
        RecordingChatModel model = new RecordingChatModel();
        ChatClient client = ChatClient.builder(model).build();
        when(toolPort.resolveToolsFor(any(), anyString(), anyString(), anyString(), any(), any())).thenReturn(List.of());
        service.useToolPort(toolPort);
        service.withToolsEnabled = true;

        assertEquals("answer", service.invokeWithTools(client, null, List.of(), "Q", agentId, "lab:run-1:0", "u1"));
    }
}
