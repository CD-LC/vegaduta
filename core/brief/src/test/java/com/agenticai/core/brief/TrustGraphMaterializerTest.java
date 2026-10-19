package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The rung table of contract section 1, as pure functions. */
class TrustGraphMaterializerTest {

    private static final Brief.Trust TRUST = new Brief.Trust("UNDER_WATCH", List.of("w1.n4"), List.of("w1.n5"));

    /** trigger n1 -> agent n2 -> tool n3 -> output n4 -> http n5 (n5 must always have a human decide). */
    private static WorkflowGraphDto graph() {
        return new WorkflowGraphDto(List.of(
            BriefFixtures.node("n1", "trigger", Map.of()),
            BriefFixtures.node("n2", "agent", Map.of("agentId", "a1")),
            BriefFixtures.node("n3", "tool", Map.of()),
            BriefFixtures.node("n4", "output", Map.of()),
            BriefFixtures.node("n5", "http", Map.of())), List.of(
            new WorkflowEdgeDto("e1", "n1", "n2", null, null),
            new WorkflowEdgeDto("e2", "n2", "n3", null, null),
            new WorkflowEdgeDto("e3", "n3", "n4", null, null),
            new WorkflowEdgeDto("e4", "n4", "n5", null, null)));
    }

    private static List<String> ids(WorkflowGraphDto g) {
        return g.nodes().stream().map(WorkflowNodeDto::id).toList();
    }

    @Test
    void theRungsMapOntoTheLowestMiddleAndHighestRealAutonomyTier() {
        assertThat(TrustRung.UNDER_WATCH.tier()).isEqualTo(AgentAutonomyTier.SUPERVISED);
        assertThat(TrustRung.TRUSTED.tier()).isEqualTo(AgentAutonomyTier.SEMI_AUTONOMOUS);
        assertThat(TrustRung.AUTONOMOUS.tier()).isEqualTo(AgentAutonomyTier.FULLY_AUTONOMOUS);
        assertThat(TrustRung.UNDER_WATCH.up()).isEqualTo(TrustRung.TRUSTED);
        assertThat(TrustRung.AUTONOMOUS.up()).isNull();
        assertThat(TrustRung.UNDER_WATCH.down()).isNull();
        assertThat(TrustRung.parse("trusted")).isEqualTo(TrustRung.TRUSTED);
        assertThat(TrustRung.parse("nonsense")).isNull();
        assertThat(TrustRung.parseOrLowest("nonsense")).isEqualTo(TrustRung.UNDER_WATCH);
    }

    @Test
    void underWatchPutsAnApprovalBeforeEverySideEffectAndEveryHumanDecision() {
        WorkflowGraphDto g = TrustGraphMaterializer.materialise(graph(), "w1", TrustRung.UNDER_WATCH, TRUST);

        assertThat(ids(g)).contains("ea-approve-n3", "ea-approve-n4", "ea-approve-n5")
            .doesNotContain("ea-approve-n1", "ea-approve-n2");
        assertThat(TrustGraphMaterializer.approvalCount(g)).isEqualTo(3);
        // S -> T became S -> A -> T
        assertThat(g.edges()).anyMatch(e -> e.source().equals("n2") && e.target().equals("ea-approve-n3"));
        assertThat(g.edges()).anyMatch(e -> e.source().equals("ea-approve-n3") && e.target().equals("n3"));
        assertThat(g.edges()).noneMatch(e -> e.source().equals("n2") && e.target().equals("n3"));
        assertThat(g.nodes().stream().filter(n -> n.id().equals("ea-approve-n3")).findFirst().orElseThrow().type()).isEqualTo("approval");
    }

    @Test
    void trustedOnlyGatesTheIrreversibleAndTheMustDecideNodes() {
        WorkflowGraphDto g = TrustGraphMaterializer.materialise(graph(), "w1", TrustRung.TRUSTED, TRUST);

        assertThat(ids(g)).contains("ea-approve-n4", "ea-approve-n5").doesNotContain("ea-approve-n3");
        assertThat(TrustGraphMaterializer.approvalCount(g)).isEqualTo(2);
    }

