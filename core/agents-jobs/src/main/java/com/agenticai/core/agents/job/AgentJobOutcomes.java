package com.agenticai.core.agents.job;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Re-reads a job that has just gone terminal and tells every {@link AgentJobOutcomeListener}. Never throws. */
@Component
public class AgentJobOutcomes {

    private static final Logger log = LoggerFactory.getLogger(AgentJobOutcomes.class);

    private final AgentJobRepository repository;
    private final ObjectProvider<AgentJobOutcomeListener> listeners;

    public AgentJobOutcomes(AgentJobRepository repository, ObjectProvider<AgentJobOutcomeListener> listeners) {
        this.repository = repository;
        this.listeners = listeners;
    }

    /** Call in the job's tenant context, after the terminal write. A job that is not terminal is ignored. */
    public void terminal(UUID jobId) {
        try {
            AgentJob job = repository.findById(jobId).orElse(null);
            if (job == null || !job.getStatus().isTerminal()) {
                return;
            }
            listeners.orderedStream().forEach(listener -> {
                try {
                    listener.onTerminal(job);
                } catch (RuntimeException e) {
                    log.warn("Outcome listener {} failed for agent job {}", listener.getClass().getSimpleName(), jobId, e);
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not notify outcome listeners for agent job {}", jobId, e);
        }
    }
}
