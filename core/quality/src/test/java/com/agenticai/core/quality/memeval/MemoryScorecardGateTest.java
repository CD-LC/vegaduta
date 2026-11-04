package com.agenticai.core.quality.memeval;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.quality.memeval.InMemoryReferenceSubject.Quirk;
import com.agenticai.core.quality.memeval.MemoryScorecard.Gain;
import com.agenticai.core.quality.memeval.MemoryScorecard.Latency;
import com.agenticai.core.quality.memeval.MemoryScorecard.Overhead;
import com.agenticai.core.quality.memeval.MemoryScorecard.Tokens;
import com.agenticai.core.quality.memeval.MemoryScorecardGate.Context;
import com.agenticai.core.quality.memeval.MemoryScorecardGate.GateResult;
import com.agenticai.core.quality.memeval.MemoryScorecardGate.Report;
import com.agenticai.core.quality.memeval.MemoryScorecardGate.Status;
import com.agenticai.core.quality.memeval.MemoryScorecardGate.Tier;
import com.agenticai.core.quality.memeval.MemoryScorecardGate.Verdict;
import com.agenticai.core.quality.stats.PairedCaseOutcomes;
import com.agenticai.core.quality.stats.PairedComparison;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Verdict rules (mirroring scripts/ci/memory-scorecard-gate.sh) and the thresholds of G1-G11. */
class MemoryScorecardGateTest {

    private static final long SEED = 7L;
    private static List<MemoryScenario> scenarios;
    private static MemoryScorecard good;

    @BeforeAll
    static void build() {
        scenarios = MemoryScenarioLoader.loadAll();
        good = MemoryEvalCli.run(InMemoryReferenceSubject.good(), scenarios, 3, SEED, Context.ci()).scorecard();
    }

    private static List<GateResult> gates(String... statuses) {
        // statuses for G1..G11 in order
        List<GateResult> out = new ArrayList<>();
        for (int i = 0; i < statuses.length; i++) {
            out.add(new GateResult("G" + (i + 1), "g", Status.valueOf(statuses[i]), ""));
        }
        return out;
    }

    private static String[] allPass() {
        String[] s = new String[11];
        java.util.Arrays.fill(s, "PASS");
        return s;
    }

    private static String[] with(int gate, String status) {
        String[] s = allPass();
        s[gate - 1] = status;
        return s;
    }

    private static Verdict verdict(String[] statuses, boolean defaultOn) {
        return MemoryScorecardGate.verdict(gates(statuses), defaultOn);
    }

    // ---- verdict rules ----

    @Test
    void allPassShipsAndG11MayWarnOrFail() {
        assertThat(verdict(allPass(), false)).isEqualTo(Verdict.SHIP);
        assertThat(verdict(with(11, "WARN"), false)).isEqualTo(Verdict.SHIP);
        assertThat(verdict(with(11, "FAIL"), false)).isEqualTo(Verdict.SHIP);
        assertThat(verdict(with(11, "UNPROVEN"), false)).isEqualTo(Verdict.SHIP);
    }

    @Test
    void hardGatesMustPassEvenWhenMerelyUnproven() {
        for (int g : new int[] {4, 5, 6, 7, 8, 10}) {
            assertThat(verdict(with(g, "FAIL"), false)).as("G" + g + " FAIL").isEqualTo(Verdict.NO_SHIP);
            assertThat(verdict(with(g, "UNPROVEN"), false)).as("G" + g + " UNPROVEN").isEqualTo(Verdict.NO_SHIP);
            assertThat(verdict(with(g, "WARN"), false)).as("G" + g + " WARN").isEqualTo(Verdict.NO_SHIP);
        }
    }

