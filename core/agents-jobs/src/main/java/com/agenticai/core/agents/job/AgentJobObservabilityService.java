package com.agenticai.core.agents.job;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.output.JobResultSchemaValidator;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Persistence/access facade for {@link AgentJobObservabilityController}: owns every repository
 * touch so the controller deals only in DTOs and service calls.
 *
 * <p>The result schema is saved through {@link AgentRepository} rather than an {@code AgentService}
 * update method on purpose: those methods call {@code AgentSchemaService#refresh}, which
 * regenerates {@code output_schema_json} and bumps {@code schema_version}. Neither has anything to
 * do with a tenant-authored RESULT schema, and triggering them here would make an unrelated column
 * churn on every save of this one.
 */
@Service
public class AgentJobObservabilityService {

    private final AgentService agentService;
    private final AgentRepository agentRepository;
    private final AgentJobRepository jobRepository;
    private final JobResultSchemaValidator schemaValidator;

    public AgentJobObservabilityService(
            AgentService agentService,
            AgentRepository agentRepository,
            AgentJobRepository jobRepository,
            JobResultSchemaValidator schemaValidator) {
        this.agentService = agentService;
        this.agentRepository = agentRepository;
        this.jobRepository = jobRepository;
        this.schemaValidator = schemaValidator;
    }

    /**
     * Path-scoped existence check: throws {@link AgentJobNotFoundException} when the job does not
     * exist OR belongs to a different agent.
     */
    public void requireJob(UUID agentId, UUID jobId) {
        jobRepository
                .findByIdAndAgentId(jobId, agentId)
                .orElseThrow(() -> new AgentJobNotFoundException(jobId));
    }

    /** True when the job is terminal, or no longer exists. */
    public boolean isJobFinished(UUID jobId) {
        return jobRepository.findById(jobId).map(job -> job.getStatus().isTerminal()).orElse(true);
    }

    /** Validates then stores the schema (EDITOR); returns what is now stored. */
    public String setResultSchema(UUID agentId, String schemaJson) {
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        schemaValidator.requireUsableSchema(schemaJson);
        agent.setResultSchemaJson(schemaJson);
        agentRepository.save(agent);
        return agent.getResultSchemaJson();
    }

    /** The stored schema (VIEWER), empty when unset or blank. */
    public Optional<String> getResultSchema(UUID agentId) {
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        String schemaJson = agent.getResultSchemaJson();
        if (schemaJson == null || schemaJson.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(schemaJson);
    }

    /** Clears the schema (EDITOR); a no-op when none was set. */
    public void clearResultSchema(UUID agentId) {
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        if (agent.getResultSchemaJson() != null) {
            agent.setResultSchemaJson(null);
            agentRepository.save(agent);
        }
    }
}
