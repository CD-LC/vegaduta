package com.agenticai.core.quality.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Suite-level reliability over a hand-built dataset whose every expected number
 * is computed in the comments from the definitions.
 *
 * <p>The headline case below is the one from the curriculum: a suite that reports
 * a respectable pass@1 and a dismal pass^k. If a refactor ever makes those two
 * numbers agree, this test fails - which is the point, because them agreeing is
 * the bug the whole package exists to prevent.
 */
class ReliabilityStatsTest {

    private static final long SEED = 20260911L;

    /**
     * Four cases, five rollouts each:
     * <pre>
     *   A: 5/5   B: 4/5   C: 3/5   D: 0/5
     * </pre>
     * so 12 of 20 rollouts passed.
     */
    private static List<CaseRollouts> fourCasesFiveRollouts() {
        return List.of(
            CaseRollouts.of("A", 5, 5),
            CaseRollouts.of("B", 4, 5),
            CaseRollouts.of("C", 3, 5),
            CaseRollouts.of("D", 0, 5));
    }

    @Test
    void passAtOneIsPooledOverRolloutsAndCarriesAWilsonInterval() {
        ReliabilityStats.SuiteReliability suite = ReliabilityStats.summarize(
            fourCasesFiveRollouts(), 0.95, Bootstrap.DEFAULT_RESAMPLES, SEED);

        // 12 of 20 rollouts passed.
        assertEquals(20, suite.rollouts());
        assertEquals(12, suite.passedRollouts());
        assertEquals(0.6, suite.passAtOne(), 1e-12);

        // Wilson 95% for 12/20 is roughly [0.386, 0.781] - the assertion that
        // matters is that the headline 0.6 is NOT a point estimate any more.
        assertEquals(IntervalMethod.WILSON_SCORE, suite.passAtOneInterval().method());
        assertTrue(suite.passAtOneInterval().contains(0.6));
        assertTrue(suite.passAtOneInterval().width() > 0.3,
            "20 rollouts is a small sample and the interval must say so");
    }

    @Test
    void passHatKIsTheOperationsNumberAndIsFarWorseThanPassAtOne() {
        ReliabilityStats.SuiteReliability suite = ReliabilityStats.summarize(
            fourCasesFiveRollouts(), 0.95, Bootstrap.DEFAULT_RESAMPLES, SEED);

        // k = min rollouts per case = 5.
        assertEquals(5, suite.k());

        // Per case, pass^5 = C(c,5)/C(5,5), which is 1 only when c = 5:
        // A -> 1, B -> 0, C -> 0, D -> 0, so the suite average is 1/4.
        assertEquals(0.25, suite.passHatK(), 1e-12);
        assertEquals(1, suite.fullyPassedCases());

        // Per case, pass@5 = 1 - C(5-c,5)/C(5,5), which is 1 unless c = 0:
        // A, B, C -> 1, D -> 0, so the suite average is 3/4.
        assertEquals(0.75, suite.passAtK(), 1e-12);

        // The number a customer actually buys versus the number a dashboard
        // shows today: 0.60 becomes 0.25.
        assertEquals(0.35, suite.reliabilityGap(), 1e-12);
        assertTrue(suite.passAtK() > suite.passAtOne(),
            "pass@k must flatter and pass^k must not - that asymmetry is the lesson");
        assertTrue(suite.passHatK() < suite.passAtOne());
    }

    @Test
    void bothCaseAveragedMetricsCarryBootstrapIntervals() {
        ReliabilityStats.SuiteReliability suite = ReliabilityStats.summarize(
            fourCasesFiveRollouts(), 0.95, Bootstrap.DEFAULT_RESAMPLES, SEED);

        assertNotNull(suite.passHatKInterval());
        assertNotNull(suite.passAtKInterval());
        assertEquals(IntervalMethod.PERCENTILE_BOOTSTRAP, suite.passHatKInterval().method());
        assertTrue(suite.passHatKInterval().contains(0.25),
            "the interval must cover its own point estimate on a four-case suite");
        assertFalse(suite.unreplicated());
    }

