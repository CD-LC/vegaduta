package com.agenticai.core.quality.dataset;

import com.agenticai.core.quality.calibration.JudgeCalibrationSetService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The exportable, self-describing record of one eval run: everything needed
 * to audit or reproduce its score outside the platform, with a SHA-256 over
 * the canonical form of the content so a copy can be checked for tampering or
 * accidental edits.
 *
 * <p>Schema v2: the hashed {@code content} no longer contains the live dataset cases (moved to the
 * unhashed {@code live}); digests of v1 bundles do not verify against v2 content and must be
 * verified against the v1 layout they were exported with.
 *
 * <p>{@code content} holds: the run (status, verdict, score, threshold,
 * repeats, judge model, dataset hash, cost, timestamps), the agent-config
 * snapshot (model, temperature, seed, prompt hash, tool allow-list - null on
 * runs that predate V375), the dataset's immutable identity (id + the content
 * hash recorded at run time), and every case result (case id, the case content
 * recorded at run time, response, tool calls, per-dimension scores). The
 * dataset as it stands NOW (name, live cases with graders, drift flag) is
 * returned separately as {@code live} and is deliberately NOT hashed, so
 * renaming or editing the dataset never changes an exported run's digest.
 * Limit: a case's grader spec is not stored on its result row (no schema
 * change), so it appears only in {@code live}; the grader's per-case verdict
 * is in each result's scores. {@code sha256} is computed over {@link CanonicalJson#write} of
 * {@code content}; to verify, re-serialise {@code content} with keys sorted
 * and compact separators and hash the UTF-8 bytes. The system prompt itself is
 * NOT exported (only its hash), and the requesting user's identity is omitted.
 *
 * <p>Honest limit: a bundle proves WHAT was recorded, not that the model is
 * deterministic - LLM sampling is not reproducible even at a fixed seed on most
 * providers. That is why the repeats and the snapshot are recorded.
 */
public final class EvalRunBundle {

    public static final String SCHEMA = "vegaduta.eval-run-bundle/v2";

    private EvalRunBundle() {
    }

    /** The response body: the canonicalised content and its digest. */
    public record Bundle(
        String schema, String hashAlgorithm, String sha256, Map<String, Object> content,
        /** The dataset as it stands NOW (name, live cases with graders, drift flag). NOT covered by sha256. */
        Map<String, Object> live
    ) {
    }

    public static Bundle build(
        EvalDataset dataset, List<EvalDatasetCase> cases, EvalRun run, List<EvalRunCaseResult> results
    ) {
        return build(dataset, cases, run, results, null);
    }

