package com.agenticai.core.workflow.portability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.workflow.OrchestratorService;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.WorkflowStatus;
import com.agenticai.core.workflow.WorkflowValidationException;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.portability.PortabilityDtos.ImportResponse;
import com.agenticai.core.workflow.portability.PortabilityDtos.PlaceholderView;
import com.agenticai.core.workflow.portability.PortabilityDtos.PreviewResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The service end to end for every format, with the collaborators mocked: the flag, the size cap,
 * hostile input, the mapping step, validation, and above all that creating an import only ever
 * creates a DRAFT and never activates, schedules or triggers anything.
 */
class WorkflowPortabilityServiceTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private WorkflowService workflowService;
    private OrchestratorService orchestratorService;
    private AgentService agentService;
    private WorkflowPortabilityService service;

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowService.class);
        orchestratorService = mock(OrchestratorService.class);
        agentService = mock(AgentService.class);
        service =
                new WorkflowPortabilityService(
                        workflowService, orchestratorService, agentService, true, 2L * 1024 * 1024);
    }

    private static String n8nFile() {
        return """
{'name': 'Hook to agent', 'nodes': [
  {'parameters': {'path': 'in'}, 'name': 'Webhook', 'type': 'n8n-nodes-base.webhook', 'position': [0, 0]},
  {'parameters': {'text': 'hi'}, 'name': 'AI Agent', 'type': '@n8n/n8n-nodes-langchain.agent',
   'position': [200, 0], 'credentials': {'openAiApi': {'id': '5', 'name': 'My OpenAI'}}}],
 'connections': {'Webhook': {'main': [[{'node': 'AI Agent', 'type': 'main', 'index': 0}]]}}}
"""
                .replace('\'', '"');
    }

    private static String difyFile() {
        return """
        app: {mode: workflow, name: Tiny}
        kind: app
        workflow:
          graph:
            edges:
            - {id: a, source: s, target: e, sourceHandle: source}
            nodes:
            - {id: s, data: {type: start, title: Start}}
            - {id: e, data: {type: end, title: End, outputs: []}}
        """;
    }

    private static String envelope(String source, String payload) throws Exception {
        return MAPPER.writeValueAsString(Map.of("source", source, "payload", payload));
    }

    private static WorkflowDefinition savedDraft(UUID id) {
        WorkflowDefinition definition =
                new WorkflowDefinition(
                        "n", "d", "{\"nodes\":[],\"edges\":[]}", WorkflowStatus.DRAFT);
        ReflectionTestUtils.setField(definition, "id", id);
        return definition;
    }

    // ---- the flag and the limits
    // ---------------------------------------------------------------------------

    @Test
    void everyEntryPointAnswers404WhenTheFeatureIsOff() throws Exception {
        WorkflowPortabilityService off =
                new WorkflowPortabilityService(
                        workflowService,
                        orchestratorService,
                        agentService,
                        false,
                        2L * 1024 * 1024);
        assertThat(statusOf(() -> off.preview(envelope("auto", n8nFile())))).isEqualTo(404);
        assertThat(statusOf(() -> off.importWorkflow(envelope("auto", n8nFile())))).isEqualTo(404);
        assertThat(statusOf(() -> off.export(UUID.randomUUID()))).isEqualTo(404);
        verifyNoMoreInteractions(workflowService);
    }

    @Test
    void anOversizedPayloadAnswers413BeforeAnyParsing() throws Exception {
        WorkflowPortabilityService small =
                new WorkflowPortabilityService(
                        workflowService, orchestratorService, agentService, true, 1024);
        String big = "{\"name\":\"" + "x".repeat(5000) + "\"}";
        assertThat(statusOf(() -> small.preview(envelope("auto", big)))).isEqualTo(413);
    }

    @Test
    void readBoundedStopsReadingPastTheLimit() {
        WorkflowPortabilityService small =
                new WorkflowPortabilityService(
                        workflowService, orchestratorService, agentService, true, 1024);
        byte[] huge = new byte[1024 * 2 + WorkflowPortabilityService.ENVELOPE_SLACK_BYTES + 10];
        assertThatThrownBy(() -> small.readBounded(new java.io.ByteArrayInputStream(huge)))
                .isInstanceOf(ResponseStatusException.class);
    }

    private static int statusOf(org.junit.jupiter.api.function.ThrowingSupplier<Object> call) {
        try {
            call.get();
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        } catch (Throwable t) {
            throw new AssertionError("unexpected " + t, t);
        }
        throw new AssertionError("expected a ResponseStatusException");
    }

    // ---- hostile input
    // -------------------------------------------------------------------------------------

    @Test
    void hostileYamlIsRefusedWith400Material() throws Exception {
        String hostile =
                "kind: app\napp: {mode: workflow}\nx: !!java.lang.ProcessBuilder [[\"calc\"]]\n";
        assertThatThrownBy(() -> service.preview(envelope("auto", hostile)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("custom type tag");
    }

    @Test
    void anAliasBombIsRefused() throws Exception {
        String lol = "[\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\"]";
        String bomb =
                "kind: app\napp: {mode: workflow}\na: &a "
                        + lol
                        + "\n"
                        + "b: &b [*a,*a,*a,*a,*a,*a,*a,*a,*a]\n"
                        + "c: &c [*b,*b,*b,*b,*b,*b,*b,*b,*b]\n"
                        + "d: &d [*c,*c,*c,*c,*c,*c,*c,*c,*c]\n"
                        + "e: &e [*d,*d,*d,*d,*d,*d,*d,*d,*d]\n";
        assertThatThrownBy(() -> service.preview(envelope("auto", bomb)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anchors");
    }

    @Test
    void badEnvelopesAndSourcesAreRefused() throws Exception {
        assertThatThrownBy(() -> service.preview("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.preview("[1]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.preview("{not json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.preview("{\"source\":\"auto\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.preview(envelope("zapier", n8nFile())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.preview(envelope("dify", n8nFile())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("looks like a n8n");
        assertThatThrownBy(() -> service.preview(envelope("auto", "{\"hello\": 1}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("which format");
    }

    // ---- preview
    // -------------------------------------------------------------------------------------------

    @Test
    void previewOfAnN8nExportCreatesNothingAndListsWhatToMap() throws Exception {
        PreviewResponse preview = service.preview(envelope("auto", n8nFile()));

        assertThat(preview.detectedSource()).isEqualTo("n8n");
        assertThat(preview.workflow().name()).isEqualTo("Hook to agent");
        assertThat(preview.workflow().graph().nodes()).hasSize(2);
        PlaceholderView agent =
                preview.placeholders().stream()
                        .filter(p -> p.key().equals("agent_1"))
                        .findFirst()
                        .orElseThrow();
        assertThat(agent.mappable()).isTrue();
        assertThat(agent.resolved()).isFalse();
        assertThat(preview.placeholders())
                .anyMatch(p -> p.kind().equals("credential") && p.label().contains("My OpenAI"));
        // The unmapped agent field was removed, not left holding a foreign id or a token.
        WorkflowNodeDto agentNode = preview.workflow().graph().nodes().get(1);
        assertThat(agentNode.config()).doesNotContainKey("agentId");
        assertThat(agentNode.data().get("importNotes").toString()).contains("agent_1");
        assertThat(preview.fidelity().sourceNodes()).isEqualTo(2);
        assertThat(preview.report().needsManual()).isNotEmpty();
        verifyNoMoreInteractions(workflowService);
    }

    @Test
    void previewValidatesAgainstAStandInSoOnlyStructuralIssuesAreListed() throws Exception {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Set<UUID>> assumed = ArgumentCaptor.forClass((Class) Set.class);
        service.preview(envelope("n8n", n8nFile()));
        verify(orchestratorService)
                .validate(any(WorkflowGraphDto.class), isNull(), assumed.capture());
        assertThat(assumed.getValue()).hasSize(1);
    }

    @Test
    void validationIssuesFromTheOrchestratorAreSurfaced() throws Exception {
        doThrow(
                        new WorkflowValidationException(
                                List.of("The graph contains a cycle reachable from node 'n1'.")))
                .when(orchestratorService)
                .validate(any(WorkflowGraphDto.class), isNull(), anySet());
        PreviewResponse preview = service.preview(envelope("auto", n8nFile()));
        assertThat(preview.validationIssues())
                .containsExactly("The graph contains a cycle reachable from node 'n1'.");
    }

    @Test
    void previewOfADifyYamlFileReadsItThroughTheSafeLoader() throws Exception {
        PreviewResponse preview = service.preview(envelope("auto", difyFile()));
        assertThat(preview.detectedSource()).isEqualTo("dify");
        assertThat(preview.workflow().name()).isEqualTo("Tiny");
        // start -> end, plus the internal end -> output edge the End node expands into.
        assertThat(preview.workflow().graph().edges()).hasSize(2);
    }

    @Test
    void aJsonObjectPayloadIsAcceptedAsWellAsAString() throws Exception {
        JsonNode object = MAPPER.readTree(n8nFile());
        String body = MAPPER.writeValueAsString(Map.of("source", "auto", "payload", object));
        assertThat(service.preview(body).detectedSource()).isEqualTo("n8n");
    }

    // ---- mappings
    // ------------------------------------------------------------------------------------------

    @Test
    void aMappedAgentIsAppliedAndMarkedResolved() throws Exception {
        UUID agentId = UUID.randomUUID();
        String body =
                MAPPER.writeValueAsString(
                        Map.of(
                                "source",
                                "auto",
                                "payload",
                                n8nFile(),
                                "mappings",
                                Map.of("agent_1", agentId.toString())));
        PreviewResponse preview = service.preview(body);

        verify(agentService).requireExists(agentId);
        WorkflowNodeDto agentNode = preview.workflow().graph().nodes().get(1);
        assertThat(agentNode.config()).containsEntry("agentId", agentId.toString());
        PlaceholderView agent =
                preview.placeholders().stream()
                        .filter(p -> p.key().equals("agent_1"))
                        .findFirst()
                        .orElseThrow();
        assertThat(agent.resolved()).isTrue();
        assertThat(agent.mappedTo()).isEqualTo(agentId.toString());
    }

    @Test
    void badMappingsAreRefused() throws Exception {
        UUID missing = UUID.randomUUID();
        doThrow(new AgentNotFoundException(missing)).when(agentService).requireExists(missing);
        assertThatThrownBy(
                        () ->
                                service.preview(
                                        MAPPER.writeValueAsString(
                                                Map.of(
                                                        "source",
                                                        "auto",
                                                        "payload",
                                                        n8nFile(),
                                                        "mappings",
                                                        Map.of("agent_1", missing.toString())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");
        assertThatThrownBy(
                        () ->
                                service.preview(
                                        MAPPER.writeValueAsString(
                                                Map.of(
                                                        "source",
                                                        "auto",
                                                        "payload",
                                                        n8nFile(),
                                                        "mappings",
                                                        Map.of("agent_1", "not-a-uuid")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid id");
        assertThatThrownBy(
                        () ->
                                service.preview(
                                        MAPPER.writeValueAsString(
                                                Map.of(
                                                        "source",
                                                        "auto",
                                                        "payload",
                                                        n8nFile(),
                                                        "mappings",
                                                        Map.of(
                                                                "nope_9",
                                                                UUID.randomUUID().toString())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a placeholder");
        // Credentials are never mappable: there is nothing to point them at.
        assertThatThrownBy(
                        () ->
                                service.preview(
                                        MAPPER.writeValueAsString(
                                                Map.of(
                                                        "source",
                                                        "auto",
                                                        "payload",
                                                        n8nFile(),
                                                        "mappings",
                                                        Map.of(
                                                                "credential_1",
                                                                UUID.randomUUID().toString())))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- create
    // --------------------------------------------------------------------------------------------

    @Test
    void importCreatesADraftThroughTheNormalCreatePathAndNothingElse() throws Exception {
        UUID id = UUID.randomUUID();
        when(workflowService.create(anyString(), any(), any(WorkflowGraphDto.class)))
                .thenReturn(savedDraft(id));

        ImportResponse response = service.importWorkflow(envelope("auto", n8nFile()));

        assertThat(response.workflowId()).isEqualTo(id);
        assertThat(response.status()).isEqualTo("DRAFT");
        assertThat(response.report().converted()).anyMatch(c -> c.what().contains("Draft"));
        ArgumentCaptor<WorkflowGraphDto> graph = ArgumentCaptor.forClass(WorkflowGraphDto.class);
        verify(workflowService).create(eq("Hook to agent"), any(), graph.capture());
        assertThat(graph.getValue().nodes()).hasSize(2);
        // No activation, no update, no schedule, no webhook, no run: the only call is create.
        verifyNoMoreInteractions(workflowService);
    }

    @Test
    void executionLimitsFromADslFileAreAppliedAfterCreate() throws Exception {
        UUID id = UUID.randomUUID();
        WorkflowDefinition draft = savedDraft(id);
        when(workflowService.create(anyString(), any(), any(WorkflowGraphDto.class)))
                .thenReturn(draft);
        when(workflowService.requireViewerAccess(id)).thenReturn(draft);

        String graph =
                "{\"nodes\":[{\"id\":\"t\",\"type\":\"trigger\",\"data\":{\"label\":\"T\",\"config\":{}}}],\"edges\":[]}";
        String file =
                WorkflowDslExporter.toJson(
                        MAPPER,
                        WorkflowDslExporter.export(
                                MAPPER,
                                "Limits",
                                null,
                                graph,
                                new WorkflowDslExporter.Settings(40, 90, 2, null, null, false)));
        service.importWorkflow(envelope("vegaduta", file));

        verify(workflowService).setExecutionLimits(id, 40, 90, 2);
        verify(workflowService, never()).update(any(), any(), any(), any(), any());
    }

    // ---- export
    // --------------------------------------------------------------------------------------------

    @Test
    void exportReturnsADeterministicFileWithoutSecretsOrTenantIds() throws Exception {
        UUID id = UUID.randomUUID();
        String agentId = UUID.randomUUID().toString();
        String graph =
                "{\"nodes\":[{\"id\":\"a\",\"type\":\"agent\",\"data\":{\"label\":\"A\",\"config\":{\"agentId\":\""
                        + agentId
                        + "\"}}},{\"id\":\"h\",\"type\":\"http\",\"data\":{\"label\":\"H\",\"config\":{\"url\":\"https://x.example/?token=abcdefghijklmnop123456\"}}}],\"edges\":[]}";
        WorkflowDefinition definition =
                new WorkflowDefinition("My flow", "d", graph, WorkflowStatus.DRAFT);
        when(workflowService.requireViewerAccess(id)).thenReturn(definition);

        WorkflowPortabilityService.ExportedFile file = service.export(id);
        WorkflowPortabilityService.ExportedFile again = service.export(id);

        assertThat(file.fileName()).isEqualTo("my-flow.vegaduta-workflow.json");
        assertThat(file.json()).isEqualTo(again.json());
        assertThat(file.json()).doesNotContain(agentId).doesNotContain("abcdefghijklmnop123456");
        JsonNode doc = MAPPER.readTree(file.json().getBytes(StandardCharsets.UTF_8));
        assertThat(doc.get("format").asString()).isEqualTo("vegaduta.workflow");
        assertThat(doc.get("placeholders").findValuesAsString("key")).contains("agent_1");
        assertThat(doc.has("exportedAt")).isFalse();
    }

    @Test
    void exportedFilesImportBackThroughTheSameService() throws Exception {
        UUID id = UUID.randomUUID();
        String graph =
                "{\"nodes\":[{\"id\":\"t\",\"type\":\"trigger\",\"data\":{\"label\":\"T\",\"config\":{}}}],\"edges\":[]}";
        when(workflowService.requireViewerAccess(id))
                .thenReturn(
                        new WorkflowDefinition("Round trip", null, graph, WorkflowStatus.DRAFT));
        String file = service.export(id).json();

        PreviewResponse preview = service.preview(envelope("auto", file));
        assertThat(preview.detectedSource()).isEqualTo("vegaduta");
        assertThat(preview.workflow().name()).isEqualTo("Round trip");
        assertThat(preview.fidelity().level()).isEqualTo("high");
        assertThat(preview.report().warnings()).noneMatch(w -> w.contains("checksum"));
        verify(workflowService, never()).create(anyString(), any(), any());
    }
}
