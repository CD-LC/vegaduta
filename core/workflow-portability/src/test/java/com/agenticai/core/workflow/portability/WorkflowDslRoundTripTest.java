package com.agenticai.core.workflow.portability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.importer.AgentImportDtos.ConversionReport;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The VegaDuta workflow DSL v1: what an export contains and leaves out, that it is deterministic,
 * and that the importer reads it back. Golden fixture: a small realistic workflow with one of each
 * thing that must not leave the tenant (an agent id, a knowledge collection, an email address, a
 * typed-in API key, a vault reference).
 */
class WorkflowDslRoundTripTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final String AGENT_ID = "3f2b8c1e-0a4d-4c55-9a1e-111122223333";
    private static final String KB_ID = "aaaaaaaa-1111-4222-8333-444444444444";
    private static final String API_KEY = "sk_live_ABCDEFGHIJKLMNOPQRSTUV";
    private static final String BEARER = "abcdefghijklmnopqrstuvwxyz0123456789";

    /** JSON with single quotes standing in for double quotes, so the fixture stays readable. */
    private static String graphJson() {
        return """
            {'nodes': [
              {'id': 't1', 'type': 'trigger', 'position': {'x': 0, 'y': 0},
               'data': {'label': 'Start', 'config': {'channel': 'WEBHOOK'}}},
              {'id': 'a1', 'type': 'agent', 'position': {'x': 200, 'y': 0},
               'data': {'label': 'Researcher', 'config': {'agentId': '%s'}}},
              {'id': 'k1', 'type': 'knowledge', 'position': {'x': 200, 'y': 120},
               'data': {'label': 'Docs', 'config': {'collectionId': '%s', 'topK': 5}}},
              {'id': 'h1', 'type': 'http', 'position': {'x': 400, 'y': 0},
               'data': {'label': 'Call API', 'config': {'method': 'POST',
                 'url': 'https://api.example.com/v1/orders?api_key=%s',
                 'headers': [{'name': 'Authorization', 'value': 'Bearer %s'},
                             {'name': 'X-Org', 'value': '{{secret.ORG_KEY}}'}],
                 'bodyType': 'json', 'body': 'hello'}}},
              {'id': 'o1', 'type': 'output', 'position': {'x': 600, 'y': 0},
               'data': {'label': 'Mail it', 'config': {'channel': 'email', 'emailTo': 'boss@example.com'}}},
              {'id': 's1', 'type': 'sub_flow', 'position': {'x': 600, 'y': 120},
               'data': {'label': 'Each', 'config': {'body': {'nodes': [
                 {'id': 'b1', 'type': 'agent', 'position': {'x': 0, 'y': 0},
                  'data': {'label': 'Inner', 'config': {'agentId': '%s'}}}], 'edges': []}}}}
            ], 'edges': [
              {'id': 'e1', 'source': 't1', 'target': 'a1'},
              {'id': 'e2', 'source': 'a1', 'target': 'h1'},
              {'id': 'e3', 'source': 'h1', 'target': 'o1', 'sourceHandle': null}
            ]}
            """
                .formatted(AGENT_ID, KB_ID, API_KEY, BEARER, AGENT_ID)
                .replace('\'', '"');
    }

    private static WorkflowDslExporter.Settings settings() {
        return new WorkflowDslExporter.Settings(50, 120, 1, "BALANCED", "SIMPLE", true);
    }

    private static ObjectNode export() {
        return WorkflowDslExporter.export(
                MAPPER, "Order flow", "Looks things up", graphJson(), settings());
    }

    @Test
    void exportHasTheDocumentedShapeAndNoExportTimestamp() {
        ObjectNode doc = export();
        assertThat(doc.get("format").asString()).isEqualTo("vegaduta.workflow");
        assertThat(doc.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(doc.get("name").asString()).isEqualTo("Order flow");
        assertThat(doc.get("nodes")).hasSize(6);
        assertThat(doc.get("edges")).hasSize(3);
        assertThat(doc.get("config").get("maxStepsPerRun").asInt()).isEqualTo(50);
        assertThat(doc.has("exportedAt")).isFalse();
        assertThat(doc.get("checksum").asString()).startsWith("sha256:");
        assertThat(WorkflowDslV1.checksum(doc)).isEqualTo(doc.get("checksum").asString());
    }

    @Test
    void exportIsByteForByteDeterministic() {
        String first = WorkflowDslExporter.toJson(MAPPER, export());
        String second = WorkflowDslExporter.toJson(MAPPER, export());
        assertThat(first).isEqualTo(second);
        assertThat(first).doesNotContain("\r");
        assertThat(first).endsWith("}\n");
    }

    @Test
    void exportKeepsTenantIdsDestinationsAndTypedInCredentialsOutOfTheFile() {
        String file = WorkflowDslExporter.toJson(MAPPER, export());
        assertThat(file)
                .doesNotContain(AGENT_ID)
                .doesNotContain(KB_ID)
                .doesNotContain("boss@example.com")
                .doesNotContain(API_KEY)
                .doesNotContain(BEARER);
        assertThat(file).contains("[[CREDENTIAL_");
    }

    @Test
    void exportReplacesReferencesWithNamedPlaceholdersAndListsThem() {
        ObjectNode doc = export();
        JsonNode nodes = doc.get("nodes");
        assertThat(nodes.get(1).get("data").get("config").get("agentId").asString())
                .isEqualTo("@placeholder:agent_1");
        assertThat(nodes.get(2).get("data").get("config").get("collectionId").asString())
                .isEqualTo("@placeholder:kb_1");
        assertThat(nodes.get(4).get("data").get("config").get("emailTo").asString())
                .isEqualTo("@placeholder:destination_1");
        // The same foreign agent used twice (including inside a sub-flow body) is one placeholder.
        JsonNode inner = nodes.get(5).get("data").get("config").get("body").get("nodes").get(0);
        assertThat(inner.get("data").get("config").get("agentId").asString())
                .isEqualTo("@placeholder:agent_1");

        JsonNode placeholders = doc.get("placeholders");
        List<String> keys = placeholders.findValuesAsText("key");
        assertThat(keys).contains("agent_1", "kb_1", "destination_1", "secret:ORG_KEY");
        assertThat(keys).anyMatch(k -> k.startsWith("CREDENTIAL_"));
        for (JsonNode placeholder : placeholders) {
            if ("agent_1".equals(placeholder.get("key").asString())) {
                assertThat(placeholder.get("kind").asString()).isEqualTo("agent");
                assertThat(placeholder.get("usedBy").findValuesAsText("nodeId"))
                        .contains("a1", "b1");
            }
        }
    }

    @Test
    void exportKeepsVaultReferencesBecauseTheyAreReferencesNotValues() {
        String file = WorkflowDslExporter.toJson(MAPPER, export());
        assertThat(file).contains("{{secret.ORG_KEY}}");
    }

    @Test
    void exportOfACorruptGraphIsRefusedRatherThanSentUnscanned() {
        assertThatThrownBy(
                        () ->
                                WorkflowDslExporter.export(
                                        MAPPER, "x", null, "{not json", settings()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fileNamesAreSafe() {
        assertThat(WorkflowDslExporter.fileName("Order flow / v2!"))
                .isEqualTo("order-flow-v2.vegaduta-workflow.json");
        assertThat(WorkflowDslExporter.fileName("???"))
                .isEqualTo("workflow.vegaduta-workflow.json");
        assertThat(WorkflowDslExporter.fileName(null)).isEqualTo("workflow.vegaduta-workflow.json");
    }

    @Test
    void importReadsAnExportBackWithItsPlaceholders() throws Exception {
        String file = WorkflowDslExporter.toJson(MAPPER, export());
        PortableWorkflow wf = WorkflowDslImporter.map(MAPPER, MAPPER.readTree(file));

        assertThat(wf.name).isEqualTo("Order flow");
        assertThat(wf.nodes).hasSize(6);
        assertThat(wf.edges).hasSize(3);
        assertThat(wf.fidelity.exact).isEqualTo(6);
        assertThat(wf.settings)
                .containsEntry("maxStepsPerRun", 50)
                .containsEntry("maxRetryAttempts", 1);

        PlaceholderRegistry.Entry agent = wf.placeholders.get("agent_1");
        assertThat(agent).isNotNull();
        assertThat(agent.kind()).isEqualTo(PlaceholderRegistry.AGENT);
        assertThat(PlaceholderRegistry.isMappable(agent.kind())).isTrue();
        assertThat(agent.label()).isEqualTo("Researcher");
        assertThat(wf.placeholders.get("kb_1").kind())
                .isEqualTo(PlaceholderRegistry.KNOWLEDGE_BASE);
        assertThat(wf.placeholders.get("destination_1").kind())
                .isEqualTo(PlaceholderRegistry.DESTINATION);
        assertThat(wf.placeholders.get("secret:ORG_KEY").kind())
                .isEqualTo(PlaceholderRegistry.SECRET);

        ConversionReport report = wf.report.toReport();
        assertThat(report.warnings()).noneMatch(w -> w.contains("checksum"));
    }

    @Test
    void anEditedFileIsReportedNotRefused() {
        ObjectNode doc = export();
        doc.put("name", "Edited after export");
        PortableWorkflow wf = WorkflowDslImporter.map(MAPPER, doc);
        assertThat(wf.report.toReport().warnings())
                .anyMatch(w -> w.contains("checksum does not match"));
    }

    @Test
    void aFileWithoutAChecksumIsAcceptedWithANote() {
        ObjectNode doc = export();
        doc.remove("checksum");
        PortableWorkflow wf = WorkflowDslImporter.map(MAPPER, doc);
        assertThat(wf.report.toReport().warnings()).anyMatch(w -> w.contains("no checksum"));
    }

    @Test
    void aNewerSchemaVersionIsRefusedWithAClearMessage() {
        ObjectNode doc = export();
        doc.put("schemaVersion", 2);
        assertThatThrownBy(() -> WorkflowDslImporter.map(MAPPER, doc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newer");
    }

    @Test
    void malformedFilesAreRefused() {
        ObjectNode noNodes = export();
        noNodes.remove("nodes");
        assertThatThrownBy(() -> WorkflowDslImporter.map(MAPPER, noNodes))
                .isInstanceOf(IllegalArgumentException.class);

        ObjectNode noVersion = export();
        noVersion.remove("schemaVersion");
        assertThatThrownBy(() -> WorkflowDslImporter.map(MAPPER, noVersion))
                .isInstanceOf(IllegalArgumentException.class);

        ObjectNode duplicate = export();
        ((ObjectNode) duplicate.get("nodes").get(1)).put("id", "t1");
        assertThatThrownBy(() -> WorkflowDslImporter.map(MAPPER, duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than once");

        ObjectNode wrongFormat = export();
        wrongFormat.put("format", "something.else");
        assertThatThrownBy(() -> WorkflowDslImporter.map(MAPPER, wrongFormat))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outOfRangeLimitsAreIgnoredWithAWarning() {
        ObjectNode doc = export();
        ((ObjectNode) doc.get("config")).put("maxStepsPerRun", 99999);
        PortableWorkflow wf = WorkflowDslImporter.map(MAPPER, doc);
        assertThat(wf.settings).doesNotContainKey("maxStepsPerRun");
        assertThat(wf.report.toReport().warnings()).anyMatch(w -> w.contains("maxStepsPerRun"));
    }

    @Test
    void resolverUsesMappingsAndRemovesWhatWasNotMapped() throws Exception {
        PortableWorkflow wf =
                WorkflowDslImporter.map(
                        MAPPER, MAPPER.readTree(WorkflowDslExporter.toJson(MAPPER, export())));
        String mappedAgent = UUID.randomUUID().toString();

        PlaceholderResolver.Result result =
                PlaceholderResolver.apply(
                        wf.nodes, Map.of("agent_1", mappedAgent), wf.placeholders);

        WorkflowNodeDto agent = result.nodes().get(1);
        assertThat(agent.config()).containsEntry("agentId", mappedAgent);
        WorkflowNodeDto knowledge = result.nodes().get(2);
        assertThat(knowledge.config()).doesNotContainKey("collectionId").containsEntry("topK", 5);
        assertThat(knowledge.data().get("needsReview")).isEqualTo(Boolean.TRUE);
        assertThat(knowledge.data().get("importNotes").toString()).contains("kb_1");
        WorkflowNodeDto output = result.nodes().get(4);
        assertThat(output.config()).doesNotContainKey("emailTo").containsEntry("channel", "email");
        assertThat(result.unresolved()).containsExactlyInAnyOrder("kb_1", "destination_1");
        assertThat(result.missing()).containsKeys("k1", "o1");
        // The sub-flow body's agent was mapped through the same placeholder.
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) result.nodes().get(5).config().get("body");
        assertThat(body.get("nodes").toString()).contains(mappedAgent);
        // The input is never mutated.
        assertThat(wf.nodes.get(1).config()).containsEntry("agentId", "@placeholder:agent_1");
    }
}
