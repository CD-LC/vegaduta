package com.agenticai.core.agents.memorystore.decay;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The importance fed into salience: a stored prior earned by use. With the default weights a hard-coded 0.5 can never
 * reach the forget band (min score 0.225 > 0.12); the attenuated prior can, but only for a fact nobody used.
 */
class FactImportanceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final DecayProperties PROPS = new DecayProperties();

    private static Instant ago(int days) {
        return NOW.minus(Duration.ofDays(days));
    }

    private static double imp(double prior, int uses, int createdDaysAgo, int usedDaysAgo) {
        return FactImportance.effective(
                prior, uses, ago(createdDaysAgo), ago(usedDaysAgo), NOW, PROPS.getHalfLifeDays(), PROPS.getFrequencySaturation());
    }

    private static double salience(double importance, int uses, int createdDaysAgo, int usedDaysAgo, boolean pinned) {
        ItemSnapshot s = new ItemSnapshot(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "fact", MemoryTrustLevel.TRUSTED,
                ItemSnapshot.Status.ACTIVE, pinned, false, importance, uses, ago(createdDaysAgo), ago(usedDaysAgo),
                java.util.List.of(), 0, false, false);
        return new MemorySalience(PROPS, Clock.fixed(NOW, ZoneOffset.UTC)).effective(s);
    }

    @Test
    void aHardCodedHalfCanNeverForgetButTheEarnedPriorCan() {
        // The old snapshot: importance 0.5 whatever happened. A never-used fact idle for 400 days still scores >= 0.225.
        assertThat(salience(0.5, 0, 400, 400, false)).isGreaterThan(PROPS.getForgetThreshold());
        // The earned prior: the same fact (stored prior 0.5, never recalled, idle 400 days) is below the threshold.
        double earned = imp(0.5, 0, 400, 400);
        assertThat(earned).isLessThan(0.01);
        assertThat(salience(earned, 0, 400, 400, false)).isLessThan(PROPS.getForgetThreshold());
    }

    @Test
    void aUsedFactKeepsItsPriorHoweverLongItSitsIdle() {
        // 20 recalls saturate the evidence: the prior survives 400 idle days and the fact is never a forget candidate.
        assertThat(imp(0.5, 20, 500, 400)).isEqualTo(0.5);
        assertThat(salience(imp(0.5, 20, 500, 400), 20, 500, 400, false)).isGreaterThan(PROPS.getForgetThreshold());
        // A few recalls help, one recall barely does.
        assertThat(imp(0.5, 5, 400, 400)).isGreaterThan(imp(0.5, 1, 400, 400));
        assertThat(imp(0.5, 1, 400, 400)).isGreaterThan(imp(0.5, 0, 400, 400));
    }

    @Test
    void aRecentlyUsedFactIsUntouchedByNeglect() {
        assertThat(imp(0.5, 0, 100, 0)).isEqualTo(0.5);
        assertThat(salience(imp(0.5, 3, 100, 2), 3, 100, 2, false)).isGreaterThan(PROPS.getSummariseThreshold());
    }

    @Test
    void neverUsedFactsFadeThroughSummariseThenForgetAsTheyAge() {
        double fresh = salience(imp(0.5, 0, 20, 20), 0, 20, 20, false);
        double mid = salience(imp(0.5, 0, 70, 70), 0, 70, 70, false);
        double old = salience(imp(0.5, 0, 150, 150), 0, 150, 150, false);
        assertThat(fresh).isGreaterThan(PROPS.getSummariseThreshold());
        assertThat(mid).isBetween(PROPS.getForgetThreshold(), PROPS.getSummariseThreshold());
        assertThat(old).isLessThan(PROPS.getForgetThreshold());
    }

    @Test
    void aPinnedFactIsAlwaysFullSalienceWhateverItsImportance() {
        assertThat(salience(0.0, 0, 900, 900, true)).isEqualTo(1.0);
    }

    @Test
    void aHigherPriorStaysAboveALowerOneAndOutOfRangeInputIsClamped() {
        assertThat(imp(0.8, 0, 90, 90)).isGreaterThan(imp(0.3, 0, 90, 90));
        assertThat(imp(5.0, 0, 0, 0)).isEqualTo(1.0);
        assertThat(imp(-1.0, 0, 0, 0)).isEqualTo(0.0);
        assertThat(FactImportance.effective(Double.NaN, 0, ago(0), null, NOW, 45, 20)).isEqualTo(FactImportance.DEFAULT_PRIOR);
    }
}
