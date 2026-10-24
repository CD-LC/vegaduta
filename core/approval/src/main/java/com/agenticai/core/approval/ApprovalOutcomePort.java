package com.agenticai.core.approval;

/**
 * What {@link HumanApprovalGateService} tells the conversation that asked for an action once the
 * action's approval has ended: ran, failed for good, or was refused (denied by a human or left to
 * expire). It is the one path by which EVERY executor's outcome reaches the agent, so the executor
 * itself does not need to know about conversations.
 *
 * <p>The implementation lives with the turn path ({@code ApprovalOutcomeFollowUp} in core/agents-tools):
 * it writes the outcome into the conversation's history and starts the turn that carries the task on.
 * A request that did not come from an agent turn (an admin screen, a webhook, a scheduled job) carries
 * no goal, and the implementation leaves it alone.
 *
 * <p>An executor that already does this for itself says so with {@link
 * ApprovalActionExecutor#reportsOutcomeItself}.
 */
public interface ApprovalOutcomePort {

    /** How the approved action ended. */
    enum Outcome {
        /** Approved and executed. */
        COMPLETED,
        /** Approved but execution failed and will not be retried. */
        FAILED,
        /** Refused by a human, or expired undecided. */
        DENIED
    }

    /**
     * @param text the action's recorded output ({@link Outcome#COMPLETED}, may be {@code null}), the
     *     failure message ({@link Outcome#FAILED}) or the reviewer's reason ({@link Outcome#DENIED})
     * @return true when a turn was started to continue the task. Never throws.
     */
    boolean afterDecision(ApprovalRequest request, Outcome outcome, String text);
}