    @Test
    void aSingleCaseHasNoCaseLevelIntervalButStillHasAWilsonOne() {
        List<CaseRollouts> single = List.of(CaseRollouts.of("only", 3, 5));

        ReliabilityStats.SuiteReliability suite = ReliabilityStats.summarize(
            single, 0.95, Bootstrap.DEFAULT_RESAMPLES, SEED);

        assertNull(suite.passHatKInterval(),
            "one case cannot be resampled at the case level - the absence must be explicit, "
                + "never a zero-width interval");
        assertNull(suite.passAtKInterval());
        assertNotNull(suite.passAtOneInterval());
        assertEquals(0.6, suite.passAtOne(), 1e-12);
    }

    /**
     * The worst realistic shape: one case that has never been repeated drags the
     * whole suite's reportable k down to 1, and the summary must say so instead
     * of quietly reporting a flattering k from the other cases.
     */
    @Test
    void oneUnreplicatedCaseCollapsesKToOneAndSetsTheUnreplicatedFlag() {
        List<CaseRollouts> ragged = List.of(
            CaseRollouts.of("well-replicated", 4, 5),
            CaseRollouts.of("brand-new", 1, 1));

        ReliabilityStats.SuiteReliability suite = ReliabilityStats.summarize(
            ragged, 0.95, Bootstrap.DEFAULT_RESAMPLES, SEED);

        assertEquals(1, suite.k());
        assertEquals(1, suite.minRolloutsPerCase());
        assertEquals(5, suite.maxRolloutsPerCase());
        assertTrue(suite.unreplicated());

        // At k=1 both reliability metrics degenerate to the MEAN OF PER-CASE
        // pass rates (macro): (4/5 + 1/1)/2 = 0.9.
        assertEquals(0.9, suite.passHatK(), 1e-12);
        assertEquals(0.9, suite.passAtK(), 1e-12);

        // pass@1 is pooled over ROLLOUTS (micro): 5 of 6 = 0.8333. The two
        // weightings deliberately differ (see ReliabilityStats' javadoc), which
        // is why the "gap" can even go slightly negative at k=1 - another way of
        // saying there is no reliability information at k=1 to read off it.
        assertEquals(5.0 / 6.0, suite.passAtOne(), 1e-12);
        assertEquals(5.0 / 6.0 - 0.9, suite.reliabilityGap(), 1e-12);
    }

    @Test
    void aPerfectSuiteStillCarriesUncertainty() {
        List<CaseRollouts> perfect = List.of(
            CaseRollouts.of("A", 3, 3),
            CaseRollouts.of("B", 3, 3),
            CaseRollouts.of("C", 3, 3));

        ReliabilityStats.SuiteReliability suite = ReliabilityStats.summarize(
            perfect, 0.95, Bootstrap.DEFAULT_RESAMPLES, SEED);

        assertEquals(1.0, suite.passAtOne(), 1e-12);
        assertEquals(1.0, suite.passHatK(), 1e-12);
        assertEquals(1.0, suite.passAtOneInterval().high(), 1e-12);
        assertTrue(suite.passAtOneInterval().low() < 0.8,
            "nine perfect rollouts do not establish perfection - the lower bound must be well "
                + "below 1.0");
    }

    @Test
    void theSameSeedProducesTheSameSummary() {
        ReliabilityStats.SuiteReliability first = ReliabilityStats.summarize(
            fourCasesFiveRollouts(), 0.95, Bootstrap.DEFAULT_RESAMPLES, 7L);
        ReliabilityStats.SuiteReliability second = ReliabilityStats.summarize(
            fourCasesFiveRollouts(), 0.95, Bootstrap.DEFAULT_RESAMPLES, 7L);

        assertEquals(first, second);
    }

    @Test
    void anEmptySuiteIsRefusedRatherThanScoredZero() {
        assertThrows(
            IllegalArgumentException.class, () -> ReliabilityStats.summarize(List.of(), SEED));
    }

    @Test
    void caseRolloutsRejectsNullAndEmptyOutcomeVectors() {
        assertThrows(IllegalArgumentException.class, () -> new CaseRollouts("a", List.of()));
        assertThrows(
            IllegalArgumentException.class,
            () -> new CaseRollouts("a", java.util.Arrays.asList(Boolean.TRUE, null)));
        assertThrows(IllegalArgumentException.class, () -> new CaseRollouts("  ", List.of(Boolean.TRUE)));
    }
}
