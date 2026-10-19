package com.agenticai.core.quality.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.quality.calibration.JudgeCalibration.LabeledPair;
import com.agenticai.core.quality.calibration.JudgeCalibration.PairwiseJudge;
import com.agenticai.core.quality.calibration.JudgeCalibration.Report;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JudgeCalibrationTest {

    /** n examples whose human label alternates A, B, A, B...; answers are "good"/"bad" markers. */
    private static List<LabeledPair> pairs(int n) {
        List<LabeledPair> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            boolean humanA = i % 2 == 0;
            out.add(new LabeledPair("p" + i, "q" + i,
                humanA ? "good" : "bad", humanA ? "bad" : "good", humanA ? "A" : "B"));
        }
        return out;
    }

    /** A judge that always picks the answer text "good", whatever the order. */
    private static final PairwiseJudge CONTENT_JUDGE = (prompt, first, second) -> "good".equals(first) ? 1 : 2;
    /** A judge that always picks whichever answer is shown first. */
    private static final PairwiseJudge POSITION_JUDGE = (prompt, first, second) -> 1;

    @Test
    void aJudgeThatFollowsContentAgreesPerfectlyAndIsOrderConsistent() {
        Report r = JudgeCalibration.evaluate(pairs(30), CONTENT_JUDGE);
        assertEquals(30, r.usable());
        assertEquals(1.0, r.orderSwapConsistency());
        assertEquals(1.0, r.cohenKappa(), 1e-9);
        assertEquals("almost perfect agreement", r.interpretation());
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    void aPositionBiasedJudgeHasZeroConsistencyAndUndefinedKappa() {
        Report r = JudgeCalibration.evaluate(pairs(30), POSITION_JUDGE);
        assertEquals(0.0, r.orderSwapConsistency());
        assertEquals(0, r.coverage());
        assertNull(r.cohenKappa());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("position bias")));
    }

    @Test
    void aJudgeThatDisagreesWithHumansHasNegativeKappa() {
        PairwiseJudge inverse = (prompt, first, second) -> "bad".equals(first) ? 1 : 2;
        Report r = JudgeCalibration.evaluate(pairs(30), inverse);
        assertEquals(1.0, r.orderSwapConsistency());
        assertEquals(-1.0, r.cohenKappa(), 1e-9);
        assertEquals("worse than chance", r.interpretation());
    }

    @Test
    void kappaMatchesTheHandComputedTextbookValue() {
        // 2x2 table [human][judge]: AA=20, AB=5, BA=10, BB=15 -> n=50
        // po = 0.7; humanA = 0.5, judgeA = 0.6; pe = 0.5*0.6 + 0.5*0.4 = 0.5; kappa = 0.4
        Double k = JudgeCalibration.kappa(new int[][] {{20, 5}, {10, 15}}, 50);
        assertNotNull(k);
        assertEquals(0.4, k, 1e-9);
    }

    @Test
    void kappaIsUndefinedWhenEveryoneUsesOneLabel() {
        assertNull(JudgeCalibration.kappa(new int[][] {{10, 0}, {0, 0}}, 10));
        assertNull(JudgeCalibration.kappa(new int[][] {{0, 0}, {0, 0}}, 0));
    }

    @Test
    void unusableVerdictsAreExcludedAndReported() {
        PairwiseJudge flaky = (prompt, first, second) -> {
            if (prompt.equals("q0")) {
                throw new IllegalStateException("provider down");
            }
            return CONTENT_JUDGE.prefer(prompt, first, second);
        };
        Report r = JudgeCalibration.evaluate(pairs(4), flaky);
        assertEquals(4, r.examples());
        assertEquals(3, r.usable());
        assertEquals(1.0, r.orderSwapConsistency());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("no usable verdict")));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("labelled examples")));
    }

    @Test
    void selfPreferenceWarningFiresOnlyWhenModelsMatch() {
        assertNotNull(JudgeCalibration.selfPreferenceWarning("OPENAI/gpt-4o", "gpt-4o"));
        assertNotNull(JudgeCalibration.selfPreferenceWarning("gpt-4o", "OPENAI/GPT-4O"));
        assertNull(JudgeCalibration.selfPreferenceWarning("OPENAI/gpt-4o", "claude-sonnet"));
        assertNull(JudgeCalibration.selfPreferenceWarning(null, "gpt-4o"));
        assertNull(JudgeCalibration.selfPreferenceWarning("gpt-4o", " "));
    }
}
