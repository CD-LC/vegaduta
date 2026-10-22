package com.agenticai.core.workflow;

import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.devices.DeviceCommandService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.knowledge.KnowledgeSearchService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.humaninput.WorkflowInputRequestService;
import com.agenticai.core.workflow.output.EmailOutputService;
import com.agenticai.core.workflow.output.GoogleDriveOutputService;
import com.agenticai.core.workflow.output.PdfOutputRenderer;
import com.agenticai.core.workflow.output.TeamsOutputService;
import com.agenticai.core.workflow.output.TelegramOutputService;
import com.agenticai.core.workflow.output.WhatsAppOutputService;
import com.agenticai.core.workflow.voicemenu.WorkflowVoiceMenuRequestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Shared fixture of the {@link WorkflowNodeExecutor} characterisation tests: every collaborator
 * mocked.
 */
@SuppressWarnings("unchecked")
abstract class WorkflowNodeExecutorCharacterizationTestBase {

    static final UUID WF = UUID.randomUUID();
    static final String RUN = UUID.randomUUID().toString();
    static final UUID AGENT = UUID.randomUUID();

    OrchestratorService orchestrator;
    AgentChatService chat;
    AgentService agentService;
    ObjectProvider<KnowledgeSearchService> knowledge;
    WorkflowEventPublisher events;
    EmailOutputService email;
    TelegramOutputService telegram;
    TeamsOutputService teams;
    WhatsAppOutputService whatsapp;
    GoogleDriveOutputService drive;
    PdfOutputRenderer pdf;
    ToolCallLogRepository toolLogs;
    DeviceCommandService devices;
    AgentToolProvider toolProvider;
    ObjectProvider<WorkflowService> workflowService;
    LlmResponseCacheService cache;
    QueryComplexityClassifier classifier;
    ObjectProvider<WorkflowInputRequestService> inputProvider;
    ObjectProvider<WorkflowVoiceMenuRequestService> voiceProvider;
    BulkEmailNodeHandler bulk;
    WorkflowNodeExecutor executor;

    @BeforeEach
    void setUp() {
        orchestrator = mock(OrchestratorService.class);
        chat = mock(AgentChatService.class);
        agentService = mock(AgentService.class);
        knowledge = mock(ObjectProvider.class);
        events = mock(WorkflowEventPublisher.class);
        email = mock(EmailOutputService.class);
        telegram = mock(TelegramOutputService.class);
        teams = mock(TeamsOutputService.class);
        whatsapp = mock(WhatsAppOutputService.class);
        drive = mock(GoogleDriveOutputService.class);
        pdf = mock(PdfOutputRenderer.class);
        toolLogs = mock(ToolCallLogRepository.class);
        devices = mock(DeviceCommandService.class);
        toolProvider = mock(AgentToolProvider.class);
        workflowService = mock(ObjectProvider.class);
        cache = mock(LlmResponseCacheService.class);
        classifier = mock(QueryComplexityClassifier.class);
        inputProvider = mock(ObjectProvider.class);
        voiceProvider = mock(ObjectProvider.class);
        bulk = mock(BulkEmailNodeHandler.class);
        executor = build(bulk);
    }

    @AfterEach
    void clear() {
        ApprovalReplayGrant.clear();
        ExpressionScopeContext.clear();
    }

    WorkflowNodeExecutor build(BulkEmailNodeHandler bulkHandler) {
        return NodeExecutorTestFactory.create(
                orchestrator,
                chat,
                agentService,
                knowledge,
                events,
                email,
                telegram,
                teams,
                whatsapp,
                drive,
                pdf,
                toolLogs,
                devices,
                toolProvider,
                new ObjectMapper(),
                workflowService,
                cache,
                classifier,
                mock(FreeTierChatClientService.class),
                mock(LlmGatewayService.class),
                mock(ProviderCredentialResolver.class),
                mock(TenantLimits.class),
                mock(SubagentRunRepository.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                inputProvider,
                voiceProvider,
                bulkHandler);
    }

    static WorkflowNodeDto node(String type, Map<String, Object> config) {
        return new WorkflowNodeDto("n1", type, Map.of(), Map.of("config", config, "label", "Node"));
    }

    NodeExecutionResult run(WorkflowNodeDto node, String input) {
        return executor.executeNode(WF.toString(), "wf", RUN, node, input, "tester");
    }
}
