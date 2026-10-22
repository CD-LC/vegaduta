package com.agenticai.core.quality.trajectory;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.quality.dataset.AgentTurnReplayer.ReplayedToolCall;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The seam between the eval runner and trajectory scoring. {@code EvalRunService} calls {@link
 * #grade} once per repetition, folds {@link Graded#passed()} into the same vote the code grader
 * uses, stores {@link Graded#dimension()} next to the judge dimensions in the case's scores
 * document, and blends {@link Graded#score()} into the case's overall score via {@link #blend} -
 * which is how a trajectory result reaches pass^k, the Wilson intervals and paired comparison
 * without those learning a new score type.
 *
 * <p>Everything here is behind {@code app.quality.trajectory-eval.enabled}; the runner does not
 * call in when it is off, and {@link #isTrajectorySpec} lets the code-grader path skip such specs
 * silently either way.
 */
public final class TrajectoryCaseGrader {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryCaseGrader.class);

    /**
     * Key under which the result is stored in a case's scores document (next to {@code ACCURACY},
     * {@code CODE_GRADER}).
     */
    public static final String DIMENSION = "TRAJECTORY";

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private TrajectoryCaseGrader() {}

    /**
     * @param passed the deterministic verdict (score at/above the bar AND the optional hard caps)
     * @param score 0..1, the mode's score
     * @param dimension the {@code {score, status, feedback, report}} map stored in the scores
     *     document
     */
    public record Graded(
            boolean passed, double score, String feedback, Map<String, Object> dimension) {}

    /**
     * True when the stored {@code grader_spec} is a trajectory spec (so the response code-grader
     * must leave it alone).
     */
    public static boolean isTrajectorySpec(String graderSpec) {
        return TrajectorySpec.isTrajectory(graderSpec);
    }

    /**
     * Grades the replayed turn's tool calls against the case's spec. Null when the case has no
     * trajectory spec or the stored spec no longer parses (logged: a corrupt spec must not fail, or
     * silently pass, a case).
     */
    public static Graded grade(String graderSpec, List<ReplayedToolCall> toolCalls) {
        if (!TrajectorySpec.isTrajectory(graderSpec)) {
            return null;
        }
        TrajectorySpec spec;
        try {
            spec = TrajectorySpec.parse(graderSpec);
        } catch (IllegalArgumentException e) {
            log.warn("A case has an unusable trajectory spec - not graded ({})", e.getMessage());
            return null;
        }
        return grade(spec, TrajectoryExtractor.fromReplayed(toolCalls));
    }

    /** Grades already-extracted steps (also what the stored-run views use). */
    public static Graded grade(TrajectorySpec spec, List<TrajectoryToolCall> actual) {
        TrajectoryReport report = TrajectoryEvaluator.evaluate(spec, actual);
        Map<String, Object> dimension = new HashMap<>();
        dimension.put("score", report.score());
        dimension.put("status", "EVALUATED");
        dimension.put("feedback", report.summary());
        dimension.put("report", report.toMap());
        return new Graded(report.passed(), report.score(), report.summary(), dimension);
    }

    /**
     * The case's overall score once a trajectory score exists: the judge aggregate and the
     * trajectory score count equally (the trajectory weighs as much as all judge dimensions
     * combined); with no judge score the trajectory score IS the overall score.
     */
    public static double blend(Double judgeOverall, double trajectoryScore) {
        return judgeOverall == null ? trajectoryScore : (judgeOverall + trajectoryScore) / 2.0;
    }

    /**
     * Whether a stored scores document carries a trajectory dimension (so the case's persisted
     * {@code passed} verdict already folds the trajectory in). False for null, blank or unparseable
     * text.
     */
    public static boolean hasTrajectoryDimension(String scoresJson) {
        if (scoresJson == null || scoresJson.isBlank() || !scoresJson.contains(DIMENSION)) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(scoresJson);
            return root != null && root.isObject() && root.has(DIMENSION);
        } catch (Exception e) {
            return false;
        }
    }
}
