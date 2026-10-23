package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.turn.ApprovalContinuation.Outcome;
import com.agenticai.core.approval.ApprovalRequest;

/**
 * Port (ADR-0023): what the approval executor calls once a human has decided a gated tool call, so
 * the platform can carry the original task on. The executor lives with the tools; the turn it
 * starts lives with the turn path - this interface is the only thing the first knows of the second.
 * The implementation is {@code ApprovalContinuationLauncher} in core/agents-turn.
 */
public interface ApprovalContinuationPort {

    /**
     * Continues the conversation that asked for {@code request}'s tool call, with the call's
     * {@code outcome}. Never throws: a failure to continue must not change the outcome of a
     * decision that already took effect. Must be called with the tenant context of the request's
     * tenant on the calling thread.
     *
     * @param text the tool's output, the failure message or the reviewer's reason, per {@code
     *     outcome}
     * @return true when a continuation turn was started
     */
    boolean afterDecision(ApprovalRequest request, Outcome outcome, String text);
}
