package com.agenticai.core.workflow.portability;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The shared node/edge constructors every importer uses, so a n8n node and a Dify node come out
 * with the same React Flow shape.
 */
class PortableNodesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void positionRoundsToWholePixels() {
        assertThat(PortableNodes.position(10.4, 20.6))
                .containsEntry("x", 10L)
                .containsEntry("y", 21L);
    }

    @Test
    void nodeBuildsTheCanvasShapeAndCarriesReviewFlags() {
        WorkflowNodeDto node =
                PortableNodes.node(
                        "n1",
                        "http",
                        "Call API",
                        PortableNodes.position(1, 2),
                        Map.of("method", "GET"),
                        List.of("check this"),
                        true);

        assertThat(node.id()).isEqualTo("n1");
        assertThat(node.type()).isEqualTo("http");
        assertThat(node.label()).isEqualTo("Call API");
        assertThat(node.config()).containsEntry("method", "GET");
        assertThat(node.data().get("needsReview")).isEqualTo(Boolean.TRUE);
        assertThat(node.data().get("importNotes")).isEqualTo(List.of("check this"));
    }

    @Test
    void nodeOmitsReviewKeysWhenNothingNeedsReview() {
        WorkflowNodeDto node =
                PortableNodes.node(
                        "n1", "code", "T", PortableNodes.position(0, 0), null, null, false);
        assertThat(node.data()).doesNotContainKeys("needsReview", "importNotes");
        assertThat(node.config()).isEmpty();
    }

    @Test
    void edgeCarriesTheSourceHandleOnly() {
        WorkflowEdgeDto edge = PortableNodes.edge("e1", "a", "true", "b");
        assertThat(edge.id()).isEqualTo("e1");
        assertThat(edge.source()).isEqualTo("a");
        assertThat(edge.target()).isEqualTo("b");
        assertThat(edge.sourceHandle()).isEqualTo("true");
        assertThat(edge.targetHandle()).isNull();
    }

    @Test
    void passthroughConfigIsTheInertTransform() {
        assertThat(PortableNodes.passthroughConfig())
                .containsEntry("operation", PortableNodes.PASSTHROUGH_OPERATION);
    }

    @Test
    void importedFromNullsBecomeEmptyStrings() {
        Map<String, Object> from = PortableNodes.importedFrom("n8n", null, null, Map.of("a", 1));
        assertThat(from)
                .containsEntry("platform", "n8n")
                .containsEntry("type", "")
                .containsEntry("name", "")
                .containsKey("parameters");
        assertThat(PortableNodes.importedFrom("dify", "t", "n", null))
                .doesNotContainKey("parameters");
    }

    @Test
    void plainConvertsJacksonNodesToJavaValues() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("a", 1).putArray("b").add("x");
        Object plain = PortableNodes.plain(MAPPER, node);
        assertThat(plain).isInstanceOf(Map.class);
        assertThat(plain.toString()).contains("a=1");
        assertThat(PortableNodes.plain(MAPPER, null)).isNull();
        assertThat(PortableNodes.plain(MAPPER, MAPPER.missingNode())).isNull();
    }

    @Test
    void cappedKeepsSmallValuesAndStubsLargeOnes() {
        assertThat(PortableNodes.capped(MAPPER, Map.of("a", 1), 1000)).isEqualTo(Map.of("a", 1));
        Object big = PortableNodes.capped(MAPPER, Map.of("blob", "x".repeat(5000)), 10);
        assertThat(big).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> stub = (Map<String, Object>) big;
        assertThat(stub).containsEntry("truncated", Boolean.TRUE);
        assertThat(stub.get("preview").toString()).hasSize(4000);
    }

    @Test
    void slugNormalisesAndFallsBack() {
        assertThat(PortableNodes.slug("My Flow! v2", "x")).isEqualTo("my-flow-v2");
        assertThat(PortableNodes.slug(null, "fallback")).isEqualTo("fallback");
        assertThat(PortableNodes.slug("???", "fallback")).isEqualTo("fallback");
        assertThat(PortableNodes.slug("a".repeat(80), "x")).hasSize(40);
    }
}
