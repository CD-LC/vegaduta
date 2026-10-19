package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.brief.ProofSource.ExperimentResult;
import com.agenticai.core.brief.ProofSource.ProofSnapshot;
import com.agenticai.core.brief.ProofSource.ScorePoint;
import com.agenticai.core.brief.dto.TrustDtos.EvidenceLine;
import com.agenticai.core.brief.dto.TrustDtos.TrustChange;
import com.agenticai.core.brief.dto.TrustDtos.TrustProposal;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ladder over the real production services: the rung -> tier mapping is exercised through applied agents,
 * approvals through the applied workflow graph, evidence through {@link InMemoryProofSource}.
 */
class TrustLadderServiceTest {

    private static final Instant NOW = EnvoyKit.NOW;

    private final EnvoyKit kit = new EnvoyKit();
    private UUID id;
    private String agentId;
    private String workflowId;

    @BeforeEach
    void applyAtTheLowestRung() {
        id = kit.savedAndApplied(EnvoyKit.richBrief());
        agentId = kit.entityIdOf(id, "a1");
        workflowId = kit.entityIdOf(id, "w1");
    }

    private WorkflowGraphDto graph() {
        return kit.ports.graphs.get(workflowId);
    }

    private int approvals() {
        return TrustGraphMaterializer.approvalCount(graph());
    }

    // ------------------------------------------------------------------ the three rungs

    @Test
    void underWatchUsesTheLowestTierAndPutsAnApprovalBeforeEverySideEffect() {
        assertThat(kit.entity(id).getRung()).isEqualTo("UNDER_WATCH");
        assertThat(kit.ports.tiers.get(agentId)).isEqualTo(AgentAutonomyTier.SUPERVISED);
        // tool (n3) and output (n4) each get one; nothing else does.
        assertThat(graph().nodes()).extracting("id").contains("ea-approve-n3", "ea-approve-n4")
            .doesNotContain("ea-approve-n2");
        assertThat(approvals()).isEqualTo(2);
        assertThat(kit.ports.approvalRequired).contains(TrustGraphMaterializer.APPROVAL_ACTION_TYPE);
    }

    @Test
    void trustedUsesTheMiddleTierAndOnlyGatesWhatTheBriefCalledIrreversible() {
        kit.proof[0] = new InMemoryProofSource(InMemoryProofSource.strong(NOW));

        TrustChange change = kit.trust.setRung(id, TrustRung.TRUSTED);

        assertThat(change.kind()).isEqualTo("PROMOTED");
        assertThat(kit.ports.tiers.get(agentId)).isEqualTo(AgentAutonomyTier.SEMI_AUTONOMOUS);
        // Only w1.n4 (the output) is irreversible; the tool step lost its approval.
        assertThat(graph().nodes()).extracting("id").contains("ea-approve-n4").doesNotContain("ea-approve-n3");
        assertThat(approvals()).isEqualTo(1);
        // Quality gate armed: max(gate.minScore 0.7, baseline 0.75 - noise 0.04 = 0.71) = 0.71.
        String datasetId = kit.entityIdOf(id, "eval");
        assertThat(kit.ports.gates.get(agentId)).startsWith(datasetId + "@0.71");
    }

    @Test
    void autonomousUsesTheHighestTierAndRemovesEveryApprovalNode() {
        kit.proof[0] = new InMemoryProofSource(InMemoryProofSource.strong(NOW));
        kit.trust.setRung(id, TrustRung.TRUSTED);

        kit.trust.setRung(id, TrustRung.AUTONOMOUS);

        assertThat(kit.ports.tiers.get(agentId)).isEqualTo(AgentAutonomyTier.FULLY_AUTONOMOUS);
        assertThat(approvals()).isZero();
        // The applied graph is exactly the Brief's graph again (the Brief never stored the approvals).
        assertThat(TrustGraphMaterializer.strip(graph())).isEqualTo(graph());
        assertThat(graph().nodes()).hasSize(EnvoyKit.richGraph().nodes().size());
        assertThat(kit.ports.gates).containsKey(agentId);
    }

