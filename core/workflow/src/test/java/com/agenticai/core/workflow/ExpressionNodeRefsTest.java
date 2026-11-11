package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.ExpressionNodeRefs.NodeRef;
import com.agenticai.core.workflow.ExpressionNodeRefs.RefKind;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link ExpressionNodeRefs}: {@code $node} reference scanning and upstream graph traversal. */
class ExpressionNodeRefsTest {

    private static WorkflowGraphDto chain() {
        return new WorkflowGraphDto(
                List.of(),
                List.of(
                        new WorkflowEdgeDto("e1", "a", "b", null, null),
                        new WorkflowEdgeDto("e2", "b", "c", null, null),
                        new WorkflowEdgeDto("e3", "x", "b", null, null)));
    }

    @Test
    void scanFindsEveryReferenceShape() {
        List<NodeRef> refs =
                ExpressionNodeRefs.scanNodeRefs(
                        "{{ $node[\"Fetch\"].x }}{{ $node.Label.y }}{{ $node.id('n7') }}");
        assertEquals(3, refs.size());
        assertEquals(RefKind.LABEL, refs.get(0).kind());
        assertEquals("Fetch", refs.get(0).name());
        assertEquals(RefKind.LABEL, refs.get(1).kind());
        assertEquals("Label", refs.get(1).name());
        assertEquals(RefKind.ID, refs.get(2).kind());
        assertEquals("n7", refs.get(2).name());
        for (NodeRef r : refs) {
            assertTrue(r.start() < r.end());
        }
    }

    @Test
    void idCallIsNotAlsoReportedAsADotLabel() {
        List<NodeRef> refs = ExpressionNodeRefs.scanNodeRefs("{{ $node.id(\"x\") }}");
        assertEquals(1, refs.size());
        assertEquals(RefKind.ID, refs.get(0).kind());
    }

    @Test
    void textOutsideSpansAndUnclosedSpansAreIgnored() {
        assertTrue(ExpressionNodeRefs.scanNodeRefs("no template at all").isEmpty());
        assertTrue(ExpressionNodeRefs.scanNodeRefs("$node.Label.x").isEmpty());
        assertTrue(ExpressionNodeRefs.scanNodeRefs("{{ $node.A.x").isEmpty());
        assertTrue(ExpressionNodeRefs.scanNodeRefs("{{ $node.9bad }}").isEmpty());
    }

    @Test
    void offsetsPointIntoTheTemplate() {
        String template = "a {{ $node.B.c }}";
        List<NodeRef> refs = ExpressionNodeRefs.scanNodeRefs(template);
        assertEquals(1, refs.size());
        NodeRef r = refs.get(0);
        assertTrue(r.start() > 3, "the ref starts after the braces");
        assertTrue(r.end() <= 15);
    }

    @Test
    void incomingEdgesGroupSourcesByTarget() {
        Map<String, List<String>> incoming = ExpressionNodeRefs.incomingEdges(chain());
        assertEquals(List.of("a", "x"), incoming.get("b"));
        assertEquals(List.of("b"), incoming.get("c"));
        assertTrue(incoming.get("a") == null);
    }

    @Test
    void ancestorsWalkUpstreamNearestFirst() {
        Map<String, List<String>> incoming = ExpressionNodeRefs.incomingEdges(chain());
        List<String> ordered = ExpressionNodeRefs.orderedAncestors("c", incoming);
        assertEquals(List.of("b", "a", "x"), ordered);
        assertEquals(Set.of("b", "a", "x"), ExpressionNodeRefs.ancestorsOf("c", incoming));
        assertTrue(ExpressionNodeRefs.ancestorsOf("a", incoming).isEmpty());
    }

    @Test
    void cyclesTerminate() {
        Map<String, List<String>> incoming =
                Map.of(
                        "a", List.of("b"),
                        "b", List.of("a"));
        assertEquals(Set.of("b"), ExpressionNodeRefs.ancestorsOf("a", incoming));
    }
}
