package com.agenticai.core.agents.experiment;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 409 for experiment-lifecycle conflicts: creating a second active
 * (DRAFT/RUNNING) experiment for an agent that already has one (backed by
 * {@code uq_agent_experiment_active_per_agent} - the database is the
 * arbiter, this exception just narrates its decision), or starting/stopping
 * an experiment that is not in the expected state. Same
 * {@code @ResponseStatus}-over-central-handler choice as
 * {@code EvalRunConflictException}.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class AgentExperimentConflictException extends RuntimeException {

    public AgentExperimentConflictException(String message) {
        super(message);
    }
}
