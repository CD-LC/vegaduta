package com.agenticai.core.agents.job.trigger;

/**
 * Why an {@link AgentEventTrigger} is switched off.
 *
 * <p>Recorded rather than inferred because the two cases need OPPOSITE operator
 * responses and are otherwise indistinguishable: a trigger somebody disabled on
 * purpose can be turned straight back on, while one the loop guard disabled will
 * go straight back into the loop - and the bill - unless the cycle itself is
 * broken first.
 */
public enum AgentEventTriggerDisableReason {

    /** A human called {@code DELETE /api/agents/{agentId}/triggers/{triggerId}}. */
    OPERATOR,

    /**
     * The trigger fired more than {@code app.agent-jobs.trigger.max-fires-per-window}
     * times inside {@code app.agent-jobs.trigger.window} and was switched off to
     * stop it spending. See {@link AgentEventTriggerFiring} for what this
     * defends against, and what it does not.
     */
    LOOP_GUARD_TRIPPED
}