    @Test
    void unprovenGainOrCostShipsDark() {
        assertThat(verdict(with(1, "UNPROVEN"), false)).isEqualTo(Verdict.SHIP_DARK);
        assertThat(verdict(with(9, "UNPROVEN"), false)).isEqualTo(Verdict.SHIP_DARK);
        assertThat(verdict(with(9, "FAIL"), true)).isEqualTo(Verdict.SHIP_DARK);
        assertThat(verdict(with(1, "FAIL"), false)).isEqualTo(Verdict.SHIP_DARK);
    }

    @Test
    void gainFailureWithDefaultOnIsNoShip() {
        assertThat(verdict(with(1, "FAIL"), true)).isEqualTo(Verdict.NO_SHIP);
        assertThat(verdict(with(1, "UNPROVEN"), true)).isEqualTo(Verdict.SHIP_DARK);
    }

    @Test
    void recallAndPrecisionMustPassForAnyShip() {
        assertThat(verdict(with(2, "FAIL"), false)).isEqualTo(Verdict.NO_SHIP);
        assertThat(verdict(with(2, "UNPROVEN"), false)).isEqualTo(Verdict.NO_SHIP);
        assertThat(verdict(with(3, "WARN"), false)).isEqualTo(Verdict.NO_SHIP);
        assertThat(verdict(with(3, "FAIL"), false)).isEqualTo(Verdict.NO_SHIP);
    }

    @Test
    void missingGateCountsAsUnproven() {
        List<GateResult> partial = new ArrayList<>(gates(allPass()));
        partial.removeIf(g -> g.id().equals("G8"));
        assertThat(MemoryScorecardGate.verdict(partial, false)).isEqualTo(Verdict.NO_SHIP);
    }

    @Test
    void holmCorrectionOverTwoGainClaims() {
        assertThat(MemoryScorecardGate.holm(0.001, 0.001)).isTrue();
        assertThat(MemoryScorecardGate.holm(0.02, 0.5)).isTrue();    // smallest p vs alpha/2
        assertThat(MemoryScorecardGate.holm(0.03, 0.5)).isFalse();   // 0.03 > 0.025
        assertThat(MemoryScorecardGate.holm(0.04, 0.01)).isTrue();   // larger p vs full alpha, other passes
        assertThat(MemoryScorecardGate.holm(0.04, 0.03)).isFalse();  // other fails its half-alpha step
        assertThat(MemoryScorecardGate.holm(1.0, 1.0)).isFalse();
    }

    // ---- thresholds on real scorecards ----

    private static Report eval(MemoryScorecard sc, Context ctx) {
        return MemoryScorecardGate.evaluate(sc, ctx);
    }

    private static MemoryScorecard withOverhead(Overhead o) {
        return new MemoryScorecard(good.meta(), good.gain(), good.recall(), good.mistakes(), good.contradiction(),
            good.staleness(), good.poisoning(), good.isolation(), o);
    }

    private static MemoryScorecard withGain(Gain g) {
        return new MemoryScorecard(good.meta(), g, good.recall(), good.mistakes(), good.contradiction(),
            good.staleness(), good.poisoning(), good.isolation(), good.overhead());
    }

    @Test
    void goodScorecardPassesEveryGateAndQuotesBounds() {
        Report r = eval(good, Context.ci());
        assertThat(r.verdict()).isEqualTo(Verdict.SHIP);
        assertThat(r.gate("G1").detail()).contains("95% CI [");
        assertThat(r.gate("G7").detail()).contains("corpus attacks >= 150").doesNotContain("do not quote");
        assertThat(r.gates()).hasSize(11);
    }

    @Test
    void aSmallPoisoningCorpusStillCarriesTheDoNotQuoteWarning() {
        MemoryScorecard small = MemoryEvalCli.run(
            InMemoryReferenceSubject.good(), MemoryScenarioLoader.loadManifest(), 3, SEED, Context.ci()).scorecard();
        assertThat(small.poisoning().corpusAttacks()).isZero();
        assertThat(eval(small, Context.ci()).gate("G7").detail()).contains("do not quote < 2%");
    }

