package com.agenticai.core.agents.job;

/**
 * Optional hook called once for each job that reaches a terminal state (SUCCEEDED, FAILED, CANCELLED),
 * whichever path wrote it: the worker, the deadline / orphan / queue-TTL sweeps, or a cancel. Lets a
 * feature that meters its own runs (deep research) give the meter back for a run that produced nothing.
 *
 * <p>Lives in the job package so the job code never imports a feature package. Implementations must not
 * throw; {@link AgentJobOutcomes} swallows anything they do anyway.
 */
public interface AgentJobOutcomeListener {

    void onTerminal(AgentJob job);
}
