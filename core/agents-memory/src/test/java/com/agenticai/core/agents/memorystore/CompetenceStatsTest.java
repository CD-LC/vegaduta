package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Reference values are the standard 95% Wilson lower bounds (same ones core/quality's
 * WilsonIntervalTest pins).
 */
class CompetenceStatsTest {

    @Test
    void matchesReferenceValues() {
        assertThat(CompetenceStats.wilsonLowerBound(10, 10)).isCloseTo(0.7225, within(0.001));
        assertThat(CompetenceStats.wilsonLowerBound(5, 10)).isCloseTo(0.2366, within(0.001));
        assertThat(CompetenceStats.wilsonLowerBound(0, 10)).isCloseTo(0.0, within(1e-9));
        assertThat(CompetenceStats.wilsonLowerBound(80, 100)).isCloseTo(0.7112, within(0.001));
    }

    @Test
    void noSampleMeansNoConfidence() {
        assertThat(CompetenceStats.wilsonLowerBound(0, 0)).isZero();
        assertThat(CompetenceStats.wilsonLowerBound(5, 0)).isZero();
    }

    @Test
    void aPerfectSmallSampleIsFarBelowOne() {
        assertThat(CompetenceStats.wilsonLowerBound(3, 3)).isLessThan(0.5);
        assertThat(CompetenceStats.wilsonLowerBound(300, 300)).isGreaterThan(0.98);
    }

    @Test
    void unknownIsNeverInTheSample() {
        CompetenceRow r = new CompetenceRow("x", 100, 3, 1, 0, 0, 96, null);
        assertThat(r.verifiedSample()).isEqualTo(4);
        assertThat(r.successRate()).isEqualTo(0.75);
    }
}
