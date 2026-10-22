package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.brief.model.Brief;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BriefJsonPatchTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode tree(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void identicalDocumentsHaveAnEmptyPatch() throws Exception {
        assertThat(BriefJsonPatch.diff(tree("{\"a\":[1,2]}"), tree("{\"a\":[1,2]}"))).isEmpty();
    }

    @Test
    void producesAddRemoveAndReplaceOperations() throws Exception {
        JsonNode from = tree("{\"title\":\"A\",\"gone\":1,\"nested\":{\"x\":1}}");
        JsonNode to = tree("{\"title\":\"B\",\"nested\":{\"x\":1,\"y\":2},\"fresh\":true}");

        List<ObjectNode> ops = BriefJsonPatch.diff(from, to);

        assertThat(ops)
                .extracting(o -> o.get("op").asText() + " " + o.get("path").asText())
                .containsExactlyInAnyOrder(
                        "replace /title", "remove /gone", "add /nested/y", "add /fresh");
    }

    @Test
    void replayingAPatchOnTheOldDocumentGivesTheNewOne() throws Exception {
        JsonNode from =
                tree(
                        "{\"list\":[{\"ref\":\"a1\",\"v\":1},{\"ref\":\"a2\",\"v\":2},{\"ref\":\"a3\"}],\"k/ey~\":1}");
        JsonNode to =
                tree(
                        "{\"list\":[{\"ref\":\"a1\",\"v\":9},{\"ref\":\"a2\",\"v\":2}],\"k/ey~\":2,\"added\":[1]}");

        assertThat(BriefJsonPatch.apply(from, BriefJsonPatch.diff(from, to))).isEqualTo(to);
        // and the other direction, which grows the array instead of shrinking it
        assertThat(BriefJsonPatch.apply(to, BriefJsonPatch.diff(to, from))).isEqualTo(from);
    }

    @Test
    void aRealBriefRoundTripsThroughDiffAndApply() {
        Brief a = EnvoyKit.richBrief();
        List<Brief.AgentSpec> agents = new ArrayList<>(a.agents());
        Brief.AgentSpec first = agents.get(0);
        agents.set(
                0,
                new Brief.AgentSpec(
                        first.ref(),
                        first.name(),
                        first.description(),
                        "New prompt",
                        first.reasoningDepth(),
                        first.tuningMode(),
                        first.generation(),
                        first.toolAllowList(),
                        first.knowledgeRefs(),
                        first.procedureRefs(),
                        first.guardrails(),
                        first.edgeRoutingPolicy(),
                        first.shareMemoryWithTenant()));
        Brief b =
                new Brief(
                        a.briefVersion(),
                        a.title() + " v2",
                        a.goal(),
                        a.locale(),
                        a.clarifications(),
                        a.trust(),
                        agents,
                        a.procedures(),
                        a.workflows(),
                        a.tools(),
                        a.mcpServers(),
                        a.knowledge(),
                        a.secrets(),
                        a.channels(),
                        a.evalSet(),
                        a.rehearsal(),
                        a.narrative());

        JsonNode from = mapper.valueToTree(a);
        JsonNode to = mapper.valueToTree(b);
        List<ObjectNode> ops = BriefJsonPatch.diff(from, to);

        assertThat(ops)
                .extracting(o -> o.get("path").asText())
                .containsExactlyInAnyOrder("/title", "/agents/0/systemPrompt");
        assertThat(BriefJsonPatch.apply(from, ops)).isEqualTo(to);
    }
}
