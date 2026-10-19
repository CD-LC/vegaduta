package com.agenticai.core.agents.coding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Wire shapes of the coding-task routes ({@link CodingTaskController}); the web client's
 * {@code codingTasks.ts} declares the same field names.
 *
 * <p>Statuses travel as plain strings (the task document lives in Redis for a while, and a string
 * survives a deploy that adds a constant where an enum would fail to deserialise a task that is
 * still being polled). Times are epoch-millisecond longs so the stored form does not depend on how
 * the injected ObjectMapper writes {@code Instant}.
 */
public final class CodingTaskDtos {

    private CodingTaskDtos() {
    }

    // ---- statuses -----------------------------------------------------------------------------

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    /** Tests pass and the review accepted the diff; the harness is blocked on the human. */
    public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";
    /** A human approved the diff; the patch is available. */
    public static final String APPROVED = "APPROVED";
    /** A human rejected the diff (the workspace was rolled back). */
    public static final String REJECTED = "REJECTED";
    /** Tests never passed, the budget ran out, the review refused, or something broke. See {@code outcome}. */
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    /** Approved, then discarded by the user: the sandbox was reset to the baseline and the patch dropped. */
    public static final String ROLLED_BACK = "ROLLED_BACK";

    public static boolean isTerminal(String status) {
        return APPROVED.equals(status) || REJECTED.equals(status) || FAILED.equals(status)
            || CANCELLED.equals(status) || ROLLED_BACK.equals(status);
    }

    public static final String SOURCE_GIT = "GIT";
    public static final String SOURCE_ARCHIVE = "ARCHIVE";

    // ---- request ------------------------------------------------------------------------------

    /**
     * What the caller asked for. Budget fields are optional and clamped to the server caps; the
     * archive (when the source is an upload) travels separately as the multipart file.
     */
    public record StartRequest(
        String agentId, String instruction, String testCommand, String repoUrl,
        Integer maxSteps, Integer maxWallSeconds, Integer maxTokens, Integer maxFixIterations
    ) {}

    /** The caller's decision on the diff it was shown; {@code diffHash} binds the approval to that exact diff. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApproveRequest(String diffHash) {}

    // ---- response / stored document -------------------------------------------------------------

    public record Budget(int maxSteps, int maxWallSeconds, int maxTokens, int maxFixIterations) {}

    public record Usage(int steps, int tokens, long elapsedMs) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Event(long atMs, String type, String text) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TestRun(int iteration, int exitCode, String outputTail) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Review(boolean approved, String notes) {}

    /** One task as the client polls it. {@code outcome} is the harness result name once the run ended, else null. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TaskView(
        String taskId, String agentId, String status, String outcome, String phase,
        String instruction, String testCommand, String sourceKind, String repoUrl, String runner,
        Budget budget, Usage usage, List<Event> events, List<TestRun> tests, Review review,
        boolean diffAvailable, String diffHash, int diffFiles, boolean testFilesTouched,
        String message, boolean cancelRequested, long createdAtMs, long updatedAtMs
    ) {}

    /** What the store keeps: the view plus the owner. The owner is never sent to the client. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TaskDocument(TaskView task, String requestedBy) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DiffResponse(String taskId, String diff, String diffHash, boolean truncated) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CancelResponse(String taskId, boolean accepted) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Capabilities(
        boolean enabled, boolean allowed, String reason, String runner, List<String> testRunners,
        Budget defaults, Budget caps, long maxArchiveBytes, int approvalTimeoutSeconds, int rollbackWindowSeconds,
        boolean networkEnabled
    ) {}
}
