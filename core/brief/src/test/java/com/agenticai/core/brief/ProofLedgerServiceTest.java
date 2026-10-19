package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.brief.ProofData.ExperimentRow;
import com.agenticai.core.brief.ProofData.Thumbs;
import com.agenticai.core.brief.ProofData.Variant;
import com.agenticai.core.brief.ProofSource.ProofSnapshot;
import com.agenticai.core.brief.dto.LedgerDtos;
import com.agenticai.core.brief.dto.LedgerDtos.Ledger;
import com.agenticai.core.brief.dto.LedgerDtos.LedgerSummary;
import com.agenticai.core.brief.dto.TrustDtos.TrustProposal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The ledger over the real production services (apply engine, versions, trust ladder) with only the platform faked:
 * measured numbers are exactly what {@link FakeProofData} holds, so anything the ledger shows that is not in there
 * would be an invention.
 */
class ProofLedgerServiceTest {

    private final EnvoyKit kit = new EnvoyKit();
    private final FakeProofData data = new FakeProofData();
    private ProofLedgerService ledger;
    private UUID id;

    @BeforeEach
    void apply() {
        ledger = new ProofLedgerService(kit.store, kit.ledger, kit.ports, data, kit.eventRepository, kit.mapper,
            Clock.fixed(EnvoyKit.NOW, ZoneOffset.UTC));
        id = kit.savedAndApplied(EnvoyKit.richBrief());
    }

    private static Instant ago(Duration d) {
        return Instant.now().minus(d);
    }

    // ------------------------------------------------------------------ states

    @Test
    void aBriefThatIsNotRunningHasNothingMeasured() {
        UUID draft = kit.save(EnvoyKit.richBrief());

        Ledger l = ledger.ledger(draft);

        assertThat(l.state()).isEqualTo(LedgerDtos.NOT_APPLIED);
        assertThat(l.reason()).contains("not running");
        assertThat(l.runs()).isEmpty();
        assertThat(l.baseline()).isNull();
    }

    @Test
    void whenThePlanHasNoEvalRunsTheLedgerIsLockedWithTheReasonAndNoNumbers() {
        // Runs and thumbs exist in the data - a locked ledger must not surface any of them.
        data.runs = List.of(FakeProofData.run("r1", ago(Duration.ofDays(1)), 0.80, "PASSED"));
        data.floor = 0.03;
        data.thumbs = new Thumbs(9, 1);
        kit.ports.evalAllowed = false;

        Ledger l = ledger.ledger(id);

        assertThat(l.state()).isEqualTo(LedgerDtos.LOCKED);
        assertThat(l.reason()).contains("not part of your plan");
        assertThat(l.baseline()).isNull();
        assertThat(l.noiseFloor()).isNull();
        assertThat(l.runs()).isEmpty();
        assertThat(l.thumbs()).isNull();
        assertThat(l.experiments()).isEmpty();
    }

    @Test
    void aLockedLedgerStillListsTheTrustHistory() {
        kit.proof[0] = new InMemoryProofSource(InMemoryProofSource.strong(EnvoyKit.NOW));
        kit.trust.setRung(id, TrustRung.TRUSTED);
        kit.ports.evalAllowed = false;

        Ledger l = ledger.ledger(id);

        assertThat(l.state()).isEqualTo(LedgerDtos.LOCKED);
        assertThat(l.rung()).isEqualTo("TRUSTED");
        assertThat(l.trustEvents()).hasSize(1);
        assertThat(l.trustEvents().get(0).kind()).isEqualTo("PROMOTED");
        assertThat(l.trustEvents().get(0).evidence()).isNotNull();
    }

    @Test
    void aBriefWithoutAnEvalDatasetSaysSoInsteadOfShowingZeros() {
        kit.ledger.unbind(id, BriefApplyService.EVAL_REF);

        Ledger l = ledger.ledger(id);

        assertThat(l.state()).isEqualTo(LedgerDtos.NO_EVAL_SET);
        assertThat(l.runs()).isEmpty();
        assertThat(l.baseline()).isNull();
    }