    @Test
    void autonomousHasNoApprovalsButAMustDecideNodeKeepsAHumanInputStep() {
        WorkflowGraphDto g = TrustGraphMaterializer.materialise(graph(), "w1", TrustRung.AUTONOMOUS, TRUST);

        assertThat(TrustGraphMaterializer.approvalCount(g)).isZero();
        assertThat(ids(g)).contains("ea-human-n5").doesNotContain("ea-human-n4");
        assertThat(TrustGraphMaterializer.humanInputCount(g)).isEqualTo(1);
        assertThat(g.nodes().stream().filter(n -> n.id().equals("ea-human-n5")).findFirst().orElseThrow().type()).isEqualTo("human_input");
    }

    @Test
    void stripReturnsTheBriefGraphExactlyAtEveryRung() {
        for (TrustRung rung : TrustRung.values()) {
            WorkflowGraphDto materialised = TrustGraphMaterializer.materialise(graph(), "w1", rung, TRUST);
            WorkflowGraphDto back = TrustGraphMaterializer.strip(materialised);

            assertThat(ids(back)).containsExactlyInAnyOrderElementsOf(ids(graph()));
            assertThat(new HashSet<>(back.edges())).isEqualTo(new HashSet<>(graph().edges()));
        }
    }

    @Test
    void materialiseIsIdempotentAndSwitchingRungsNeverStacksGates() {
        WorkflowGraphDto once = TrustGraphMaterializer.materialise(graph(), "w1", TrustRung.UNDER_WATCH, TRUST);
        WorkflowGraphDto twice = TrustGraphMaterializer.materialise(once, "w1", TrustRung.UNDER_WATCH, TRUST);
        assertThat(twice).isEqualTo(once);

        WorkflowGraphDto toTrusted = TrustGraphMaterializer.materialise(once, "w1", TrustRung.TRUSTED, TRUST);
        assertThat(toTrusted).isEqualTo(TrustGraphMaterializer.materialise(graph(), "w1", TrustRung.TRUSTED, TRUST));
        WorkflowGraphDto backDown = TrustGraphMaterializer.materialise(toTrusted, "w1", TrustRung.UNDER_WATCH, TRUST);
        assertThat(new HashSet<>(backDown.edges())).isEqualTo(new HashSet<>(once.edges()));
        assertThat(ids(backDown)).containsExactlyInAnyOrderElementsOf(ids(once));
    }

    @Test
    void aConditionBranchKeepsItsHandleThroughTheGate() {
        WorkflowGraphDto g = new WorkflowGraphDto(List.of(
            BriefFixtures.node("n1", "trigger", Map.of()),
            BriefFixtures.node("n2", "condition", Map.of()),
            BriefFixtures.node("n3", "output", Map.of())),
            List.of(new WorkflowEdgeDto("e1", "n1", "n2", null, null),
                new WorkflowEdgeDto("e2", "n2", "n3", "true", null)));

        WorkflowGraphDto m = TrustGraphMaterializer.materialise(g, "w1", TrustRung.UNDER_WATCH, TRUST);

        assertThat(m.edges()).anyMatch(e -> e.source().equals("n2") && e.target().equals("ea-approve-n3") && "true".equals(e.sourceHandle()));
    }

    @Test
    void aGatedEntryNodeWithNothingInFrontOfItIsLeftAlone() {
        WorkflowGraphDto g = new WorkflowGraphDto(List.of(BriefFixtures.node("n1", "output", Map.of())), List.of());

        assertThat(TrustGraphMaterializer.materialise(g, "w1", TrustRung.UNDER_WATCH, TRUST)).isEqualTo(g);
    }

    @Test
    void trustPathsOfOtherWorkflowsDoNotLeakIn() {
        Brief.Trust other = new Brief.Trust("TRUSTED", List.of("w2.n4"), List.of());

        WorkflowGraphDto g = TrustGraphMaterializer.materialise(graph(), "w1", TrustRung.TRUSTED, other);

        assertThat(TrustGraphMaterializer.approvalCount(g)).isZero();
    }
}