    @Test
    void aPromotionIsOneNewVersionAndOneTrustEventCarryingTheEvidence() {
        kit.proof[0] = new InMemoryProofSource(InMemoryProofSource.strong(NOW));
        int versionsBefore = kit.versions.size();

        TrustChange change = kit.trust.setRung(id, TrustRung.TRUSTED);

        assertThat(kit.versions).hasSize(versionsBefore + 1);
        assertThat(kit.entity(id).getCurrentVersion()).isEqualTo(change.version());
        assertThat(kit.entity(id).getRung()).isEqualTo("TRUSTED");
        assertThat(kit.store.loadCurrent(kit.entity(id)).trust().rung()).isEqualTo("TRUSTED");
        assertThat(kit.events).hasSize(1);
        BriefTrustEventEntity event = kit.events.get(0);
        assertThat(event.getKind()).isEqualTo("PROMOTED");
        assertThat(event.getFromRung()).isEqualTo("UNDER_WATCH");
        assertThat(event.getToRung()).isEqualTo("TRUSTED");
        assertThat(event.getVersion()).isEqualTo(change.version());
        assertThat(event.getEvidenceJson()).contains("Eval score holds above the gate").contains("guardrails");
    }

    // ------------------------------------------------------------------ evidence

    @Test
    void promotionIsRefusedWithoutAProofSourceAndNothingChanges() {
        assertThatThrownBy(() -> kit.trust.setRung(id, TrustRung.TRUSTED))
            .isInstanceOf(BriefConflictException.class).hasMessageContaining("not connected");
        assertNothingMoved();
    }

    @Test
    void promotionIsRefusedWhenAnyEvidenceLineFails() {
        Instant now = NOW;
        ProofSnapshot struck = new ProofSnapshot(true, null, now.minus(Duration.ofDays(9)),
            List.of(new ScorePoint(now.minus(Duration.ofDays(2)), 0.82)), 8, 1, 12, 10, null, 0.75, 0.04);
        kit.proof[0] = new InMemoryProofSource(struck);

        assertThatThrownBy(() -> kit.trust.setRung(id, TrustRung.TRUSTED))
            .isInstanceOf(BriefConflictException.class).hasMessageContaining("1 guardrail strike");
        assertNothingMoved();
    }

    @Test
    void promotionMovesOneRungAtATime() {
        kit.proof[0] = new InMemoryProofSource(InMemoryProofSource.strong(NOW));

        assertThatThrownBy(() -> kit.trust.setRung(id, TrustRung.AUTONOMOUS))
            .isInstanceOf(BriefConflictException.class).hasMessageContaining("one rung at a time");
        assertNothingMoved();
    }

    @Test
    void aFailingEntityChangeRollsTheRungChangeBackAndRecordsNothing() {
        kit.proof[0] = new InMemoryProofSource(InMemoryProofSource.strong(NOW));
        kit.ports.failOn.add("armGate"); // the last thing a promotion does; the tier and the graph were already changed

        assertThatThrownBy(() -> kit.trust.setRung(id, TrustRung.TRUSTED))
            .isInstanceOf(BriefConflictException.class).hasMessageContaining("put back");

        assertThat(kit.ports.tiers.get(agentId)).isEqualTo(AgentAutonomyTier.SUPERVISED);
        assertThat(approvals()).isEqualTo(2);
        assertNothingMoved();
    }

    @Test
    void proposalListsEveryRuleAsALineAndIsEligibleOnlyWhenAllHold() {
        kit.proof[0] = new InMemoryProofSource(InMemoryProofSource.strong(NOW));

        TrustProposal proposal = kit.trust.proposal(id);

        assertThat(proposal.eligible()).isTrue();
        assertThat(proposal.currentRung()).isEqualTo("UNDER_WATCH");
        assertThat(proposal.targetRung()).isEqualTo("TRUSTED");
        assertThat(proposal.evidence()).extracting(EvidenceLine::key).containsExactly("plan", "eval", "guardrails", "feedback");
        assertThat(proposal.evidence()).allMatch(EvidenceLine::met);
        assertThat(proposal.diff().toString()).contains("/trust/rung").contains("TRUSTED");
        assertThat(proposal.effects()).anyMatch(e -> e.contains("SEMI_AUTONOMOUS"));
        assertThat(kit.events).extracting(BriefTrustEventEntity::getKind).containsExactly("PROPOSED");
        // Asking again does not pile up identical PROPOSED events.
        kit.trust.proposal(id);
        assertThat(kit.events).hasSize(1);
    }

