package com.agenticai.core.quality.dataset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The outcome of replaying + scoring ONE case within one {@link EvalRun}.
 * Append-only: written once by the run worker as each case finishes, never
 * updated (same posture as {@code AgentQualityRecord}).
 *
 * <p>Carries full SNAPSHOTS of the case (message/expectations) rather than
 * joining back to {@code eval_dataset_case}, because cases are editable and
 * deletable after the run - a historical result must stay readable and
 * re-explainable forever regardless of what happened to the dataset since.
 * {@code caseId} is informational only and deliberately not an FK (V213
 * header).
 */
@Entity
@Table(name = "eval_run_case_result")
public class EvalRunCaseResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "case_id")
    private UUID caseId;

    @Column(nullable = false)
    private int position;

    @Column(name = "user_message", nullable = false, columnDefinition = "TEXT")
    private String userMessage;

    @Column(name = "expected_output", columnDefinition = "TEXT")
    private String expectedOutput;

    @Column(name = "expected_tools", columnDefinition = "TEXT")
    private String expectedTools;

    @Column(name = "agent_response", columnDefinition = "TEXT")
    private String agentResponse;

    /** JSON array of {toolName, input, output}; tool-enabled replays only. */
    @Column(name = "tool_calls_json", columnDefinition = "TEXT")
    private String toolCallsJson;

    /**
     * Per-dimension judge results as one JSON document - see V213's column
     * comment for why this is a document and not the {@code
     * @ElementCollection} side-tables {@code agent_quality_record} uses.
     */
    @Column(name = "scores_json", columnDefinition = "TEXT")
    private String scoresJson;

    /**
     * Case-level weighted aggregate; null when nothing was measured.
     * columnDefinition matches V213's NUMERIC(4,3) - see {@code
     * EvalDataset#passThreshold}'s javadoc for why this is required (bare
     * Double maps to float8 by default, which fails Hibernate's
     * ddl-auto=validate boot check against the migration's real column type).
     */
    @Column(name = "overall_score", columnDefinition = "numeric(4,3)")
    private Double overallScore;

    /**
     * Three-valued: TRUE satisfied, FALSE violated, NULL not asserted or not
     * evaluable (tool-less replay). NULL must never be treated as failure -
     * the same honest-absence rule as everything else in this module.
     */
    @Column(name = "expected_tools_satisfied")
    private Boolean expectedToolsSatisfied;

    /**
     * The case-level verdict (V343): TRUE the case passed (scored at/above
     * the run's pass bar AND no violated assertion AND, for repeated runs,
     * a strict majority of repetitions passed), FALSE it failed, NULL not
     * evaluable (UNAVAILABLE judges, replay ERROR). The JUnit report and the
     * console's pass/fail chips read this column rather than re-deriving the
     * rule.
     */
    @Column(name = "passed")
    private Boolean passed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EvalCaseResultStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected EvalRunCaseResult() {
        // JPA
    }

    private EvalRunCaseResult(
        UUID runId, UUID caseId, int position,
        String userMessage, String expectedOutput, String expectedTools
    ) {
        this.runId = runId;
        this.caseId = caseId;
        this.position = position;
        this.userMessage = userMessage;
        this.expectedOutput = expectedOutput;
        this.expectedTools = expectedTools;
        this.createdAt = Instant.now();
        this.finishedAt = this.createdAt;
    }

    /** A case whose replay and (at least partial) scoring succeeded. */
    public static EvalRunCaseResult scored(
        UUID runId, EvalDatasetCase sourceCase,
        String agentResponse, String toolCallsJson, String scoresJson,
        Double overallScore, Boolean expectedToolsSatisfied
    ) {
        return scored(runId, sourceCase, agentResponse, toolCallsJson, scoresJson,
            overallScore, expectedToolsSatisfied, null);
    }

    /**
     * A case whose replay and (at least partial) scoring succeeded.
     * {@code passed} is the case-level three-valued verdict (V343) - the
     * worker computes it; this factory only records.
     */
    public static EvalRunCaseResult scored(
        UUID runId, EvalDatasetCase sourceCase,
        String agentResponse, String toolCallsJson, String scoresJson,
        Double overallScore, Boolean expectedToolsSatisfied, Boolean passed
    ) {
        EvalRunCaseResult result = new EvalRunCaseResult(
            runId, sourceCase.getId(), sourceCase.getPosition(),
            sourceCase.getUserMessage(), sourceCase.getExpectedOutput(), sourceCase.getExpectedTools());
        result.agentResponse = agentResponse;
        result.toolCallsJson = toolCallsJson;
        result.scoresJson = scoresJson;
        result.overallScore = overallScore;
        result.expectedToolsSatisfied = expectedToolsSatisfied;
        result.passed = passed;
        // overallScore == null means the replay worked but NO judge produced
        // a measurement - explicitly UNAVAILABLE, never a silent SCORED-with-
        // nothing (the run-level verdict rules read this distinction).
        result.status = overallScore != null ? EvalCaseResultStatus.SCORED : EvalCaseResultStatus.UNAVAILABLE;
        return result;
    }

    /** A case whose replay itself broke. */
    public static EvalRunCaseResult errored(UUID runId, EvalDatasetCase sourceCase, String errorMessage) {
        EvalRunCaseResult result = new EvalRunCaseResult(
            runId, sourceCase.getId(), sourceCase.getPosition(),
            sourceCase.getUserMessage(), sourceCase.getExpectedOutput(), sourceCase.getExpectedTools());
        result.status = EvalCaseResultStatus.ERROR;
        result.errorMessage = errorMessage;
        return result;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public int getPosition() {
        return position;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public String getExpectedOutput() {
        return expectedOutput;
    }

    public String getExpectedTools() {
        return expectedTools;
    }

    public String getAgentResponse() {
        return agentResponse;
    }

    public String getToolCallsJson() {
        return toolCallsJson;
    }

    public String getScoresJson() {
        return scoresJson;
    }

    public Double getOverallScore() {
        return overallScore;
    }

    public Boolean getExpectedToolsSatisfied() {
        return expectedToolsSatisfied;
    }

    /** Nullable - see the field's javadoc (V343). */
    public Boolean getPassed() {
        return passed;
    }

    public EvalCaseResultStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
