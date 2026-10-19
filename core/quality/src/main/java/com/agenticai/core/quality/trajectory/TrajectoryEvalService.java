package com.agenticai.core.quality.trajectory;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.quality.dataset.EvalDataset;
import com.agenticai.core.quality.dataset.EvalDatasetCase;
import com.agenticai.core.quality.dataset.EvalDatasetNotFoundException;
import com.agenticai.core.quality.dataset.EvalDatasetService;
import com.agenticai.core.quality.dataset.EvalRunCaseResult;
import com.agenticai.core.quality.dataset.EvalRunService;
import com.agenticai.core.quality.trajectory.TrajectoryEvalDtos.ActualStep;
import com.agenticai.core.quality.trajectory.TrajectoryEvalDtos.ActualTrajectory;
import com.agenticai.core.quality.trajectory.TrajectoryEvalDtos.CaseTrajectory;
import com.agenticai.core.quality.trajectory.TrajectoryEvalDtos.Config;
import com.agenticai.core.quality.trajectory.TrajectoryEvalDtos.RunCaseTrajectory;
import com.agenticai.core.quality.trajectory.TrajectoryEvalDtos.RunTrajectories;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads and writes the expected trajectory of a dataset case and serves the expected-vs-actual views of a
 * finished run. The trajectory is stored as the case's {@code grader_spec} JSON ({@link TrajectorySpec}), so
 * every write goes through {@code EvalDatasetService#setCaseGrader} - same validation, same content-hash refresh,
 * same access rules as every other case edit.
 *
 * <p>Everything answers 404 while {@code app.quality.trajectory-eval.enabled} is off (the console hides itself),
 * except {@link #config()}, which reports {@code enabled: false} so the console can ask.
 */
@Service
public class TrajectoryEvalService {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final EvalDatasetService datasetService;
    private final EvalRunService runService;
    private final boolean enabled;

    public TrajectoryEvalService(
        EvalDatasetService datasetService,
        EvalRunService runService,
        @Value("${app.quality.trajectory-eval.enabled:false}") boolean enabled
    ) {
        this.datasetService = datasetService;
        this.runService = runService;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Config config() {
        List<String> modes = new ArrayList<>();
        Arrays.stream(TrajectorySpec.Mode.values()).forEach(m -> modes.add(m.wire()));
        return new Config(enabled, modes, TrajectorySpec.MAX_STEPS, TrajectorySpec.MAX_ARGS_PER_STEP,
            TrajectoryEvaluator.MAX_ACTUAL_STEPS);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Trajectory evaluation is not enabled");
        }
    }

    // ------------------------------------------------------------------
    // The expected trajectory of a case
    // ------------------------------------------------------------------

    public CaseTrajectory getCase(UUID agentId, UUID datasetId, UUID caseId) {
        requireEnabled();
        EvalDataset dataset = datasetService.requireDataset(agentId, datasetId);
        EvalDatasetCase datasetCase = requireCase(datasetId, caseId);
        return describe(datasetCase, dataset.getContentHash());
    }

    /**
     * Sets the case's expected trajectory, or clears it with a null body value.
     *
     * @throws IllegalArgumentException (400) when the spec is invalid
     * @throws ResponseStatusException 409 when the case already carries a response code grader
     */
    public CaseTrajectory setCase(UUID agentId, UUID datasetId, UUID caseId, JsonNode trajectory) {
        requireEnabled();
        EvalDataset dataset = datasetService.requireDataset(agentId, datasetId);
        EvalDatasetCase existing = requireCase(datasetId, caseId);
        boolean storedIsTrajectory = TrajectorySpec.isTrajectory(existing.getGraderSpec());
        boolean storedIsOther = existing.getGraderSpec() != null && !storedIsTrajectory;

        if (trajectory == null || trajectory.isNull()) {
            if (!storedIsTrajectory) {
                // Nothing of ours to clear; never delete a response grader from here.
                return describe(existing, dataset.getContentHash());
            }
            EvalDatasetCase cleared = datasetService.setCaseGrader(agentId, datasetId, caseId, null);
            return describe(cleared, datasetService.requireDataset(agentId, datasetId).getContentHash());
        }
        if (!trajectory.isObject()) {
            throw new IllegalArgumentException("'trajectory' must be a JSON object.");
        }
        if (storedIsOther) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This case already has a response grader (regex / exact / json_schema). A case holds one grader - "
                    + "clear that one first, then save the expected trajectory.");
        }
        ObjectNode copy = ((ObjectNode) trajectory).deepCopy();
        if (!copy.has("type")) {
            copy.put("type", TrajectorySpec.TYPE);
        }
        // Validate here for a precise 400, then store the canonical form (defaults made explicit).
        TrajectorySpec spec = TrajectorySpec.parse(copy);
        EvalDatasetCase saved = datasetService.setCaseGrader(agentId, datasetId, caseId, spec.toJson());
        return describe(saved, datasetService.requireDataset(agentId, datasetId).getContentHash());
    }

    private CaseTrajectory describe(EvalDatasetCase datasetCase, String datasetHash) {
        String stored = datasetCase.getGraderSpec();
        boolean isTrajectory = TrajectorySpec.isTrajectory(stored);
        JsonNode node = null;
        if (isTrajectory) {
            try {
                node = MAPPER.readTree(stored);
            } catch (Exception e) {
                node = null;
            }
        }
        return new CaseTrajectory(datasetCase.getId(), node, stored != null && !isTrajectory, datasetHash);
    }

    private EvalDatasetCase requireCase(UUID datasetId, UUID caseId) {
        return datasetService.listCases(datasetId).stream()
            .filter(c -> c.getId().equals(caseId))
            .findFirst()
            .orElseThrow(() -> new EvalDatasetNotFoundException(caseId));
    }

    // ------------------------------------------------------------------
    // Views of a finished run
    // ------------------------------------------------------------------

    /** The trajectory-graded cases of a run, each with its stored metrics and alignment report. */
    public RunTrajectories runTrajectories(UUID agentId, UUID datasetId, UUID runId) {
        requireEnabled();
        datasetService.requireDataset(agentId, datasetId);
        runService.requireRun(datasetId, runId);
        List<RunCaseTrajectory> cases = new ArrayList<>();
        for (EvalRunCaseResult result : runService.listCaseResults(runId)) {
            if (!TrajectoryCaseGrader.hasTrajectoryDimension(result.getScoresJson())) {
                continue;
            }
            JsonNode dimension = dimensionOf(result.getScoresJson());
            JsonNode report = dimension == null || dimension.path("report").isMissingNode()
                ? null : dimension.get("report");
            Double score = dimension != null && dimension.path("score").isNumber()
                ? dimension.get("score").asDouble() : null;
            cases.add(new RunCaseTrajectory(
                result.getId(), result.getCaseId(), result.getPosition(), result.getUserMessage(),
                result.getStatus().name(), result.getPassed(), result.getOverallScore(), score, report));
        }
        return new RunTrajectories(runId, cases);
    }

    /** The trajectory one stored case result actually produced, extracted from its recorded tool calls. */
    public ActualTrajectory actual(UUID agentId, UUID datasetId, UUID runId, UUID resultId) {
        requireEnabled();
        datasetService.requireDataset(agentId, datasetId);
        runService.requireRun(datasetId, runId);
        EvalRunCaseResult result = runService.listCaseResults(runId).stream()
            .filter(r -> r.getId().equals(resultId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case result not found for this run"));
        List<TrajectoryToolCall> steps = TrajectoryExtractor.fromToolCallsJson(result.getToolCallsJson());
        List<ActualStep> shown = new ArrayList<>();
        ObjectNode suggested = MAPPER.createObjectNode();
        suggested.put("type", TrajectorySpec.TYPE);
        suggested.put("mode", TrajectorySpec.Mode.IN_ORDER.wire());
        suggested.put("minScore", 1.0);
        ArrayNode suggestedSteps = suggested.putArray("steps");
        for (TrajectoryToolCall s : steps) {
            shown.add(new ActualStep(s.tool(), s.args().isMissingNode() ? null : s.args(), s.ok()));
            if (suggestedSteps.size() < TrajectorySpec.MAX_STEPS && !s.tool().isBlank()) {
                suggestedSteps.addObject().put("tool", s.tool());
            }
        }
        return new ActualTrajectory(resultId, shown, suggested);
    }

    private static JsonNode dimensionOf(String scoresJson) {
        try {
            JsonNode root = MAPPER.readTree(scoresJson);
            return root == null ? null : root.get(TrajectoryCaseGrader.DIMENSION);
        } catch (Exception e) {
            return null;
        }
    }
}
