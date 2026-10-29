package com.agenticai.core.workflow.portability;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.portability.N8nImportModel.Built;
import com.agenticai.core.workflow.portability.N8nImportModel.Conn;
import com.agenticai.core.workflow.portability.N8nImportModel.Outputs;
import com.agenticai.core.workflow.portability.N8nImportModel.Src;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * n8n's {@code main} connections become React Flow edges: the output index becomes the source
 * handle, dead ends are dropped with a warning, and a Split In Batches loop body loses its
 * back-connection because this graph cannot hold a cycle.
 */
class N8nEdgeBuilderTest {

    private static Built built(String id, String label, Outputs outputs) {
        Built b = new Built();
        b.node = new WorkflowNodeDto(id, "type", Map.of("x", 0L, "y", 0L), Map.of("label", label));
        b.outputs = outputs;
        return b;
    }

    private static Src src(String name, double version) {
        return new Src(0, "id-" + name, name, "type", version, null, null);
    }

    private static Map<String, Src> byName(String... names) {
        Map<String, Src> map = new HashMap<>();
        for (String name : names) {
            map.put(name, src(name, 3));
        }
        return map;
    }

    @Test
    void aPlainConnectionBecomesAHandlelessEdge() {
        PortableWorkflow wf = new PortableWorkflow();
        Map<String, Built> built = new HashMap<>();
        built.put("A", built("n-a", "A", Outputs.PLAIN));
        built.put("B", built("n-b", "B", Outputs.PLAIN));

        new N8nEdgeBuilder(wf, byName("A", "B"), built).build(List.of(new Conn("A", 0, "B")));

        assertThat(wf.edges).hasSize(1);
        WorkflowEdgeDto edge = wf.edges.get(0);
        assertThat(edge.id()).isEqualTo("e1");
        assertThat(edge.source()).isEqualTo("n-a");
        assertThat(edge.target()).isEqualTo("n-b");
        assertThat(edge.sourceHandle()).isNull();
        assertThat(wf.fidelity.connectionsKept).isEqualTo(1);
        assertThat(wf.fidelity.connectionsDropped).isZero();
    }

    @Test
    void conditionAndIndexedOutputsBecomeSourceHandles() {
        PortableWorkflow wf = new PortableWorkflow();
        Map<String, Built> built = new HashMap<>();
        built.put("IF", built("n-if", "IF", Outputs.CONDITION));
        built.put("SW", built("n-sw", "SW", Outputs.INDEXED));
        built.put("T1", built("n-t1", "T1", Outputs.PLAIN));
        built.put("T2", built("n-t2", "T2", Outputs.PLAIN));
        built.put("T3", built("n-t3", "T3", Outputs.PLAIN));
        built.put("T4", built("n-t4", "T4", Outputs.PLAIN));

        new N8nEdgeBuilder(wf, byName("IF", "SW", "T1", "T2", "T3", "T4"), built)
                .build(
                        List.of(
                                new Conn("IF", 0, "T1"),
                                new Conn("IF", 1, "T2"),
                                new Conn("IF", 3, "T3"),
                                new Conn("SW", 2, "T4")));

        assertThat(wf.edges)
                .extracting(WorkflowEdgeDto::sourceHandle)
                .containsExactly("true", "false", "output_3", "output_2");
    }

    @Test
    void aConnectionToAnUnknownNodeIsDroppedWithAWarning() {
        PortableWorkflow wf = new PortableWorkflow();
        Map<String, Built> built = new HashMap<>();
        built.put("A", built("n-a", "A", Outputs.PLAIN));

        new N8nEdgeBuilder(wf, byName("A"), built).build(List.of(new Conn("A", 0, "Ghost")));

        assertThat(wf.edges).isEmpty();
        assertThat(wf.fidelity.connectionsDropped).isEqualTo(1);
        assertThat(wf.report.toReport().warnings())
                .anySatisfy(w -> assertThat(w).contains("Ghost"));
    }

