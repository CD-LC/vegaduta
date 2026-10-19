package com.agenticai.core.workflow.voicemenu;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A paused voice-call workflow run's pending DTMF gather - the {@code
 * voice_menu} node's halt/resume state, same shape as {@code
 * WorkflowInputRequest} (see that class's javadoc for the general pattern).
 * Created by {@code WorkflowNodeExecutor#executeVoiceMenuNode} at halt time
 * (via {@link WorkflowVoiceMenuRequestService#createPending}); resolved by
 * {@link WorkflowVoiceMenuRequestService#collectDigits}, which matches the
 * collected digits against {@code branches} and feeds the result back into
 * the run as the resumed node's {@code branch()}.
 *
 * <p>{@code promptText}/{@code invalidPromptText}/{@code numDigits}/{@code
 * finishOnKey}/{@code timeoutSeconds}/{@code maxRetries} snapshot the node's
 * config AT HALT TIME (same reasoning {@code WorkflowInputRequest.formSchemaJson}
 * documents: a graph edited while a caller is mid-menu must not change what
 * that live call is already hearing). {@code branchesJson} likewise snapshots
 * the digit-to-branch-label map. {@code nodeInput} is the resume envelope
 * (the upstream output this node consumed), carried verbatim for the same
 * reason {@code WorkflowInputRequest.nodeInput} is.
 *
 * <p>{@code attempt} starts at 1 and increments on every retry (an empty or
 * unmatched gather that has not yet exhausted {@code maxRetries}); once
 * {@code attempt > maxRetries} the next collection resolves to the implicit
 * {@code "timeout"}/{@code "no_match"} branch instead of retrying again.
 *
 * <p>Tenant-schema entity (no {@code schema=} on {@code @Table}, same
 * convention as {@code workflow_input_request}/{@code workflow_run}),
 * persisted via migration V308 ({@code workflow_voice_menu_request}).
 */
@Entity
@Table(name = "workflow_voice_menu_request")
public class WorkflowVoiceMenuRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "workflow_name", length = 255)
    private String workflowName;

    @Column(name = "node_id", nullable = false, length = 100)
    private String nodeId;

    @Column(name = "node_label", length = 255)
    private String nodeLabel;

    @Column(name = "prompt_text", columnDefinition = "TEXT")
    private String promptText;

    @Column(name = "invalid_prompt_text", columnDefinition = "TEXT")
    private String invalidPromptText;

    @Column(name = "num_digits")
    private Integer numDigits;

    @Column(name = "finish_on_key", length = 4)
    private String finishOnKey;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "branches", columnDefinition = "jsonb")
    private String branchesJson;

    @Column(name = "node_input", columnDefinition = "TEXT")
    private String nodeInput;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WorkflowVoiceMenuRequestStatus status;

    @Column(name = "requested_by", length = 255)
    private String requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "collected_digits", length = 64)
    private String collectedDigits;

    @Column(name = "resolved_branch", length = 64)
    private String resolvedBranch;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected WorkflowVoiceMenuRequest() {
        // JPA
    }

    public WorkflowVoiceMenuRequest(
        UUID runId, UUID workflowId, String workflowName, String nodeId, String nodeLabel,
        String promptText, String invalidPromptText, Integer numDigits, String finishOnKey,
        int timeoutSeconds, int maxRetries, String branchesJson, String nodeInput, String requestedBy
    ) {
        this.runId = runId;
        this.workflowId = workflowId;
        this.workflowName = workflowName;
        this.nodeId = nodeId;
        this.nodeLabel = nodeLabel;
        this.promptText = promptText;
        this.invalidPromptText = invalidPromptText;
        this.numDigits = numDigits;
        this.finishOnKey = finishOnKey;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRetries = maxRetries;
        this.attempt = 1;
        this.branchesJson = branchesJson;
        this.nodeInput = nodeInput;
        this.status = WorkflowVoiceMenuRequestStatus.PENDING;
        this.requestedBy = requestedBy;
        this.createdAt = Instant.now();
    }

    public void incrementAttempt() {
        this.attempt = this.attempt + 1;
    }

    public void markResolved(String collectedDigits, String resolvedBranch) {
        this.status = WorkflowVoiceMenuRequestStatus.RESOLVED;
        this.collectedDigits = collectedDigits;
        this.resolvedBranch = resolvedBranch;
        this.resolvedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getNodeLabel() {
        return nodeLabel;
    }

    public String getPromptText() {
        return promptText;
    }

    public String getInvalidPromptText() {
        return invalidPromptText;
    }

    public Integer getNumDigits() {
        return numDigits;
    }

    public String getFinishOnKey() {
        return finishOnKey;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getBranchesJson() {
        return branchesJson;
    }

    public String getNodeInput() {
        return nodeInput;
    }

    public WorkflowVoiceMenuRequestStatus getStatus() {
        return status;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCollectedDigits() {
        return collectedDigits;
    }

    public String getResolvedBranch() {
        return resolvedBranch;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
