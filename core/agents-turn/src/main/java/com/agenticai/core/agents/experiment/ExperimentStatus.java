package com.agenticai.core.agents.experiment;

/**
 * Lifecycle of one {@link AgentExperiment}. Mirrors {@code EvalRunStatus}'s
 * "state transitions enforced on the entity, never in a caller" shape -
 * see that class's javadoc for the same reasoning applied here.
 */
public enum ExperimentStatus {

    /** Created - the treatment clone exists, but no traffic is routed to it yet. */
    DRAFT,

    /** Live: {@code AgentChatService} is bucketing real turns between control and treatment. */
    RUNNING,

    /** Ended, manually or by the auto-stop guardrail. {@link AgentExperiment#getStopReason()} is set. */
    STOPPED,

    /** Reserved for a future explicit "declare a winner and finish" action - not yet reachable. */
    COMPLETED;

    /** DRAFT or RUNNING - the two states {@code uq_agent_experiment_active_per_agent} allows at most one of, per agent. */
    public boolean isActive() {
        return this == DRAFT || this == RUNNING;
    }

    public boolean isTerminal() {
        return this == STOPPED || this == COMPLETED;
    }
}
