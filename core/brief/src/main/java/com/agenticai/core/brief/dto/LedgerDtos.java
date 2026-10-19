package com.agenticai.core.brief.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/briefs/{id}/ledger} and the rehearsal / experiment shapes (contract section 2). The ledger is
 * measured or absent: a state other than READY / COLLECTING carries a {@code reason} and no numbers.
 */
public final class LedgerDtos {

    public static final String LOCKED = "LOCKED";
    public static final String NOT_APPLIED = "NOT_APPLIED";
    public static final String NO_EVAL_SET = "NO_EVAL_SET";
    public static final String COLLECTING = "COLLECTING";
    public static final String READY = "READY";

    private LedgerDtos() {
    }

    /**
     * @param state LOCKED (the plan has no eval runs), NOT_APPLIED, NO_EVAL_SET (nothing to measure against),
     *     COLLECTING (a dataset exists but no run has finished) or READY
     * @param reason a human sentence for every state except READY
     * @param noiseFloor the dataset's measured score noise floor; null when it could not be measured yet
     * @param runs finished eval runs, oldest first
     */
    public record Ledger(
        String briefId,
        String state,
        String reason,
        String rung,
        Baseline baseline,
        Double noiseFloor,
        List<RunView> runs,
        ThumbsView thumbs,
        List<ExperimentView> experiments,
        List<TrustEventView> trustEvents
    ) {
    }

    /**
     * The one-line proof shown on the brief's card and detail ({@code ledgerSummary}; {@code baseline} / {@code lastDelta}
     * in the list). Every number is null unless it was measured.
     */
    public record LedgerSummary(
        String state,
        String reason,
        Double baseline,
        Double latest,
        Double lastDelta,
        boolean lastDeltaSignificant,
        int runs,
        Double noiseFloor
    ) {
    }

    public record Baseline(String runId, double score, Instant at, int version) {
    }

    /**
     * @param version the brief version that was current when the run started
     * @param deltaVsPrev score minus the previous run's score; null for the first run
     * @param significant true only when a noise floor was measured AND the change is larger than it
     * @param change BASELINE, SIGNIFICANT_UP, SIGNIFICANT_DOWN, WITHIN_NOISE or UNKNOWN_NOISE (no floor measured yet)
     */
    public record RunView(
        int version,
        String runId,
        Instant at,
        double score,
        double passThreshold,
        String verdict,
        Double noiseFloor,
        Double deltaVsPrev,
        boolean significant,
        String change
    ) {
    }

    /** @param ratio thumbs-up over rated turns; null when nothing was rated */
    public record ThumbsView(long up, long down, long rated, Double ratio) {
    }

    public record VariantView(String agentId, long calls, long feedback, Double thumbsUpRatePct, Double errorRatePct) {
    }

    /**
     * @param comparison only for experiments started through {@code POST /experiments} (control = the running
     *     configuration, treatment = the earlier version it was set against); null when either arm has too little
     *     feedback to compare
     */
    public record ExperimentView(
        String id,
        String agentRef,
        String agentId,
        String name,
        String status,
        Instant createdAt,
        VariantView control,
        VariantView treatment,
        Comparison comparison
    ) {
    }

    /** @param delta control thumbs-up rate minus treatment's, as a fraction (positive: the running version is better) */
    public record Comparison(boolean significant, double delta, String summary) {
    }

    public record TrustEventView(
        String kind, String fromRung, String toRung, Integer version, String actor, Instant at, JsonNode evidence
    ) {
    }

    // ------------------------------------------------------------------ rehearsal

    /** {@code POST /rehearse}. {@code label} is always "rehearsal". */
    public record RehearsalStarted(String label, String agentRef, String agentId, List<String> sessionIds,
                                   List<String> personas, String note) {
    }

    /** {@code GET /rehearsals}: the brief's rehearsal sessions, each labelled. */
    public record RehearsalView(
        String label, String sessionId, String status, Instant createdAt, List<RehearsalRun> runs
    ) {
    }

    public record RehearsalRun(String runId, String persona, String status, String verdict, String verdictReason, int turns) {
    }

    // ------------------------------------------------------------------ experiments

    /**
     * {@code POST /experiments}. {@code versionA} is optional (the running configuration, which is always the control;
     * if given it must be the agent's current version id); {@code versionB} is the agent_version id to compare against.
     */
    public record ExperimentRequest(String agentRef, String versionA, String versionB, Integer trafficPercent, Boolean start) {
    }

    public record ExperimentStarted(String experimentId, String agentRef, String agentId, String name, String status,
                                    int trafficPercent) {
    }
}