    @Test
    void thereIsNoProposalWhenThePlanHasNoEvalRuns() {
        ProofSnapshot noPlan = new ProofSnapshot(false, "Eval runs are a paid feature.", NOW.minus(Duration.ofDays(9)),
            List.of(), 0, 0, 0, 0, null, null, null);
        kit.proof[0] = new InMemoryProofSource(noPlan);

        TrustProposal proposal = kit.trust.proposal(id);

        assertThat(proposal.eligible()).isFalse();
        assertThat(proposal.targetRung()).isNull();
        assertThat(proposal.evidence().get(0).detail()).isEqualTo("Eval runs are a paid feature.");
    }

    @Test
    void thereIsNoProposalWhenTheLedgerIsNotConnectedAndNoNumberIsMadeUp() {
        TrustProposal proposal = kit.trust.proposal(id);

        assertThat(proposal.eligible()).isFalse();
        assertThat(proposal.targetRung()).isNull();
        assertThat(proposal.evidence()).singleElement().satisfies(l -> assertThat(l.detail()).contains("not connected"));
    }

    @Test
    void theSevenDayRuleCanBeMetByTwentyRealRunsInstead() {
        Instant now = NOW;
        ProofSnapshot young = new ProofSnapshot(true, null, now.minus(Duration.ofDays(2)),
            List.of(new ScorePoint(now.minus(Duration.ofDays(1)), 0.8)), 25, 0, 12, 10, null, 0.75, 0.04);
        kit.proof[0] = new InMemoryProofSource(young);
        assertThat(kit.trust.proposal(id).eligible()).isTrue();

        ProofSnapshot youngAndQuiet = new ProofSnapshot(true, null, now.minus(Duration.ofDays(2)),
            List.of(new ScorePoint(now.minus(Duration.ofDays(1)), 0.8)), 5, 0, 12, 10, null, 0.75, 0.04);
        kit.proof[0] = new InMemoryProofSource(youngAndQuiet);
        TrustProposal notYet = kit.trust.proposal(id);
        assertThat(notYet.eligible()).isFalse();
        assertThat(notYet.evidence().stream().filter(l -> l.key().equals("eval")).findFirst().orElseThrow().detail())
            .contains("7 days or 20 real runs");
    }

    @Test
    void aScoreBelowTheGateAnywhereInTheWindowBlocksPromotion() {
        Instant now = NOW;
        ProofSnapshot dip = new ProofSnapshot(true, null, now.minus(Duration.ofDays(9)),
            List.of(new ScorePoint(now.minus(Duration.ofDays(5)), 0.55), new ScorePoint(now.minus(Duration.ofDays(1)), 0.85)),
            8, 0, 12, 10, null, 0.75, 0.04);
        kit.proof[0] = new InMemoryProofSource(dip);

        TrustProposal proposal = kit.trust.proposal(id);

        assertThat(proposal.eligible()).isFalse();
        assertThat(proposal.evidence().stream().filter(l -> l.key().equals("eval")).findFirst().orElseThrow().detail())
            .contains("0.55").contains("below the gate");
    }

    @Test
    void anExperimentWinOutsideTheNoiseFloorCanStandInForThumbs() {
        Instant now = NOW;
        ExperimentResult win = new ExperimentResult(true, 0.06, 0.03, "v3 vs v2");
        ProofSnapshot fewRatings = new ProofSnapshot(true, null, now.minus(Duration.ofDays(9)),
            List.of(new ScorePoint(now.minus(Duration.ofDays(2)), 0.82)), 8, 0, 3, 3, win, 0.75, 0.04);
        kit.proof[0] = new InMemoryProofSource(fewRatings);
        assertThat(kit.trust.proposal(id).eligible()).isTrue();

        ExperimentResult noise = new ExperimentResult(false, 0.01, 0.03, "inside the noise");
        kit.proof[0] = new InMemoryProofSource(new ProofSnapshot(true, null, now.minus(Duration.ofDays(9)),
            List.of(new ScorePoint(now.minus(Duration.ofDays(2)), 0.82)), 8, 0, 3, 3, noise, 0.75, 0.04));
        assertThat(kit.trust.proposal(id).eligible()).isFalse();
    }

    // ------------------------------------------------------------------ demotion

