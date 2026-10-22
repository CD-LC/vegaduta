package com.agenticai.core.quality.trajectory;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Wire shapes of {@code /api/agents/{agentId}/quality/trajectory-eval}. */
public final class TrajectoryEvalDtos {

    private TrajectoryEvalDtos() {}

    /**
     * What the console needs to decide whether to offer the feature and how to validate input
     * before a PUT.
     */
    public record Config(
            boolean enabled,
            List<String> modes,
            int maxSteps,
            int maxArgsPerStep,
            int maxActualSteps) {}

    /**
     * @param trajectory the stored trajectory spec ({@code type}, {@code mode}, {@code steps},
     *     ...), or null
     * @param hasOtherGrader true when the case carries a response code grader instead (regex /
     *     exact / json_schema): a case holds one grader, so saving a trajectory is refused until
     *     that is cleared
     * @param datasetContentHash the dataset's hash after any change - a trajectory edit changes
     *     what a run measures
     */
    public record CaseTrajectory(
            UUID caseId, JsonNode trajectory, boolean hasOtherGrader, String datasetContentHash) {}

    /** {@code trajectory} null (or absent) clears the case's trajectory. */
    public record SetCaseRequest(JsonNode trajectory) {}

    /**
     * One case result that was trajectory-graded.
     *
     * @param trajectoryScore the trajectory dimension's own 0..1 score (the case's overall score
     *     blends it with the judge scores)
     * @param report the stored report: metrics plus the expected-vs-actual alignment rows
     */
    public record RunCaseTrajectory(
            UUID resultId,
            UUID caseId,
            int position,
            String userMessage,
            String status,
            Boolean passed,
            Double overallScore,
            Double trajectoryScore,
            JsonNode report) {}

    public record RunTrajectories(UUID runId, List<RunCaseTrajectory> cases) {}

    /** {@code args} is null when the recorded input was not JSON. */
    public record ActualStep(String tool, JsonNode args, boolean ok) {}

    /**
     * The trajectory a stored case result actually produced, plus a ready-to-save spec made of its
     * tool names (no argument matchers - add those by hand) for the editor's "use this as the
     * expected trajectory".
     */
    public record ActualTrajectory(UUID resultId, List<ActualStep> steps, JsonNode suggestedSpec) {}
}
