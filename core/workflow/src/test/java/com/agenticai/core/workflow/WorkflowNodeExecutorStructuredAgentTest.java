package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.devices.DeviceCommandService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.output.EmailOutputService;
import com.agenticai.core.workflow.output.GoogleDriveOutputService;
import com.agenticai.core.workflow.output.PdfOutputRenderer;
import com.agenticai.core.workflow.output.TeamsOutputService;
import com.agenticai.core.workflow.output.TelegramOutputService;
import com.agenticai.core.workflow.output.WhatsAppOutputService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * Workflow -> Agent structured parameter passing: covers the additive branch {@link
 * WorkflowNodeExecutor#executeAgentNode} took on (see that method's javadoc). The single most
 * important test here is {@link #agentNodeWithNoInputMappingBehavesExactlyLikeBeforeThisFeature} -
 * every workflow saved before this feature existed has an Agent node config shaped exactly {@code
 * {agentId: "..."}}, and this feature must not change that path at all.
 */
class WorkflowNodeExecutorStructuredAgentTest {

    private AgentChatService agentChatService;
    private AgentService agentService;
    private ToolCallLogRepository toolCallLogRepository;
    private SubagentRunRepository subagentRunRepository;
    private WorkflowNodeExecutor executor;

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String RUN_ID = UUID.randomUUID().toString();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        agentChatService = mock(AgentChatService.class);
        agentService = mock(AgentService.class);
        toolCallLogRepository = mock(ToolCallLogRepository.class);
        subagentRunRepository = mock(SubagentRunRepository.class);
        when(toolCallLogRepository.findByAgentIdAndSessionIdOrderByStartedAtAsc(any(), any()))
                .thenReturn(List.of());

        executor =
                NodeExecutorTestFactory.create(
                        mock(OrchestratorService.class),
                        agentChatService,
                        agentService,
                        mock(ObjectProvider.class),
                        mock(WorkflowEventPublisher.class),
                        mock(EmailOutputService.class),
                        mock(TelegramOutputService.class),
                        mock(TeamsOutputService.class),
                        mock(WhatsAppOutputService.class),
                        mock(GoogleDriveOutputService.class),
                        mock(PdfOutputRenderer.class),
                        toolCallLogRepository,
                        mock(DeviceCommandService.class),
                        mock(AgentToolProvider.class),
                        new ObjectMapper(),
                        mock(ObjectProvider.class),
                        mock(LlmResponseCacheService.class),
                        mock(QueryComplexityClassifier.class),
                        mock(FreeTierChatClientService.class),
                        mock(LlmGatewayService.class),
                        mock(ProviderCredentialResolver.class),
                        mock(TenantLimits.class),
                        subagentRunRepository,
                        mock(ObjectProvider.class),
                        // 24th arg: ObjectProvider<SidecarClient> for the MEDIA node. A bare
                        // mock returns null from getIfAvailable(), i.e. "no sidecar in this
                        // context" - which is what every test here wants.
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class));
    }

    private WorkflowNodeDto agentNode(Map<String, Object> config) {
        return new WorkflowNodeDto(
                "agent-1", "agent", Map.of(), Map.of("config", config, "label", "Agent"));
    }

    @Test
    void agentNodeWithNoInputMappingBehavesExactlyLikeBeforeThisFeature() {
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && java.util.Objects.equals(r.sessionId(), RUN_ID)
                                                && java.util.Objects.equals(r.subjectKey(), RUN_ID)
                                                && java.util.Objects.equals(
                                                        r.userMessage(), "hello"))))
                .thenReturn(Flux.just("legacy reply"));

        WorkflowNodeDto node = agentNode(Map.of("agentId", AGENT_ID.toString()));
        WorkflowNodeExecutor.NodeExecutionResult result =
                executor.executeNode(null, "wf", RUN_ID, node, "hello", "system:test");

        assertEquals("OK", result.status());
        assertEquals("legacy reply", result.output());
        verify(agentChatService, never()).chatWithParams(any(), any(), any(), any(), any(), any());
        verify(subagentRunRepository, never()).save(any());
    }

    @Test
    void agentNodeWithInputMappingUsesStructuredPathAndRecordsAudit() {
        AgentChatService.StructuredAgentOutput output =
                new AgentChatService.StructuredAgentOutput(
                        "structured reply", 1.0, List.of(), List.of(), Map.of());
        when(agentChatService.chatWithParams(
                        eq(AGENT_ID),
                        eq(RUN_ID),
                        eq(RUN_ID),
                        any(AgentChatService.StructuredAgentInput.class),
                        eq("agent-1"),
                        any(Duration.class)))
                .thenReturn(output);

        WorkflowNodeDto node =
                agentNode(
                        Map.of(
                                "agentId", AGENT_ID.toString(),
                                "inputMapping", Map.of("query", "{{input}}")));
        WorkflowNodeExecutor.NodeExecutionResult result =
                executor.executeNode(null, "wf", RUN_ID, node, "hi there", "system:test");

        assertEquals("OK", result.status());
        assertEquals("structured reply", result.output());
        verify(subagentRunRepository, times(1)).save(any(SubagentRun.class));
        verify(agentChatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }

    @Test
    void agentNodeWithInputMappingAppliesOutputMappingField() {
        AgentChatService.StructuredAgentOutput output =
                new AgentChatService.StructuredAgentOutput(
                        "ignored for this test", 0.75, List.of(), List.of(), Map.of());
        when(agentChatService.chatWithParams(
                        eq(AGENT_ID),
                        eq(RUN_ID),
                        eq(RUN_ID),
                        any(AgentChatService.StructuredAgentInput.class),
                        eq("agent-1"),
                        any(Duration.class)))
                .thenReturn(output);

        WorkflowNodeDto node =
                agentNode(
                        Map.of(
                                "agentId", AGENT_ID.toString(),
                                "inputMapping", Map.of("query", "{{input}}"),
                                "outputMapping", "confidence"));
        WorkflowNodeExecutor.NodeExecutionResult result =
                executor.executeNode(null, "wf", RUN_ID, node, "hi", "system:test");

        assertEquals("0.75", result.output());
    }

    @Test
    void agentNodeWithInputMappingSkipsOnValidationFailureInsteadOfThrowing() {
        when(agentChatService.chatWithParams(
                        eq(AGENT_ID),
                        eq(RUN_ID),
                        eq(RUN_ID),
                        any(AgentChatService.StructuredAgentInput.class),
                        eq("agent-1"),
                        any(Duration.class)))
                .thenThrow(
                        new IllegalArgumentException("'query' is required and cannot be blank."));

        WorkflowNodeDto node =
                agentNode(
                        Map.of(
                                "agentId", AGENT_ID.toString(),
                                "inputMapping", Map.of("query", "")));
        WorkflowNodeExecutor.NodeExecutionResult result =
                executor.executeNode(null, "wf", RUN_ID, node, "", "system:test");

        assertEquals("SKIPPED", result.status());
        assertTrue(result.summary().contains("validation failed"));
        verify(subagentRunRepository, times(1)).save(any(SubagentRun.class));
    }
}