    @Test
    void demotionReInsertsTheApprovalsThatPromotionRemoved() {
        kit.proof[0] = new InMemoryProofSource(InMemoryProofSource.strong(NOW));
        kit.trust.setRung(id, TrustRung.TRUSTED);
        kit.trust.setRung(id, TrustRung.AUTONOMOUS);
        assertThat(approvals()).isZero();

        TrustChange change = kit.trust.setRung(id, TrustRung.UNDER_WATCH); // a person lowers it two rungs

        assertThat(change.kind()).isEqualTo("DEMOTED");
        assertThat(approvals()).isEqualTo(2);
        assertThat(graph().nodes()).extracting("id").contains("ea-approve-n3", "ea-approve-n4");
        assertThat(kit.ports.tiers.get(agentId)).isEqualTo(AgentAutonomyTier.SUPERVISED);
        assertThat(kit.ports.gates).as("no gate below TRUSTED").isEmpty();
        assertThat(kit.entity(id).getRung()).isEqualTo("UNDER_WATCH");
        assertThat(kit.ports.approvalRequired).contains(TrustGraphMaterializer.APPROVAL_ACTION_TYPE);
    }

    @Test
    void automaticDemotionLowersOneRungReInsertsApprovalsAndWritesTheEvidence() {
        kit.proof[0] = new InMemoryProofSource(InMemoryProofSource.strong(NOW));
        kit.trust.setRung(id, TrustRung.TRUSTED);
        kit.trust.setRung(id, TrustRung.AUTONOMOUS);
        assertThat(approvals()).isZero();

        TrustChange change = kit.trust.demote(id, "Guardrail strike on the collector agent",
            Map.of("guardrail", "PII in reply", "runId", "r-9")).orElseThrow();

        assertThat(change.fromRung()).isEqualTo("AUTONOMOUS");
        assertThat(change.toRung()).isEqualTo("TRUSTED");
        assertThat(kit.ports.tiers.get(agentId)).isEqualTo(AgentAutonomyTier.SEMI_AUTONOMOUS);
        assertThat(approvals()).isEqualTo(1); // the irreversible output is gated again
        BriefTrustEventEntity event = kit.events.get(kit.events.size() - 1);
        assertThat(event.getKind()).isEqualTo("DEMOTED");
        assertThat(event.getActor()).isEqualTo("system");
        assertThat(event.getEvidenceJson()).contains("Guardrail strike on the collector agent").contains("PII in reply");
    }

    @Test
    void demotingAtTheBottomOrBeforeApplyDoesNothing() {
        assertThat(kit.trust.demote(id, "strike", Map.of())).isEmpty();
        UUID draft = kit.save(EnvoyKit.richBrief());
        assertThat(kit.trust.demote(draft, "strike", Map.of())).isEmpty();
    }

    // ------------------------------------------------------------------ gate maths

    @Test
    void theGateIsTheHigherOfTheBriefsMinimumAndBaselineMinusNoise() {
        Brief brief = EnvoyKit.richBrief(); // gate.minScore 0.7
        Instant now = NOW;
        assertThat(TrustLadderService.gateMinScore(brief, null)).isEqualTo(0.7);
        ProofSnapshot high = new ProofSnapshot(true, null, now, List.of(), 0, 0, 0, 0, null, 0.9, 0.05);
        assertThat(TrustLadderService.gateMinScore(brief, high)).isCloseTo(0.85, org.assertj.core.api.Assertions.within(1e-9));
        ProofSnapshot low = new ProofSnapshot(true, null, now, List.of(), 0, 0, 0, 0, null, 0.6, 0.05);
        assertThat(TrustLadderService.gateMinScore(brief, low)).isEqualTo(0.7);
        // No measured noise floor: the baseline term is not guessed at.
        ProofSnapshot unmeasured = new ProofSnapshot(true, null, now, List.of(), 0, 0, 0, 0, null, 0.95, null);
        assertThat(TrustLadderService.gateMinScore(brief, unmeasured)).isEqualTo(0.7);
    }

    // ------------------------------------------------------------------ helpers

    private void assertNothingMoved() {
        assertThat(kit.entity(id).getRung()).isEqualTo("UNDER_WATCH");
        assertThat(kit.versions).hasSize(1);
        assertThat(kit.events).isEmpty();
        assertThat(kit.ports.tiers.get(agentId)).isEqualTo(AgentAutonomyTier.SUPERVISED);
    }
}
