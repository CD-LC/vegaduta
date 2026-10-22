package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.tools.web.HttpRequestExecutor;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.devices.DeviceCommandService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.output.EmailOutputService;
import com.agenticai.core.workflow.output.GoogleDriveOutputService;
import com.agenticai.core.workflow.output.PdfOutputRenderer;
import com.agenticai.core.workflow.output.TeamsOutputService;
import com.agenticai.core.workflow.output.TelegramOutputService;
import com.agenticai.core.workflow.output.WhatsAppOutputService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * The four AI node types through {@link WorkflowNodeExecutor#executeNode}: dispatch, expression
 * resolution of their data fields, and the branch hand-off the engine's edge routing reads.
 */
class WorkflowNodeExecutorAiNodesTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    @SuppressWarnings("unchecked")
    private static WorkflowNodeExecutor newExecutor() {
        return NodeExecutorTestFactory.create(
                mock(OrchestratorService.class),
                mock(AgentChatService.class),
                mock(AgentService.class),
                mock(ObjectProvider.class),
                mock(WorkflowEventPublisher.class),
                mock(EmailOutputService.class),
                mock(TelegramOutputService.class),
                mock(TeamsOutputService.class),
                mock(WhatsAppOutputService.class),
                mock(GoogleDriveOutputService.class),
                mock(PdfOutputRenderer.class),
                mock(ToolCallLogRepository.class),
                mock(DeviceCommandService.class),
                mock(AgentToolProvider.class),
                MAPPER,
                mock(ObjectProvider.class),
                mock(LlmResponseCacheService.class),
                mock(QueryComplexityClassifier.class),
                mock(FreeTierChatClientService.class),
                mock(LlmGatewayService.class),
                mock(ProviderCredentialResolver.class),
                mock(TenantLimits.class),
                mock(SubagentRunRepository.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class));
    }

    @SuppressWarnings("unchecked")
    private static WorkflowNodeExecutor executorWith(WorkflowAiModel model) {
        ObjectProvider<WorkflowAiModel> modelProvider = mock(ObjectProvider.class);
        when(modelProvider.getIfAvailable()).thenReturn(model);
        ObjectProvider<SidecarClient> sidecar = mock(ObjectProvider.class);
        ObjectProvider<FileStorageService> storage = mock(ObjectProvider.class);
        ObjectProvider<HttpRequestExecutor> http = mock(ObjectProvider.class);
        WorkflowAiNodeHandler handler =
                new WorkflowAiNodeHandler(
                        modelProvider,
                        sidecar,
                        storage,
                        http,
                        MAPPER,
                        true,
                        12_000,
                        0.5,
                        1_048_576,
                        60_000,
                        50,
                        2_000);
        WorkflowNodeExecutor executor = newExecutor();
        ((AiNodeAdapter) executor.handlerFor("question_classifier")).setHandler(handler);
        return executor;
    }

    private static WorkflowNodeDto node(String type, Map<String, Object> config) {
        return new WorkflowNodeDto("n1", type, Map.of(), Map.of("label", "Node", "config", config));
    }

    @Test
    void classifierResolvesItsTextFieldAndReturnsTheBranchTheEngineRoutesOn() {
        List<String> prompts = new ArrayList<>();
        WorkflowAiModel model =
                request -> {
                    prompts.add(request.user());
                    return "{\"class\":\"billing\",\"confidence\":0.9}";
                };
        Map<String, Object> config = new HashMap<>();
        config.put(
                "classes",
                List.of(
                        Map.of("handle", "billing", "label", "Billing", "description", "money"),
                        Map.of("handle", "support", "label", "Support", "description", "bugs")));
        config.put("text", "{{message}}");
        NodeExecutionResult result =
                executorWith(model)
                        .executeNode(
                                "wf-1",
                                "Wf",
                                "run-1",
                                node("question_classifier", config),
                                "{\"message\":\"my invoice is wrong\"}",
                                "system:test");
        assertEquals("OK", result.status());
        assertEquals("billing", result.branch());
        assertEquals(
                "{\"message\":\"my invoice is wrong\"}",
                result.output(),
                "the input passes through for downstream nodes");
        assertTrue(prompts.get(0).contains("my invoice is wrong"), prompts.get(0));
        assertFalse(
                prompts.get(0).contains("{{message}}"),
                "the template was resolved before the handler ran");
    }

    @Test
    void aggregatorResolvesItsSourcesAndPicksTheFirstNonEmpty() {
        Map<String, Object> config = new HashMap<>();
        config.put("sources", List.of("{{missingField}}", "{{empty}}", "{{b}}", "{{c}}"));
        NodeExecutionResult result =
                executorWith(request -> "")
                        .executeNode(
                                "wf-1",
                                "Wf",
                                "run-1",
                                node("variable_aggregator", config),
                                "{\"empty\":\"\",\"b\":\"hello\",\"c\":\"later\"}",
                                "system:test");
        assertEquals("OK", result.status());
        assertEquals("hello", result.output());
    }

    @Test
    void extractorRunsThroughTheExecutor() throws Exception {
        WorkflowAiModel model = request -> "{\"city\":\"Pune\"}";
        Map<String, Object> config = new HashMap<>();
        config.put(
                "parameters", List.of(Map.of("name", "city", "type", "string", "required", true)));
        NodeExecutionResult result =
                executorWith(model)
                        .executeNode(
                                "wf-1",
                                "Wf",
                                "run-1",
                                node("parameter_extractor", config),
                                "I live in Pune",
                                "system:test");
        assertEquals("Pune", MAPPER.readTree(result.output()).get("city").asString());
    }

    @Test
    void documentExtractorRunsThroughTheExecutorOnInlineText() {
        Map<String, Object> config = new HashMap<>();
        config.put("format", "txt");
        NodeExecutionResult result =
                executorWith(request -> "")
                        .executeNode(
                                "wf-1",
                                "Wf",
                                "run-1",
                                node("document_extractor", config),
                                "just some text",
                                "system:test");
        assertEquals("OK", result.status());
        assertEquals("just some text", result.output());
    }

    @Test
    void withoutAHandlerTheNodesReportThemselvesUnavailableInsteadOfPassingSilently() {
        for (String type :
                List.of(
                        "question_classifier",
                        "parameter_extractor",
                        "variable_aggregator",
                        "document_extractor")) {
            NodeExecutionResult result =
                    newExecutor()
                            .executeNode(
                                    "wf-1",
                                    "Wf",
                                    "run-1",
                                    node(type, Map.of()),
                                    "in",
                                    "system:test");
            assertEquals("SKIPPED", result.status(), type);
            assertFalse(result.summary().contains("not yet executable"), type);
        }
    }

    @Test
    void aRoutingClassifierIsNeverServedFromAPinBecauseAPinCarriesNoRoutingDecision() {
        assertTrue(WorkflowNodeExecutor.PIN_NOT_HONOURED_TYPES.contains("question_classifier"));
        assertTrue(WorkflowNodeExecutor.PIN_NOT_HONOURED_TYPES.contains("condition"));
        assertFalse(WorkflowNodeExecutor.PIN_NOT_HONOURED_TYPES.contains("variable_aggregator"));
    }

    @Test
    void theNewTypesAreRegisteredWithExpressionFieldsAndTheEnum() {
        assertEquals(List.of("text"), ExpressionFields.paths("question_classifier"));
        assertEquals(List.of("text"), ExpressionFields.paths("parameter_extractor"));
        assertEquals(List.of("sources[]"), ExpressionFields.paths("variable_aggregator"));
        assertEquals(List.of("source"), ExpressionFields.paths("document_extractor"));
        assertEquals(
                WorkflowNodeType.QUESTION_CLASSIFIER,
                WorkflowNodeType.fromWire("question_classifier"));
        assertEquals(
                WorkflowNodeType.PARAMETER_EXTRACTOR,
                WorkflowNodeType.fromWire("parameter_extractor"));
        assertEquals(
                WorkflowNodeType.VARIABLE_AGGREGATOR,
                WorkflowNodeType.fromWire("variable_aggregator"));
        assertEquals(
                WorkflowNodeType.DOCUMENT_EXTRACTOR,
                WorkflowNodeType.fromWire("document_extractor"));
    }
}