    @Test
    void beforeAnyRunFinishesTheLedgerIsCollectingNotZero() {
        Ledger l = ledger.ledger(id);

        assertThat(l.state()).isEqualTo(LedgerDtos.COLLECTING);
        assertThat(l.reason()).contains("baseline eval run has not finished");
        assertThat(l.baseline()).isNull();
        assertThat(l.runs()).isEmpty();
        assertThat(l.thumbs().ratio()).isNull();
    }

    // ------------------------------------------------------------------ the numbers

    @Test
    void theFirstRunIsTheBaselineAndLaterRunsAreJudgedAgainstTheNoiseFloor() {
        data.floor = 0.04;
        data.runs = List.of(
            FakeProofData.run("r1", ago(Duration.ofDays(3)), 0.75, "PASSED"),
            FakeProofData.run("r2", ago(Duration.ofDays(2)), 0.78, "PASSED"),
            FakeProofData.run("r3", ago(Duration.ofDays(1)), 0.90, "PASSED"));
        data.thumbs = new Thumbs(7, 3);

        Ledger l = ledger.ledger(id);

        assertThat(l.state()).isEqualTo(LedgerDtos.READY);
        assertThat(l.reason()).isNull();
        assertThat(l.baseline().runId()).isEqualTo("r1");
        assertThat(l.baseline().score()).isEqualTo(0.75);
        assertThat(l.noiseFloor()).isEqualTo(0.04);
        assertThat(l.runs()).extracting(r -> r.runId()).containsExactly("r1", "r2", "r3");
        assertThat(l.runs().get(1).significant()).isFalse();   // +0.03 is inside 0.04
        assertThat(l.runs().get(1).change()).isEqualTo("WITHIN_NOISE");
        assertThat(l.runs().get(2).significant()).isTrue();    // +0.12 is outside it
        assertThat(l.thumbs().rated()).isEqualTo(10);
        assertThat(l.thumbs().ratio()).isEqualTo(0.7);
    }

    @Test
    void aFloorThatCannotBeReadMeansNoSignificanceClaimNotAFailedLedger() {
        data.floorFailure = new IllegalStateException("reliability down");
        data.runs = List.of(
            FakeProofData.run("r1", ago(Duration.ofDays(2)), 0.40, "FAILED"),
            FakeProofData.run("r2", ago(Duration.ofDays(1)), 0.90, "PASSED"));

        Ledger l = ledger.ledger(id);

        assertThat(l.state()).isEqualTo(LedgerDtos.READY);
        assertThat(l.noiseFloor()).isNull();
        assertThat(l.runs().get(1).significant()).isFalse();
        assertThat(l.runs().get(1).change()).isEqualTo("UNKNOWN_NOISE");
    }

    @Test
    void anExperimentTheBriefStartedIsComparedButOneItDidNotIsOnlyListed() {
        ExperimentRow ours = new ExperimentRow("e1", "agent", ProofLedgerService.EXPERIMENT_PREFIX + id + " running vs earlier version",
            "RUNNING", ago(Duration.ofHours(2)), new Variant("c", 80, 60, 85.0, 1.0), new Variant("t", 80, 60, 55.0, 2.0));
        ExperimentRow theirs = new ExperimentRow("e2", "agent", "my own test", "STOPPED", ago(Duration.ofHours(5)),
            new Variant("c", 80, 60, 85.0, 1.0), new Variant("t", 80, 60, 55.0, 2.0));
        data.experiments = List.of(ours, theirs);

        Ledger l = ledger.ledger(id);

        assertThat(l.experiments()).hasSize(2);
        assertThat(l.experiments().get(0).id()).isEqualTo("e1");
        assertThat(l.experiments().get(0).agentRef()).isEqualTo("a1");
        assertThat(l.experiments().get(0).comparison().significant()).isTrue();
        assertThat(l.experiments().get(1).comparison()).isNull();
    }