    @Test
    void theSameEdgeIsNotWrittenTwice() {
        PortableWorkflow wf = new PortableWorkflow();
        Map<String, Built> built = new HashMap<>();
        built.put("A", built("n-a", "A", Outputs.PLAIN));
        built.put("B", built("n-b", "B", Outputs.PLAIN));

        new N8nEdgeBuilder(wf, byName("A", "B"), built)
                .build(List.of(new Conn("A", 0, "B"), new Conn("A", 0, "B")));

        assertThat(wf.edges).hasSize(1);
        assertThat(wf.fidelity.connectionsKept).isEqualTo(1);
    }

    @Test
    void twoTargetsOnOneOutputWarnAboutTheFollowedOneOnly() {
        PortableWorkflow wf = new PortableWorkflow();
        Map<String, Built> built = new HashMap<>();
        built.put("A", built("n-a", "Source", Outputs.PLAIN));
        built.put("B", built("n-b", "B", Outputs.PLAIN));
        built.put("C", built("n-c", "C", Outputs.PLAIN));

        new N8nEdgeBuilder(wf, byName("A", "B", "C"), built)
                .build(List.of(new Conn("A", 0, "B"), new Conn("A", 0, "C")));

        assertThat(wf.edges).hasSize(2);
        assertThat(wf.report.toReport().warnings())
                .anySatisfy(w -> assertThat(w).contains("Source").contains("2"));
    }

    @Test
    void aSplitInBatchesLoopKeepsTheForwardEdgeAndDropsTheBackEdge() {
        PortableWorkflow wf = new PortableWorkflow();
        Map<String, Built> built = new HashMap<>();
        built.put("Loop", built("n-loop", "Loop", Outputs.LOOP));
        built.put("Body", built("n-body", "Body", Outputs.PLAIN));
        built.put("After", built("n-after", "After", Outputs.PLAIN));

        // n8n wires: Loop(0)=loop -> Body, Body -> Loop (the back edge), Loop(1)=done -> After.
        new N8nEdgeBuilder(wf, byName("Loop", "Body", "After"), built)
                .build(
                        List.of(
                                new Conn("Loop", 0, "Body"),
                                new Conn("Body", 0, "Loop"),
                                new Conn("Loop", 1, "After")));

        // Forward loop edge kept; the done output is reattached after the loop body.
        assertThat(wf.edges)
                .extracting(e -> e.source() + "->" + e.target())
                .containsExactly("n-loop->n-body", "n-body->n-after");
        assertThat(wf.fidelity.connectionsDropped).isEqualTo(1);
        assertThat(wf.fidelity.connectionsKept).isEqualTo(2);
        assertThat(wf.report.toReport().unsupported())
                .anySatisfy(u -> assertThat(u.what()).contains("Loop"));
    }

    @Test
    void aSecondNodeFedByTheLoopOutputIsDropped() {
        PortableWorkflow wf = new PortableWorkflow();
        Map<String, Built> built = new HashMap<>();
        built.put("Loop", built("n-loop", "Loop", Outputs.LOOP));
        built.put("Body", built("n-body", "Body", Outputs.PLAIN));
        built.put("Other", built("n-other", "Other", Outputs.PLAIN));
        built.put("After", built("n-after", "After", Outputs.PLAIN));

        new N8nEdgeBuilder(wf, byName("Loop", "Body", "Other", "After"), built)
                .build(
                        List.of(
                                new Conn("Loop", 0, "Body"),
                                new Conn("Body", 0, "Loop"),
                                new Conn("Loop", 0, "Other"),
                                new Conn("Loop", 1, "After")));

        // Only the first node on the loop output is kept.
        assertThat(wf.edges)
                .extracting(e -> e.source() + "->" + e.target())
                .contains("n-loop->n-body", "n-body->n-after")
                .doesNotContain("n-loop->n-other");
        assertThat(wf.report.toReport().warnings())
                .anySatisfy(w -> assertThat(w).contains("Other"));
    }
}
