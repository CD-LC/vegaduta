package com.agenticai.core.workflow;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.tools.web.HttpRequestExecutor;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.devices.DeviceCommandService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.knowledge.KnowledgeSearchService;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.workflow.humaninput.WorkflowInputRequestService;
import com.agenticai.core.workflow.output.EmailOutputService;
import com.agenticai.core.workflow.output.GoogleDriveOutputService;
import com.agenticai.core.workflow.output.PdfOutputRenderer;
import com.agenticai.core.workflow.output.TeamsOutputService;
import com.agenticai.core.workflow.output.TelegramOutputService;
import com.agenticai.core.workflow.output.WhatsAppOutputService;
import com.agenticai.core.workflow.voicemenu.WorkflowVoiceMenuRequestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Builds a {@link WorkflowNodeExecutor} from the flat collaborator list the tests have always used.
 * This is the single place that knows how the executor is assembled, so the executor's constructor
 * can change shape without touching every test.
 */
final class NodeExecutorTestFactory {

    private NodeExecutorTestFactory() {}

    static WorkflowNodeExecutor create(
            OrchestratorService orchestratorService,
            AgentChatService agentChatService,
            AgentService agentService,
            ObjectProvider<KnowledgeSearchService> knowledgeSearchService,
            WorkflowEventPublisher eventPublisher,
            EmailOutputService emailOutputService,
            TelegramOutputService telegramOutputService,
            TeamsOutputService teamsOutputService,
            WhatsAppOutputService whatsAppOutputService,
            GoogleDriveOutputService googleDriveOutputService,
            PdfOutputRenderer pdfOutputRenderer,
            ToolCallLogRepository toolCallLogRepository,
            DeviceCommandService deviceCommandService,
            AgentToolProvider agentToolProvider,
            ObjectMapper objectMapper,
            ObjectProvider<WorkflowService> workflowService,
            LlmResponseCacheService llmResponseCacheService,
            QueryComplexityClassifier queryComplexityClassifier,
            FreeTierChatClientService freeTierChatClientService,
            LlmGatewayService llmGatewayService,
            ProviderCredentialResolver credentialResolver,
            TenantLimits tenantLimits,
            SubagentRunRepository subagentRunRepository,
            ObjectProvider<HttpRequestExecutor> httpRequestExecutor,
            ObjectProvider<SidecarClient> sidecarClient,
            ObjectProvider<WorkflowInputRequestService> inputRequestService,
            ObjectProvider<WorkflowVoiceMenuRequestService> voiceMenuRequestService) {
        return create(
                orchestratorService,
                agentChatService,
                agentService,
                knowledgeSearchService,
                eventPublisher,
                emailOutputService,
                telegramOutputService,
                teamsOutputService,
                whatsAppOutputService,
                googleDriveOutputService,
                pdfOutputRenderer,
                toolCallLogRepository,
                deviceCommandService,
                agentToolProvider,
                objectMapper,
                workflowService,
                llmResponseCacheService,
                queryComplexityClassifier,
                freeTierChatClientService,
                llmGatewayService,
                credentialResolver,
                tenantLimits,
                subagentRunRepository,
                httpRequestExecutor,
                sidecarClient,
                inputRequestService,
                voiceMenuRequestService,
                null);
    }

    static WorkflowNodeExecutor create(
            OrchestratorService orchestratorService,
            AgentChatService agentChatService,
            AgentService agentService,
            ObjectProvider<KnowledgeSearchService> knowledgeSearchService,
            WorkflowEventPublisher eventPublisher,
            EmailOutputService emailOutputService,
            TelegramOutputService telegramOutputService,
            TeamsOutputService teamsOutputService,
            WhatsAppOutputService whatsAppOutputService,
            GoogleDriveOutputService googleDriveOutputService,
            PdfOutputRenderer pdfOutputRenderer,
            ToolCallLogRepository toolCallLogRepository,
            DeviceCommandService deviceCommandService,
            AgentToolProvider agentToolProvider,
            ObjectMapper objectMapper,
            ObjectProvider<WorkflowService> workflowService,
            LlmResponseCacheService llmResponseCacheService,
            QueryComplexityClassifier queryComplexityClassifier,
            FreeTierChatClientService freeTierChatClientService,
            LlmGatewayService llmGatewayService,
            ProviderCredentialResolver credentialResolver,
            TenantLimits tenantLimits,
            SubagentRunRepository subagentRunRepository,
            ObjectProvider<HttpRequestExecutor> httpRequestExecutor,
            ObjectProvider<SidecarClient> sidecarClient,
            ObjectProvider<WorkflowInputRequestService> inputRequestService,
            ObjectProvider<WorkflowVoiceMenuRequestService> voiceMenuRequestService,
            BulkEmailNodeHandler bulkEmailNodeHandler) {
        NodeConfigResolver configResolver = new NodeConfigResolver(objectMapper);
        NodeOutputMapper outputMapper = new NodeOutputMapper(objectMapper);
        EconomyModelCaller economy =
                new EconomyModelCaller(
                        freeTierChatClientService,
                        llmGatewayService,
                        credentialResolver,
                        tenantLimits);
        StructuredAgentRunner structured =
                new StructuredAgentRunner(
                        agentChatService, subagentRunRepository, configResolver, outputMapper);
        List<NodeHandler> handlers =
                List.of(
                        new TriggerNodeHandler(),
                        new WaitNodeHandler(),
                        new AgentNodeHandler(
                                agentService,
                                agentChatService,
                                llmResponseCacheService,
                                queryComplexityClassifier,
                                new ToolCallLogService(toolCallLogRepository),
                                economy,
                                structured),
                        new KnowledgeNodeHandler(knowledgeSearchService, "off"),
                        new ConditionNodeHandler(),
                        new SupervisorNodeHandler(agentChatService, configResolver),
                        new ToolNodeHandler(agentToolProvider),
                        new CodeNodeHandler(objectMapper),
                        new HumanInputNodeHandler(inputRequestService),
                        new VoiceMenuNodeHandler(voiceMenuRequestService, objectMapper),
                        new DeviceControlNodeHandler(deviceCommandService),
                        new WorkflowTriggerNodeHandler(workflowService),
                        new OutputNodeHandler(
                                eventPublisher,
                                emailOutputService,
                                telegramOutputService,
                                teamsOutputService,
                                whatsAppOutputService,
                                googleDriveOutputService,
                                pdfOutputRenderer),
                        new BulkEmailNodeAdapter(bulkEmailNodeHandler),
                        new HttpMediaNodeAdapter(httpRequestExecutor, sidecarClient, objectMapper),
                        new DataNodeAdapter(objectMapper),
                        new CrmLeadNodeAdapter(),
                        new DataTableNodeAdapter(),
                        new AiNodeAdapter());
        return new WorkflowNodeExecutor(orchestratorService, objectMapper, handlers);
    }
}