    @Test
    void theListSummaryIsLightAndNeverCallsADeltaSignificant() {
        data.floor = 0.01;
        data.runs = List.of(
            FakeProofData.run("r1", ago(Duration.ofDays(2)), 0.60, "FAILED"),
            FakeProofData.run("r2", ago(Duration.ofDays(1)), 0.90, "PASSED"));

        LedgerSummary light = ledger.summary(id, false);
        LedgerSummary full = ledger.summary(id, true);

        assertThat(light.state()).isEqualTo(LedgerDtos.READY);
        assertThat(light.baseline()).isEqualTo(0.60);
        assertThat(light.lastDelta()).isCloseTo(0.30, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(light.lastDeltaSignificant()).isFalse();
        assertThat(light.noiseFloor()).isNull();
        assertThat(full.lastDeltaSignificant()).isTrue();
        assertThat(full.noiseFloor()).isEqualTo(0.01);
    }

    @Test
    void aLockedSummaryCarriesTheReasonAndNoNumbers() {
        kit.ports.evalAllowed = false;

        LedgerSummary s = ledger.summary(id, false);

        assertThat(s.state()).isEqualTo(LedgerDtos.LOCKED);
        assertThat(s.reason()).isNotBlank();
        assertThat(s.baseline()).isNull();
        assertThat(s.latest()).isNull();
        assertThat(s.lastDelta()).isNull();
    }

    // ------------------------------------------------------------------ baseline run on apply

    @Test
    void anApplyStartsOneEvalRunOnTheTargetAgentAndDataset() {
        ledger.applied(id);

        assertThat(data.startedFor).containsExactly(kit.entityIdOf(id, "a1") + "@" + kit.entityIdOf(id, "eval"));
    }

    @Test
    void noEvalRunIsStartedWhenThePlanHasNone() {
        kit.ports.evalAllowed = false;

        ledger.applied(id);

        assertThat(data.startedFor).isEmpty();
    }

    @Test
    void noEvalRunIsStartedWhenThereIsNoDataset() {
        kit.ledger.unbind(id, BriefApplyService.EVAL_REF);

        ledger.applied(id);

        assertThat(data.startedFor).isEmpty();
    }

    @Test
    void aRunAlreadyInProgressIsNotAnError() {
        data.startedRunId = null;

        ledger.applied(id);   // must simply return

        assertThat(data.startedFor).hasSize(1);
    }

    // ------------------------------------------------------------------ the ProofSource

    @Test
    void theSnapshotHoldsOnlyWhatWasMeasuredSinceTheRungBegan() {
        Instant before = ago(Duration.ofDays(30));
        Instant after = Instant.now().plusSeconds(5);
        data.runs = List.of(
            FakeProofData.run("old", before, 0.60, "FAILED"),
            FakeProofData.run("new", after, 0.85, "PASSED"));
        data.floor = 0.04;
        data.realRuns = 12;
        data.thumbs = new Thumbs(9, 3);
        data.recordStrike(id, "agent", "x");

        ProofSnapshot s = ledger.snapshot(id, Instant.now());

        assertThat(s.planAllowsEval()).isTrue();
        assertThat(s.planNote()).isNull();
        assertThat(s.baseline()).isEqualTo(0.60);                 // the first measured score ever
        assertThat(s.noiseFloor()).isEqualTo(0.04);
        assertThat(s.scoreHistory()).extracting(ProofSource.ScorePoint::score).containsExactly(0.85);
        assertThat(s.realRuns()).isEqualTo(12);
        assertThat(s.guardrailStrikes()).isEqualTo(1);
        assertThat(s.ratedTurns()).isEqualTo(12);
        assertThat(s.thumbsUp()).isEqualTo(9);
        assertThat(s.experiment()).isNull();
        assertThat(s.observedSince()).isEqualTo(data.lastSince);
    }

    @Test
    void whenThePlanHasNoEvalRunsTheSnapshotSaysSoAndCarriesNoScores() {
        kit.ports.evalAllowed = false;
        data.runs = List.of(FakeProofData.run("r", Instant.now().plusSeconds(5), 0.99, "PASSED"));
        data.floor = 0.01;

        ProofSnapshot s = ledger.snapshot(id, Instant.now());

        assertThat(s.planAllowsEval()).isFalse();
        assertThat(s.planNote()).contains("not available on your plan");
        assertThat(s.scoreHistory()).isEmpty();
        assertThat(s.baseline()).isNull();
        assertThat(s.noiseFloor()).isNull();
    }

    @Test
    void theObservationWindowRestartsAtTheLastRungChange() {
        kit.proof[0] = new InMemoryProofSource(InMemoryProofSource.strong(EnvoyKit.NOW));
        kit.trust.setRung(id, TrustRung.TRUSTED);
        Instant promotedAt = kit.events.get(0).getCreatedAt();

        assertThat(ledger.snapshot(id, Instant.now()).observedSince()).isEqualTo(promotedAt);
    }

    @Test
    void onlyExperimentsTheBriefStartedCountAsTheExperimentEvidence() {
        data.experiments = List.of(
            new ExperimentRow("e2", "agent", "someone else's", "STOPPED", ago(Duration.ofHours(1)),
                new Variant("c", 80, 60, 95.0, null), new Variant("t", 80, 60, 40.0, null)));
        assertThat(ledger.snapshot(id, Instant.now()).experiment()).isNull();

        data.experiments = List.of(
            new ExperimentRow("e1", "agent", ProofLedgerService.EXPERIMENT_PREFIX + id + " running vs earlier version", "STOPPED",
                Instant.now().plusSeconds(5), new Variant("c", 80, 60, 85.0, null), new Variant("t", 80, 60, 55.0, null)));

        ProofSource.ExperimentResult ex = ledger.snapshot(id, Instant.now()).experiment();
        assertThat(ex).isNotNull();
        assertThat(ex.significant()).isTrue();
        assertThat(ex.delta()).isGreaterThan(0);
    }

    @Test
    void anExperimentFromBeforeTheRungBeganOrStillRunningIsNotEvidence() {
        String name = ProofLedgerService.EXPERIMENT_PREFIX + id + " running vs earlier version";
        data.experiments = List.of(
            new ExperimentRow("old", "agent", name, "STOPPED", ago(Duration.ofDays(30)),
                new Variant("c", 80, 60, 85.0, null), new Variant("t", 80, 60, 55.0, null)));
        assertThat(ledger.snapshot(id, Instant.now()).experiment()).isNull();

        data.experiments = List.of(
            new ExperimentRow("live", "agent", name, "RUNNING", Instant.now().plusSeconds(5),
                new Variant("c", 80, 60, 85.0, null), new Variant("t", 80, 60, 55.0, null)));
        assertThat(ledger.snapshot(id, Instant.now()).experiment()).isNull();
    }

    @Test
    void theTrustLadderPromotesOnTheLedgersEvidenceAndStopsOnAStrike() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ProofSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(ledger);
        TrustLadderService ladder = new TrustLadderService(kit.store, kit.ledger, kit.ports, provider, kit.eventRepository,
            kit.audit, kit.mapper, Clock.fixed(EnvoyKit.NOW, ZoneOffset.UTC));
        data.runs = List.of(FakeProofData.run("r1", Instant.now().plusSeconds(5), 0.82, "PASSED"));
        data.floor = 0.04;
        data.realRuns = 25;
        data.thumbs = new Thumbs(9, 1);

        TrustProposal eligible = ladder.proposal(id);

        assertThat(eligible.eligible()).isTrue();
        assertThat(eligible.targetRung()).isEqualTo("TRUSTED");

        data.recordStrike(id, "agent", "path=input");
        TrustProposal struck = ladder.proposal(id);

        assertThat(struck.eligible()).isFalse();
        assertThat(struck.evidence()).anySatisfy(line -> {
            assertThat(line.key()).isEqualTo("guardrails");
            assertThat(line.met()).isFalse();
        });
    }
}
