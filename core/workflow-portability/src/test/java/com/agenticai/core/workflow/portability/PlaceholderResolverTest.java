package com.agenticai.core.workflow.portability;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code @placeholder:<key>} resolution: mapped keys become real values, unmapped keys lose their
 * field (a DRAFT never carries a foreign id), and every node that lost a field is flagged for
 * review. The input graph is never mutated.
 */
class PlaceholderResolverTest {

    private static WorkflowNodeDto node(String id, Map<String, Object> config) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", "Node " + id);
        data.put("config", config);
        return new WorkflowNodeDto(id, "agent", Map.of("x", 0, "y", 0), data);
    }

    private static PlaceholderRegistry registryWith(String kind, String key, String label) {
        PlaceholderRegistry registry = new PlaceholderRegistry();
        registry.registerExact(key, kind, label, "n1", "f");
        return registry;
    }

    @Test
    void mappedTokensAreSwappedForTheValue() {
        WorkflowNodeDto input =
                node("n1", new LinkedHashMap<>(Map.of("agentId", "@placeholder:agent_1")));
        PlaceholderResolver.Result result =
                PlaceholderResolver.apply(
                        List.of(input),
                        Map.of("agent_1", "real-agent-id"),
                        new PlaceholderRegistry());

        assertThat(result.nodes().get(0).config()).containsEntry("agentId", "real-agent-id");
        assertThat(result.unresolved()).isEmpty();
        assertThat(result.missing()).isEmpty();
        assertThat(result.nodes().get(0).data()).doesNotContainKey("needsReview");
    }

    @Test
    void unmappedTokensLoseTheirFieldAndFlagTheNode() {
        WorkflowNodeDto input =
                node(
                        "n1",
                        new LinkedHashMap<>(
                                Map.of("agentId", "@placeholder:agent_1", "keep", "me")));
        PlaceholderResolver.Result result =
                PlaceholderResolver.apply(
                        List.of(input),
                        Map.of(),
                        registryWith(PlaceholderRegistry.AGENT, "agent_1", "Researcher"));

        WorkflowNodeDto out = result.nodes().get(0);
        assertThat(out.config()).doesNotContainKey("agentId").containsEntry("keep", "me");
        assertThat(result.unresolved()).containsExactly("agent_1");
        assertThat(result.missing()).containsEntry("n1", List.of("agentId"));
        assertThat(out.data().get("needsReview")).isEqualTo(Boolean.TRUE);
        assertThat(out.data().get("importNotes").toString())
                .contains("the agent 'Researcher'")
                .contains("agent_1");
    }

    @Test
    void describeCoversEveryKindAndAnUnknownEntry() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("a", "@placeholder:kb_1");
        config.put("b", "@placeholder:workflow_1");
        config.put("c", "@placeholder:group_1");
        config.put("d", "@placeholder:device_1");
        config.put("e", "@placeholder:destination_1");
        config.put("f", "@placeholder:credential_1");
        config.put("g", "@placeholder:ghost_1");

        PlaceholderRegistry registry = new PlaceholderRegistry();
        registry.registerExact("kb_1", PlaceholderRegistry.KNOWLEDGE_BASE, "Docs", "n1", "f");
        registry.registerExact("workflow_1", PlaceholderRegistry.WORKFLOW, "Sub", "n1", "f");
        registry.registerExact("group_1", PlaceholderRegistry.CONTACT_GROUP, "Ops", "n1", "f");
        registry.registerExact("device_1", PlaceholderRegistry.DEVICE, "Phone", "n1", "f");
        registry.registerExact("destination_1", PlaceholderRegistry.DESTINATION, "Mail", "n1", "f");
        registry.registerExact("credential_1", PlaceholderRegistry.CREDENTIAL, "Key", "n1", "f");
        // ghost_1 is deliberately NOT registered: the note falls back to the generic phrasing.

        PlaceholderResolver.Result result =
                PlaceholderResolver.apply(List.of(node("n1", config)), Map.of(), registry);

        String notes = result.nodes().get(0).data().get("importNotes").toString();
        assertThat(notes)
                .contains("the knowledge base 'Docs'")
                .contains("the workflow 'Sub'")
                .contains("the contact group 'Ops'")
                .contains("the device 'Phone'")
                .contains("the destination 'Mail'")
                .contains("a value 'Key'")
                .contains("an unknown reference");
    }

    @Test
    void tokensInsideNestedMapsAndListsResolveToo() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(
                "headers",
                List.of(
                        Map.of("name", "Authorization", "value", "@placeholder:agent_1"),
                        Map.of("name", "X-Keep", "value", "1")));
        config.put(
                "options",
                Map.of(
                        "agentId",
                        "@placeholder:agent_1",
                        "list",
                        List.of("@placeholder:agent_1", "x")));
        WorkflowNodeDto input = node("n1", config);

        PlaceholderResolver.Result result =
                PlaceholderResolver.apply(
                        List.of(input), Map.of("agent_1", "real-id"), new PlaceholderRegistry());

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) result.nodes().get(0).data().get("config");
        assertThat(out.toString()).contains("real-id").doesNotContain("@placeholder:");
    }

    @Test
    void anExistingImportNotesListIsAppendedNotReplaced() {
        Map<String, Object> config = new LinkedHashMap<>(Map.of("f", "@placeholder:agent_1"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", "L");
        data.put("config", config);
        List<String> notes = new ArrayList<>(List.of("first note"));
        data.put("importNotes", notes);
        WorkflowNodeDto input = new WorkflowNodeDto("n1", "agent", Map.of(), data);

        PlaceholderResolver.Result result =
                PlaceholderResolver.apply(
                        List.of(input),
                        Map.of(),
                        registryWith(PlaceholderRegistry.AGENT, "agent_1", "A"));

        assertThat((List<?>) result.nodes().get(0).data().get("importNotes"))
                .hasSize(2)
                .first()
                .isEqualTo("first note");
    }

    @Test
    void nodesWithoutConfigOrDataPassThroughUntouched() {
        WorkflowNodeDto noData = new WorkflowNodeDto("n1", "agent", Map.of(), null);
        WorkflowNodeDto noConfig =
                new WorkflowNodeDto("n2", "agent", Map.of(), Map.of("label", "L"));
        PlaceholderResolver.Result result =
                PlaceholderResolver.apply(
                        List.of(noData, noConfig), Map.of(), new PlaceholderRegistry());
        assertThat(result.nodes()).hasSize(2);
        assertThat(result.missing()).isEmpty();
        assertThat(result.nodes().get(0).data()).isNotNull();
    }

    @Test
    void nonTokenStringsAndNonContainerValuesAreKeptVerbatim() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("text", "see @placeholder:agent_1 mid-string");
        config.put("num", 42);
        config.put("flag", Boolean.TRUE);
        config.put("nothing", null);
        WorkflowNodeDto input = node("n1", config);

        PlaceholderResolver.Result result =
                PlaceholderResolver.apply(List.of(input), Map.of(), new PlaceholderRegistry());

        assertThat(result.nodes().get(0).config())
                .containsEntry("text", "see @placeholder:agent_1 mid-string")
                .containsEntry("num", 42)
                .containsEntry("flag", Boolean.TRUE)
                .containsKey("nothing");
        assertThat(result.unresolved()).isEmpty();
    }
}
