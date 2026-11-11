package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link WorkflowGraphCodec}: graph JSON round-trips and the corrupt-graph contract. */
class WorkflowGraphCodecTest {

    private final WorkflowGraphCodec codec = new WorkflowGraphCodec(new ObjectMapper());

    @Test
    void writeThenReadRoundTripsTheGraph() {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                new WorkflowNodeDto(
                                        "n1",
                                        "agent",
                                        Map.of("x", 10, "y", 20),
                                        Map.of("label", "Fetch", "config", Map.of("k", "v")))),
                        List.of(new WorkflowEdgeDto("e1", "n1", "n2", "true", null)));
        String json = codec.writeGraph(graph);
        assertTrue(json.contains("\"n1\""));

        WorkflowGraphDto back = codec.readGraph(json);
        assertEquals(1, back.nodes().size());
        assertEquals("n1", back.nodes().get(0).id());
        assertEquals("Fetch", back.nodes().get(0).label());
        assertEquals("v", back.nodes().get(0).config().get("k"));
        assertEquals("e1", back.edges().get(0).id());
        assertEquals("true", back.edges().get(0).sourceHandle());
    }

    @Test
    void anEmptyDocumentReadsAsEmptyLists() {
        WorkflowGraphDto back = codec.readGraph("{}");
        assertTrue(back.nodes().isEmpty());
        assertTrue(back.edges().isEmpty());
    }

    @Test
    void corruptJsonThrowsTheCorruptGraphException() {
        assertThrows(WorkflowGraphCorruptException.class, () -> codec.readGraph("not json"));
        assertThrows(WorkflowGraphCorruptException.class, () -> codec.readGraph(null));
    }
}
