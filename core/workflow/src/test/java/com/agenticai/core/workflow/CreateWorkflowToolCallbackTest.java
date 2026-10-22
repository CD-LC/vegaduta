package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.workflow.dto.GenerateGraphResponse;
import com.agenticai.core.workflow.dto.ValidationResult;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** {@code create_workflow}: generate, save as a draft, report; never activate or run. */
class CreateWorkflowToolCallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkflowCrudService crud = mock(WorkflowCrudService.class);
    private final WorkflowGraphGeneratorService generator =
            mock(WorkflowGraphGeneratorService.class);
    private final UUID workflowId = UUID.randomUUID();
    private final WorkflowGraphDto graph =
            new WorkflowGraphDto(
                    List.of(
                            new WorkflowNodeDto(
                                    "n1", "trigger", Map.of(), Map.of("label", "Every Monday")),
                            new WorkflowNodeDto(
                                    "n2", "agent", Map.of(), Map.of("label", "Summarise sales"))),
                    List.of(new WorkflowEdgeDto("e1", "n1", "n2", null, null)));
    private CreateWorkflowToolCallback tool;

    @BeforeEach
    void setUp() {
        tool = new CreateWorkflowToolCallback(crud, generator);
        WorkflowDefinition saved = mock(WorkflowDefinition.class);
        when(saved.getId()).thenReturn(workflowId);
        when(saved.getName()).thenReturn("Weekly digest");
        when(crud.create(anyString(), anyString(), any())).thenReturn(saved);
        when(crud.validate(workflowId)).thenReturn(ValidationResult.ok());
        when(generator.generate(anyString(), any(ReasoningDepth.class), isNull()))
                .thenReturn(new GenerateGraphResponse(graph, List.of("picked the first agent")));
    }

    @Test
    void generatesAndSavesADraftThenReportsNodesPathAndNotes() throws Exception {
        JsonNode out =
                MAPPER.readTree(
                        tool.call(
                                "{\"name\":\"Weekly digest\",\"instruction\":\"every Monday summarise sales\"}"));

        assertEquals(workflowId.toString(), out.get("workflowId").asText());
        assertEquals("DRAFT", out.get("status").asText());
        assertEquals("/workflows/" + workflowId, out.get("path").asText());
        assertEquals(2, out.get("nodes").size());
        assertEquals("agent", out.get("nodes").get(1).get("type").asText());
        assertEquals("Summarise sales", out.get("nodes").get(1).get("label").asText());
        assertEquals(1, out.get("edgeCount").asInt());
        assertEquals("picked the first agent", out.get("notes").get(0).asText());
        assertEquals(0, out.get("warnings").size());
        assertFalse(out.has("error"));
        verify(crud).create(eq("Weekly digest"), eq("every Monday summarise sales"), eq(graph));
        // a draft only: nothing here may touch the graph again, activate or run it
        verify(crud, never()).update(any(), any(), any(), any(), any());
    }

    @Test
    void validationProblemsOfTheSavedDraftComeBackAsWarnings() throws Exception {
        when(crud.validate(workflowId))
                .thenReturn(
                        ValidationResult.failed(List.of("Agent node n2 has no agent selected")));

        JsonNode out = MAPPER.readTree(tool.call("{\"name\":\"W\",\"instruction\":\"do it\"}"));

        assertEquals(1, out.get("warnings").size());
        assertTrue(out.get("warnings").get(0).asText().contains("no agent selected"));
        assertEquals("DRAFT", out.get("status").asText());
    }

    @Test
    void aGeneratorFailureIsAnErrorEnvelopeAndSavesNothing() throws Exception {
        when(generator.generate(anyString(), any(ReasoningDepth.class), isNull()))
                .thenThrow(new IllegalStateException("model unavailable"));

        JsonNode out = MAPPER.readTree(tool.call("{\"name\":\"W\",\"instruction\":\"do it\"}"));

        assertTrue(out.get("error").asText().contains("model unavailable"));
        verify(crud, never()).create(any(), any(), any());
    }

    @Test
    void anInvalidGeneratedGraphIsReportedWithItsErrors() throws Exception {
        when(generator.generate(anyString(), any(ReasoningDepth.class), isNull()))
                .thenThrow(new WorkflowValidationException(List.of("no trigger node")));

        JsonNode out = MAPPER.readTree(tool.call("{\"name\":\"W\",\"instruction\":\"do it\"}"));

        assertTrue(out.get("error").asText().contains("no trigger node"));
        verify(crud, never()).create(any(), any(), any());
    }

    @Test
    void theTenantWorkflowLimitIsRelayedAsTheError() throws Exception {
        when(crud.create(anyString(), anyString(), any()))
                .thenThrow(
                        new TenantLimitExceededException("The FREE plan allows up to 3 workflows"));

        JsonNode out = MAPPER.readTree(tool.call("{\"name\":\"W\",\"instruction\":\"do it\"}"));

        assertTrue(out.get("error").asText().contains("allows up to 3 workflows"));
    }

    @Test
    void nameAndInstructionAreRequired() throws Exception {
        for (String input :
                List.of(
                        "{\"instruction\":\"do it\"}",
                        "{\"name\":\"  \",\"instruction\":\"do it\"}",
                        "{\"name\":\"W\"}",
                        "{}",
                        "not json")) {
            JsonNode out = MAPPER.readTree(tool.call(input));
            assertTrue(out.has("error"), input);
        }
        verify(generator, never()).generate(anyString(), any(ReasoningDepth.class), any());
    }

    @Test
    void theToolIsNamedAndRequiresBothFields() {
        var definition = tool.getToolDefinition();
        assertEquals("create_workflow", definition.name());
        assertTrue(definition.inputSchema().contains("\"required\": [\"name\", \"instruction\"]"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void theFactoryBuildsTheToolOnlyWhenEnabledAndTheServicesExist() {
        ObjectProvider<WorkflowCrudService> crudProvider = mock(ObjectProvider.class);
        ObjectProvider<WorkflowGraphGeneratorService> generatorProvider =
                mock(ObjectProvider.class);
        when(crudProvider.getIfAvailable()).thenReturn(crud);
        when(generatorProvider.getIfAvailable()).thenReturn(generator);

        assertTrue(
                new CreateWorkflowToolCallbackFactory(crudProvider, generatorProvider, true)
                                .createIfEnabled()
                        instanceof CreateWorkflowToolCallback);
        assertEquals(
                null,
                new CreateWorkflowToolCallbackFactory(crudProvider, generatorProvider, false)
                        .createIfEnabled());
        when(generatorProvider.getIfAvailable()).thenReturn(null);
        assertEquals(
                null,
                new CreateWorkflowToolCallbackFactory(crudProvider, generatorProvider, true)
                        .createIfEnabled());
    }
}
