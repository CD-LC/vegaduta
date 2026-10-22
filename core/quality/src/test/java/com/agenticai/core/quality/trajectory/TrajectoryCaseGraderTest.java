package com.agenticai.core.quality.trajectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.quality.dataset.AgentTurnReplayer.ReplayedToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrajectoryCaseGraderTest {

    private static final String SPEC =
            "{\"type\":\"trajectory\",\"mode\":\"in_order\",\"steps\":[{\"tool\":\"a\"},{\"tool\":\"b\"}]}";

    private static ReplayedToolCall call(String tool) {
        return new ReplayedToolCall(tool, "{}", "ok");
    }

    @Test
    void aSpecThatIsNotATrajectoryIsLeftAlone() {
        assertNull(TrajectoryCaseGrader.grade(null, List.of(call("a"))));
        assertNull(
                TrajectoryCaseGrader.grade(
                        "{\"type\":\"exact\",\"value\":\"a\"}", List.of(call("a"))));
        assertFalse(
                TrajectoryCaseGrader.isTrajectorySpec("{\"type\":\"regex\",\"pattern\":\"a\"}"));
        assertTrue(TrajectoryCaseGrader.isTrajectorySpec(SPEC));
    }

    @Test
    void aCorruptTrajectorySpecIsNotGradedAndDoesNotThrow() {
        assertNull(
                TrajectoryCaseGrader.grade(
                        "{\"type\":\"trajectory\",\"mode\":\"nope\",\"steps\":[]}",
                        List.of(call("a"))));
        assertNull(TrajectoryCaseGrader.grade("{\"type\":\"trajectory\"}", List.of()));
    }

    @Test
    void gradingProducesTheVerdictScoreAndTheStoredDimension() {
        TrajectoryCaseGrader.Graded ok =
                TrajectoryCaseGrader.grade(SPEC, List.of(call("a"), call("b")));
        assertNotNull(ok);
        assertTrue(ok.passed());
        assertEquals(1.0, ok.score(), 1e-9);
        assertEquals(1.0, ((Number) ok.dimension().get("score")).doubleValue(), 1e-9);
        assertEquals("EVALUATED", ok.dimension().get("status"));
        assertEquals(ok.feedback(), ok.dimension().get("feedback"));
        assertTrue(ok.dimension().get("report") instanceof Map<?, ?>);

        TrajectoryCaseGrader.Graded bad = TrajectoryCaseGrader.grade(SPEC, List.of(call("a")));
        assertFalse(bad.passed());
        assertEquals(0.5, bad.score(), 1e-9);
        assertTrue(bad.feedback().contains("missing: b"), bad.feedback());
    }

    @Test
    void anEmptyReplayIsGradedAsNoCalls() {
        TrajectoryCaseGrader.Graded none = TrajectoryCaseGrader.grade(SPEC, List.of());
        assertFalse(none.passed());
        assertEquals(0.0, none.score(), 1e-9);
    }

    @Test
    void blendCountsJudgeAndTrajectoryEquallyAndFallsBackToTrajectory() {
        assertEquals(0.9, TrajectoryCaseGrader.blend(0.8, 1.0), 1e-9);
        assertEquals(0.25, TrajectoryCaseGrader.blend(0.5, 0.0), 1e-9);
        assertEquals(0.4, TrajectoryCaseGrader.blend(null, 0.4), 1e-9);
    }

    @Test
    void theDimensionIsDetectedInAStoredScoresDocument() throws Exception {
        TrajectoryCaseGrader.Graded g = TrajectoryCaseGrader.grade(SPEC, List.of(call("a")));
        String scores =
                new ObjectMapper()
                        .writeValueAsString(
                                Map.of(
                                        "ACCURACY",
                                        Map.of(
                                                "score",
                                                0.9,
                                                "status",
                                                "EVALUATED",
                                                "feedback",
                                                "fine"),
                                        TrajectoryCaseGrader.DIMENSION,
                                        g.dimension()));
        assertTrue(TrajectoryCaseGrader.hasTrajectoryDimension(scores), scores);
        assertFalse(TrajectoryCaseGrader.hasTrajectoryDimension("{\"ACCURACY\":{\"score\":0.9}}"));
        // The word appearing only inside another dimension's feedback is not the dimension.
        assertFalse(
                TrajectoryCaseGrader.hasTrajectoryDimension(
                        "{\"ACCURACY\":{\"feedback\":\"TRAJECTORY looked odd\"}}"));
        assertFalse(TrajectoryCaseGrader.hasTrajectoryDimension(null));
        assertFalse(TrajectoryCaseGrader.hasTrajectoryDimension(""));
        assertFalse(TrajectoryCaseGrader.hasTrajectoryDimension("not json TRAJECTORY"));
    }
}
