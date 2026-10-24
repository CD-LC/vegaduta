package com.agenticai.core.agents.job;

import com.agenticai.core.agents.turn.ApprovalContinuation;
import java.util.Optional;

/**
 * The seam through which the turn a job runs AFTER an approval park gets the decisions it was parked
 * on, WITHOUT {@link AgentJobExecution} importing {@code job/approval/} (the same reason {@link
 * AgentJobApprovalPark} exists: the approval package depends on this one, so this one may not
 * depend back on it). The implementation is {@code AgentJobApprovalResume}, which builds the
 * message with the continuation builder a chat session's server-started turn uses.
 *
 * <p>Absent implementation == the previous behaviour: the resumed job re-sends its goal and relies
 * on the history note for the approved tool's result.
 */
public interface AgentJobApprovalResumption {

    /**
     * What the first turn of a resumed job is asked.
     *
     * @param message what the model is sent (the job's goal restated with the decided calls'
     *     results)
     * @param continuation the decision behind it, which makes the turn screen, select tools and
     *     route on the job's goal and keep the tools the job already used on offer
     */
    record ResumeTurn(String message, ApprovalContinuation continuation) {}

    /**
     * @param goal the job's goal as the model is normally asked it
     * @return empty when no decided, not-yet-delivered approval can be turned into a continuation;
     *     never throws
     */
    Optional<ResumeTurn> resumeFor(AgentJob job, String goal);
}