    /**
     * {@code judgeTrust} (wave E, null = omit): whether the LLM judge that scored this run was ever calibrated
     * against human labels, as of NOW. It goes in the unhashed {@code live} section: calibration status changes
     * after the run, so putting it under {@code content} would make one run's digest change between exports.
     */
    public static Bundle build(
        EvalDataset dataset, List<EvalDatasetCase> cases, EvalRun run, List<EvalRunCaseResult> results,
        JudgeCalibrationSetService.JudgeTrust judgeTrust
    ) {
        Map<String, Object> runDoc = new LinkedHashMap<>();
        runDoc.put("id", str(run.getId()));
        runDoc.put("status", run.getStatus().name());
        runDoc.put("verdict", run.getVerdict() != null ? run.getVerdict().name() : null);
        runDoc.put("overallScore", run.getOverallScore());
        runDoc.put("passThreshold", run.getPassThreshold());
        runDoc.put("caseCount", run.getCaseCount());
        runDoc.put("scoredCaseCount", run.getScoredCaseCount());
        runDoc.put("failedAssertionCount", run.getFailedAssertionCount());
        runDoc.put("erroredCaseCount", run.getErroredCaseCount());
        runDoc.put("repeats", run.getRepeats());
        runDoc.put("judgeModel", run.getJudgeModel());
        runDoc.put("requestedJudgeModel", run.getRequestedJudgeModel());
        runDoc.put("strictJudge", run.isStrictJudge());
        runDoc.put("datasetHashAtRun", run.getDatasetHash());
        runDoc.put("agentVersionId", str(run.getAgentVersionId()));
        runDoc.put("budgetUsd", run.getBudgetUsd() != null ? run.getBudgetUsd().toPlainString() : null);
        runDoc.put("costUsd", run.getCostUsd() != null ? run.getCostUsd().toPlainString() : null);
        runDoc.put("errorMessage", run.getErrorMessage());
        runDoc.put("createdAt", str(run.getCreatedAt()));
        runDoc.put("startedAt", str(run.getStartedAt()));
        runDoc.put("finishedAt", str(run.getFinishedAt()));

        Map<String, Object> agentDoc = new LinkedHashMap<>();
        agentDoc.put("model", run.getAgentModel());
        agentDoc.put("temperature", run.getAgentTemperature());
        agentDoc.put("seed", run.getAgentSeed());
        agentDoc.put("configHash", run.getAgentConfigHash());
        // Parsed back to a tree so it nests as JSON, not as an escaped string.
        agentDoc.put("config", parseOrNull(run.getAgentConfigJson()));

        // Immutable identity only: renaming/editing the dataset later must not
        // change the digest of an already-exported run. Everything mutable
        // (name, live cases, current hash, drift flag) goes in the UNHASHED
        // 'live' section instead.
        Map<String, Object> datasetDoc = new LinkedHashMap<>();
        datasetDoc.put("id", str(dataset.getId()));
        datasetDoc.put("contentHashAtRun", run.getDatasetHash());

        Map<String, Object> live = new LinkedHashMap<>();
        live.put("name", dataset.getName());
        live.put("passThreshold", dataset.getPassThreshold());
        live.put("toolExecutionEnabled", dataset.isToolExecutionEnabled());
        live.put("currentContentHash", dataset.getContentHash());
        live.put("changedSinceRun",
            run.getDatasetHash() != null && dataset.getContentHash() != null
                && !run.getDatasetHash().equals(dataset.getContentHash()));
        List<Object> liveCases = new ArrayList<>();
        for (EvalDatasetCase c : cases) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("id", str(c.getId()));
            doc.put("position", c.getPosition());
            doc.put("userMessage", c.getUserMessage());
            doc.put("expectedOutput", c.getExpectedOutput());
            doc.put("expectedTools", parseOrNull(c.getExpectedTools()));
            doc.put("grader", parseOrNull(c.getGraderSpec()));
            liveCases.add(doc);
        }
        live.put("cases", liveCases);
        if (judgeTrust != null) {
            Map<String, Object> trust = new LinkedHashMap<>();
            trust.put("status", judgeTrust.status());
            trust.put("kappa", judgeTrust.kappa());
            trust.put("n", judgeTrust.n());
            live.put("judgeCalibration", trust);
        }

        List<Object> resultDocs = new ArrayList<>();
        for (EvalRunCaseResult r : results) {
            // Case id + content as recorded at run time (the result row snapshots
            // them), so the digest is stable however the dataset changes later.
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("caseId", str(r.getCaseId()));
            doc.put("position", r.getPosition());
            doc.put("userMessage", r.getUserMessage());
            doc.put("expectedOutput", r.getExpectedOutput());
            doc.put("expectedTools", parseOrNull(r.getExpectedTools()));
            doc.put("agentResponse", r.getAgentResponse());
            doc.put("toolCalls", parseOrNull(r.getToolCallsJson()));
            doc.put("scores", parseOrNull(r.getScoresJson()));
            doc.put("overallScore", r.getOverallScore());
            doc.put("expectedToolsSatisfied", r.getExpectedToolsSatisfied());
            doc.put("passed", r.getPassed());
            doc.put("status", r.getStatus().name());
            doc.put("errorMessage", r.getErrorMessage());
            resultDocs.add(doc);
        }

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("run", runDoc);
        content.put("agent", agentDoc);
        content.put("dataset", datasetDoc);
        content.put("results", resultDocs);

        // Normalise through canonical JSON once, so the object we return is
        // exactly the tree the digest was computed over.
        String canonical = CanonicalJson.write(content);
        Map<String, Object> normalised = parseMap(canonical);
        return new Bundle(SCHEMA, "SHA-256 over canonical JSON (sorted keys, compact) of 'content'",
            CanonicalJson.sha256Hex(canonical), normalised, live);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private static Object parseOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            return null; // Stored provenance text that no longer parses: absent, not an export failure.
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMap(String json) {
        try {
            return MAPPER.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not re-read canonical bundle JSON", e);
        }
    }
}