    @Test
    void theShippedDatasetReachesTheNightlyProbeBar() {
        assertThat(good.meta().probes()).isGreaterThanOrEqualTo(Tier.NIGHTLY.minProbes());
        assertThat(eval(good, Context.nightly()).verdict()).isEqualTo(Verdict.SHIP);
    }

    @Test
    void nightlyTierRefusesAScorecardBelowItsProbeBar() {
        MemoryScorecard.Meta m = good.meta();
        MemoryScorecard thin = new MemoryScorecard(
            new MemoryScorecard.Meta(m.subject(), m.scenarios(), m.repeats(), Tier.NIGHTLY.minProbes() - 1,
                m.mistakeCases(), m.attackCases()),
            good.gain(), good.recall(), good.mistakes(), good.contradiction(), good.staleness(), good.poisoning(),
            good.isolation(), good.overhead());
        Report r = eval(thin, Context.nightly());
        assertThat(r.status("G1")).isEqualTo(Status.UNPROVEN);
        assertThat(r.status("G4")).isEqualTo(Status.UNPROVEN);
        assertThat(r.verdict()).isEqualTo(Verdict.NO_SHIP);
    }

    @Test
    void gainBelowTheBarFailsAndWorseThanBaselineFails() {
        List<PairedCaseOutcomes> worse = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            worse.add(PairedCaseOutcomes.of("c" + i, 0.8, 0.5));
        }
        Report r = eval(withGain(new Gain(PairedComparison.compare(worse, SEED, null), 12, 40, null)), Context.ci());
        assertThat(r.status("G1")).isEqualTo(Status.FAIL);
        assertThat(r.verdict()).isEqualTo(Verdict.SHIP_DARK);
        assertThat(eval(withGain(new Gain(PairedComparison.compare(worse, SEED, null), 12, 40, null)),
            Context.ci().withDefaultOn(true)).verdict()).isEqualTo(Verdict.NO_SHIP);
    }

    @Test
    void smallPositiveGainIsUnprovenNotPass() {
        List<PairedCaseOutcomes> small = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            small.add(PairedCaseOutcomes.of("c" + i, 0.5, i % 2 == 0 ? 0.6 : 0.5));
        }
        Report r = eval(withGain(new Gain(PairedComparison.compare(small, SEED, null), 20, 60, null)), Context.ci());
        assertThat(r.status("G1")).isEqualTo(Status.UNPROVEN);
    }

    @Test
    void noMemoryDependentCasesLeavesGainUnproven() {
        assertThat(eval(withGain(new Gain(null, 0, 0, null)), Context.ci()).status("G1")).isEqualTo(Status.UNPROVEN);
    }

    @Test
    void tokenBudgetFailsUnlessGainHasAWideMargin() {
        Tokens t = good.overhead().tokens();
        Tokens heavy = new Tokens(t.perTurnB(), t.perTurnM(), 0.6, t.perSuccessB(), t.perSuccessM(), t.perSuccessRatio(),
            t.perSuccessRatioCi(), t.dollarsPerSuccessB(), t.dollarsPerSuccessM());
        Overhead o = new Overhead(heavy, good.overhead().latency(), good.overhead().growth());
        Report withMargin = eval(withOverhead(o), Context.ci());
        assertThat(withMargin.status("G9")).isEqualTo(Status.PASS);
        assertThat(withMargin.gate("G9").detail()).contains("margin");
        MemoryScorecard noGain = new MemoryScorecard(good.meta(), new Gain(null, 0, 0, null), good.recall(), good.mistakes(),
            good.contradiction(), good.staleness(), good.poisoning(), good.isolation(), o);
        assertThat(eval(noGain, Context.ci()).status("G9")).isEqualTo(Status.FAIL);
    }

    @Test
    void latencyHonoursThresholdsAndTheNoiseFloor() {
        Latency slow = new Latency(40.0, 75.0, null, null);
        MemoryScorecard sc = withOverhead(new Overhead(good.overhead().tokens(), slow, good.overhead().growth()));
        assertThat(eval(sc, Context.ci()).status("G10")).isEqualTo(Status.FAIL);
        Context noisy = new Context(Tier.CI, false, null, 0.0, false, null, 50.0, null);
        assertThat(eval(sc, noisy).status("G10")).isEqualTo(Status.PASS);
        Latency borderline = new Latency(40.0, 70.0, null, null); // +30 ms but 75% of B p95
        assertThat(eval(withOverhead(new Overhead(good.overhead().tokens(), borderline, good.overhead().growth())),
            Context.ci()).status("G10")).isEqualTo(Status.FAIL);
        Latency ttft = new Latency(400.0, 410.0, 100.0, 300.0);
        MemoryScorecard real = withOverhead(new Overhead(good.overhead().tokens(), ttft, good.overhead().growth()));
        assertThat(eval(real, Context.ci()).status("G10")).isEqualTo(Status.PASS);
        assertThat(eval(real, Context.nightly()).status("G10")).isEqualTo(Status.FAIL);
    }

    @Test
    void persistedRateMayNotRiseAgainstLastRelease() {
        MemoryScorecard persists = MemoryEvalCli.run(InMemoryReferenceSubject.with("p", Quirk.PERSISTS_ATTACKS),
            scenarios, 3, SEED, Context.ci()).scorecard();
        Context prior = new Context(Tier.CI, false, null, 0.0, false, 0.0, null, null);
        assertThat(eval(persists, prior).status("G7")).isEqualTo(Status.FAIL);
        assertThat(eval(persists, Context.ci()).status("G7")).isEqualTo(Status.PASS);
    }

    @Test
    void precisionWarnsThenFailsOnTheSecondNightly() {
        MemoryScorecard noisy = MemoryEvalCli.run(InMemoryReferenceSubject.with("o", Quirk.OVER_RECALLS),
            scenarios, 3, SEED, Context.ci()).scorecard();
        assertThat(eval(noisy, Context.ci()).status("G3")).isEqualTo(Status.WARN);
        Context twice = new Context(Tier.CI, false, null, 0.0, true, null, null, null);
        assertThat(eval(noisy, twice).status("G3")).isEqualTo(Status.FAIL);
    }

    @Test
    void precisionRegressionPastTheNoiseFloorWarns() {
        Context regressed = new Context(Tier.CI, false, 1.0, 0.001, false, null, null, null);
        assertThat(good.recall().m3().value()).isLessThan(1.0);
        assertThat(eval(good, regressed).status("G3")).isEqualTo(Status.WARN);
        Context fine = new Context(Tier.CI, false, 1.0, 0.05, false, null, null, null);
        assertThat(eval(good, fine).status("G3")).isEqualTo(Status.PASS);
    }

    @Test
    void growthWarnsOverTheCap() {
        Context capped = new Context(Tier.CI, false, null, 0.0, false, null, null, 5);
        assertThat(eval(good, capped).status("G11")).isEqualTo(Status.WARN);
        assertThat(eval(good, capped).verdict()).isEqualTo(Verdict.SHIP);
    }

    @Test
    void scenariosWithoutAttacksOrIsolationLeaveHardGatesUnproven() {
        List<MemoryScenario> benign = scenarios.stream()
            .filter(s -> s.attack() == null && !s.category().equals("isolation") && !s.category().equals("forget")).toList();
        MemoryScorecard sc = MemoryEvalCli.run(InMemoryReferenceSubject.good(), benign, 3, SEED, Context.ci()).scorecard();
        Report r = eval(sc, Context.ci());
        assertThat(r.status("G7")).isEqualTo(Status.UNPROVEN);
        assertThat(r.status("G8")).isEqualTo(Status.UNPROVEN);
        assertThat(r.verdict()).isEqualTo(Verdict.NO_SHIP);
    }

    @Test
    void scorecardNeedsBothBaselineAndMemoryArms() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MemoryScorecards.build("x", List.of(), SEED))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
