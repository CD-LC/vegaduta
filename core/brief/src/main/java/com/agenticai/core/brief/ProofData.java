package com.agenticai.core.brief;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything the proof ledger reads from (and the one thing it starts on) the platform, as one narrow port -
 * the same seam idea as {@link EnvoyPorts}. {@link ServiceProofData} implements it over the real quality, rl,
 * agents and audit services; tests use a fake so the ledger's arithmetic, its locked state and the
 * {@link ProofSource} rules run against fixed numbers.
 *
 * <p>Every method reads in the caller's tenant context. Anything that was not measured is empty or null -
 * never a default that could satisfy a rule.
 */
public interface ProofData {

    /** Measured, finished eval runs of the dataset, oldest first (unmeasured / failed / cancelled runs are not here). */
    List<EvalRunPoint> evalRuns(String datasetId);

    /** Rerun-to-rerun noise floor of the dataset's eval scores, or null when it cannot be estimated yet. */
    Double noiseFloor(String agentId, String datasetId);

    /** Model calls handled by these agents since {@code since} (real traffic plus replays; eval traffic is small next to it). */
    int realRuns(List<String> agentIds, Instant since);

    /** Thumbs on these agents' turns since {@code since}. */
    Thumbs thumbs(List<String> agentIds, Instant since);

    /** Experiments run on this agent, newest first, each with the metrics its window measured. */
    List<ExperimentRow> experiments(String agentId);

    /** Guardrail strikes recorded against the brief since {@code since}. */
    int strikes(UUID briefId, Instant since);

    /** Records one guardrail strike against the brief (an audit row); the strike counter reads it back. */
    void recordStrike(UUID briefId, String agentId, String detail);

    /**
     * Starts one eval run of the dataset.
     *
     * @return the run id, or null when a run of that dataset is already queued or running
     */
    String startEvalRun(String agentId, String datasetId);

    /**
     * @param passed the run met its own pass bar (verdict PASSED)
     * @param verdict PASSED, FAILED or INCONCLUSIVE
     */
    record EvalRunPoint(String runId, Instant at, double score, double passThreshold, boolean passed, String verdict) {
    }

    record Thumbs(long up, long down) {
        public long rated() {
            return up + down;
        }
    }

    /**
     * @param thumbsUpRatePct null when nothing was rated
     * @param errorRatePct null when there were no calls
     */
    record Variant(String agentId, long callCount, long feedbackCount, Double thumbsUpRatePct, Double errorRatePct) {
    }

    record ExperimentRow(
        String id, String agentId, String name, String status, Instant createdAt, Variant control, Variant treatment
    ) {
    }
}
