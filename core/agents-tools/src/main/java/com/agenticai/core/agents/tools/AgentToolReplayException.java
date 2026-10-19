package com.agenticai.core.agents.tools;

/**
 * A failure replaying an approved agent tool call - thrown by {@link AgentToolCallApprovalExecutor}
 * and recorded against the {@code ApprovalRequest} by {@code HumanApprovalGateService}'s existing
 * failure-handling path.
 *
 * <p>Deliberately does NOT implement {@code RetryableActionFailure}, and no exception this executor
 * throws ever should: unlike a channel send (where the provider API is idempotent-ish and a timeout
 * is worth retrying), a replayed agent tool is an arbitrary, potentially side-effectful action - an
 * MCP tool that transfers a file, sends a message, mutates a record. The executor cannot know
 * whether a failure happened before or after the side effect landed, so an automatic re-run risks
 * executing the approved action TWICE, which is strictly worse than asking the human to approve a
 * fresh attempt. {@code RetryableActionFailure}'s own javadoc frames retry as opt-in for callers
 * who KNOW the failure is transient; this executor never knows that, so it never opts in.
 */
public class AgentToolReplayException extends RuntimeException {

    public AgentToolReplayException(String message) {
        super(message);
    }

    public AgentToolReplayException(String message, Throwable cause) {
        super(message, cause);
    }
}
