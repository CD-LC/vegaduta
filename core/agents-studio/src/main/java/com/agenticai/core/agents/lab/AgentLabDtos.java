package com.agenticai.core.agents.lab;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Wire shapes of the Agent Lab routes ({@link AgentLabController}). The web
 * client's {@code labApi.ts} declares the same field names, so a rename here
 * is a breaking change on both sides.
 *
 * <p>Status values travel as plain strings rather than enums: the run
 * document is also what {@link AgentLabRunStore} keeps in Redis for an hour,
 * and a string survives a deploy that adds or renames a constant where an
 * enum would fail to deserialise a run that is still being polled.
 *
 * <p>Response records are {@code @JsonInclude(ALWAYS)}: the client tells
 * "not scored" from "scored" by an explicit {@code null}, so a field must
 * never disappear if a global non-null inclusion is ever configured.
 */
public final class AgentLabDtos {

    private AgentLabDtos() {
    }

    // ---- kinds and statuses -------------------------------------------------

    public static final String KIND_KNOCKOUT = "KNOCKOUT";
    public static final String KIND_CONSISTENCY = "CONSISTENCY";

    public static final String MODE_NONE = "NONE";
    public static final String MODE_JUDGE = "JUDGE";

    public static final String RUN_QUEUED = "QUEUED";
    public static final String RUN_RUNNING = "RUNNING";
    public static final String RUN_SUCCEEDED = "SUCCEEDED";
    public static final String RUN_FAILED = "FAILED";
    public static final String RUN_CANCELLED = "CANCELLED";

    public static final String CELL_PENDING = "PENDING";
    public static final String CELL_DONE = "DONE";
    public static final String CELL_ERROR = "ERROR";

    public static final String SCORE_NONE = "NONE";
    public static final String SCORE_PENDING = "PENDING";
    public static final String SCORE_SCORED = "SCORED";
    public static final String SCORE_UNAVAILABLE = "UNAVAILABLE";

    public static final String SIMILARITY_PENDING = "PENDING";
    public static final String SIMILARITY_PARTIAL = "PARTIAL";
    public static final String SIMILARITY_DONE = "DONE";

    /** Whether a run in this status will never change again. */
    public static boolean isTerminal(String runStatus) {
        return RUN_SUCCEEDED.equals(runStatus) || RUN_FAILED.equals(runStatus) || RUN_CANCELLED.equals(runStatus);
    }

    // ---- request --------------------------------------------------------------

    /** One earlier message of a cell's conversation; {@code role} is {@code user} or {@code assistant}. */
    public record LabMessage(String role, String content) {}

    /**
     * One model call to make.
     *
     * @param key caller-chosen, unique within the run; echoed on the result
     * @param promptIndex index into {@link LabRunRequest#systemPrompts}, or null for the agent's stored prompt
     * @param expected optional reference answer for the score judge
     */
    public record LabCellRequest(
        String key, Integer promptIndex, String message, List<LabMessage> history, String expected
    ) {}

    /**
     * @param kind {@code KNOCKOUT} or {@code CONSISTENCY}
     * @param scoring {@code NONE}, or {@code JUDGE} to grade every answer 0..1
     * @param similarity {@code NONE}, or {@code JUDGE} to compare every pair of answers for same meaning
     */
    public record LabRunRequest(
        String kind, List<String> systemPrompts, List<LabCellRequest> cells, String scoring, String similarity
    ) {}

    // ---- responses ------------------------------------------------------------

    /** @param reason {@code PLAN} when {@code allowed} is false, otherwise null */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LabCapabilitiesResponse(
        boolean allowed, String reason, int maxCells, int maxCallsPerRun,
        int maxSystemPrompts, int maxSimilarityCells, int runTtlMinutes
    ) {}

    /**
     * @param score 0..1, or null whenever {@code scoreStatus} is not {@code SCORED} - never a default number
     * @param ms wall time of the cell's own model call, null while pending
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LabCellResult(
        String key, String status, String answer, boolean answerTruncated,
        Double score, String scoreStatus, String scoreFeedback, String errorMessage, Long ms
    ) {}

    /**
     * Pairwise same-meaning verdicts over the DONE cells.
     *
     * @param keys the DONE cells, in cell order; row and column i of {@code matrix} belong to {@code keys[i]}
     * @param matrix symmetric, 1.0 on the diagonal; 1.0 = same, 0.0 = different, null = no verdict
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LabSimilarity(String method, String status, List<String> keys, List<List<Double>> matrix) {}

    /**
     * @param plannedCalls the estimate while the run is live; equal to {@code completedCalls} once it SUCCEEDED
     * @param completedCalls model calls attempted so far, judge calls included
     * @param errorMessage a full sentence, or null
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LabRunResponse(
        String runId, String agentId, String kind, String status,
        int plannedCalls, int completedCalls, boolean cancelRequested, String errorMessage,
        long createdAtMs, long updatedAtMs, Long finishedAtMs,
        List<LabCellResult> cells, LabSimilarity similarity
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LabCancelResponse(String runId, boolean cancelRequested) {}

    // ---- stored form ----------------------------------------------------------

    /**
     * What {@link AgentLabRunStore} keeps: the response plus who asked for
     * it. Deliberately not the prompts or the cell requests - those stay in
     * the worker's memory, so an hour of run documents in Redis holds answers
     * only, never a copy of a tenant's system prompt.
     */
    public record LabRunDocument(LabRunResponse run, String requestedBy) {}
}
