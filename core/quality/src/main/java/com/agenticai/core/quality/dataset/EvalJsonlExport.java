package com.agenticai.core.quality.dataset;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NDJSON (JSON Lines) renderings of eval data - the training/optimization
 * export counterpart to {@link EvalRunBundle} (which is the tamper-evident
 * audit artifact). One JSON object per line, UTF-8, no outer array, so the
 * file streams straight into offline tooling (DSPy/GEPA-style optimizers,
 * pandas, jq) without a wrapper parse.
 *
 * <p>Two schemas, both versioned in the header line's {@code schema} field:
 * <ul>
 *   <li>{@code vegaduta.eval-run-jsonl/v1} - line 1 is the run header
 *       ({@code "type":"run"}) carrying the same provenance the bundle signs
 *       (agent version binding, config hash, dataset hash, judge model,
 *       repeats, budget/cost); every following line is one case result
 *       ({@code "type":"case"}).</li>
 *   <li>{@code vegaduta.eval-dataset-jsonl/v1} - line 1 is the dataset
 *       header; following lines are the LIVE cases ({@code input},
 *       {@code expectedOutput}, {@code expectedTools}, {@code grader}) in
 *       position order - the optimization loop's candidate training set.</li>
 * </ul>
 *
 * <p>JSON document columns ({@code scores_json}, {@code tool_calls_json},
 * {@code grader_spec}) are embedded as parsed values, never stringified a
 * second time - an export that double-encodes makes every downstream consumer
 * write an unparse step. An unreadable column is exported as its raw string
 * under a {@code *Raw} key rather than dropped: the export's job is fidelity,
 * and a corrupt blob is still evidence.
 */
public final class EvalJsonlExport {

    public static final String RUN_SCHEMA = "vegaduta.eval-run-jsonl/v1";
    public static final String DATASET_SCHEMA = "vegaduta.eval-dataset-jsonl/v1";

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private EvalJsonlExport() {
    }

    /** One eval run: header line + one line per case result (position order). */
    public static String run(EvalDataset dataset, EvalRun run, List<EvalRunCaseResult> results) {
        StringBuilder out = new StringBuilder(results.size() * 512 + 512);
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("type", "run");
        head.put("schema", RUN_SCHEMA);
        head.put("runId", str(run.getId()));
        head.put("datasetId", str(run.getDatasetId()));
        head.put("datasetName", dataset.getName());
        head.put("agentId", str(run.getAgentId()));
        head.put("agentVersionId", str(run.getAgentVersionId()));
        head.put("agentConfigHash", run.getAgentConfigHash());
        head.put("status", run.getStatus().name());
        head.put("verdict", run.getVerdict() != null ? run.getVerdict().name() : null);
        head.put("passThreshold", run.getPassThreshold());
        head.put("overallScore", run.getOverallScore());
        head.put("caseCount", run.getCaseCount());
        head.put("scoredCaseCount", run.getScoredCaseCount());
        head.put("failedAssertionCount", run.getFailedAssertionCount());
        head.put("erroredCaseCount", run.getErroredCaseCount());
        head.put("judgeModel", run.getJudgeModel());
        head.put("requestedJudgeModel", run.getRequestedJudgeModel());
        head.put("strictJudge", run.isStrictJudge());
        head.put("mode", run.getEvalMode().name());
        head.put("repeats", run.getRepeats());
        head.put("budgetUsd", run.getBudgetUsd());
        head.put("costUsd", run.getCostUsd());
        head.put("datasetHash", run.getDatasetHash());
        head.put("errorMessage", run.getErrorMessage());
        head.put("createdAt", str(run.getCreatedAt()));
        head.put("startedAt", str(run.getStartedAt()));
        head.put("finishedAt", str(run.getFinishedAt()));
        line(out, head);

        for (EvalRunCaseResult r : results) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", "case");
            row.put("id", str(r.getId()));
            row.put("caseId", str(r.getCaseId()));
            row.put("position", r.getPosition());
            row.put("input", r.getUserMessage());
            row.put("expectedOutput", r.getExpectedOutput());
            row.put("expectedTools", EvalDatasetService.parseExpectedTools(r.getExpectedTools()));
            row.put("response", r.getAgentResponse());
            putJson(row, "toolCalls", r.getToolCallsJson());
            putJson(row, "scores", r.getScoresJson());
            row.put("overallScore", r.getOverallScore());
            row.put("expectedToolsSatisfied", r.getExpectedToolsSatisfied());
            row.put("passed", r.getPassed());
            row.put("status", r.getStatus().name());
            row.put("errorMessage", r.getErrorMessage());
            row.put("finishedAt", str(r.getFinishedAt()));
            line(out, row);
        }
        return out.toString();
    }

    /** A dataset as a training set: header line + one line per live case (position order). */
    public static String dataset(EvalDataset dataset, List<EvalDatasetCase> cases) {
        StringBuilder out = new StringBuilder(cases.size() * 384 + 384);
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("type", "dataset");
        head.put("schema", DATASET_SCHEMA);
        head.put("datasetId", str(dataset.getId()));
        head.put("agentId", str(dataset.getAgentId()));
        head.put("name", dataset.getName());
        head.put("passThreshold", dataset.getPassThreshold());
        head.put("toolExecutionEnabled", dataset.getToolExecutionEnabled());
        head.put("contentHash", dataset.getContentHash());
        head.put("caseCount", cases.size());
        line(out, head);

        for (EvalDatasetCase c : cases) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", "case");
            row.put("id", str(c.getId()));
            row.put("position", c.getPosition());
            row.put("input", c.getUserMessage());
            row.put("expectedOutput", c.getExpectedOutput());
            row.put("expectedTools", EvalDatasetService.parseExpectedTools(c.getExpectedTools()));
            putJson(row, "grader", c.getGraderSpec());
            line(out, row);
        }
        return out.toString();
    }

    /**
     * Embeds a stored JSON column as a parsed value ({@code key} -> object/
     * array/scalar). Null/blank stores nothing. Unparseable content is kept
     * verbatim under {@code key + "Raw"} - see the class javadoc.
     */
    private static void putJson(Map<String, Object> row, String key, String storedJson) {
        if (storedJson == null || storedJson.isBlank()) {
            return;
        }
        try {
            JsonNode node = MAPPER.readTree(storedJson);
            row.put(key, node.isNull() ? null : node);
        } catch (Exception e) {
            row.put(key + "Raw", storedJson);
        }
    }

    private static void line(StringBuilder out, Map<String, Object> doc) {
        try {
            out.append(MAPPER.writeValueAsString(doc)).append('\n');
        } catch (Exception e) {
            // A Map<String,Object> of JPA strings/numbers cannot fail to
            // serialize; if it ever does, that is a programmer error, not
            // data - fail loudly rather than emit a truncated file.
            throw new IllegalStateException("JSONL export serialization failed", e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
