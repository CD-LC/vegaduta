package com.agenticai.core.agents.core;

import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.version.AgentVersionSnapshotService;
import com.agenticai.core.security.TenantContext;
import org.springframework.stereotype.Service;

/**
 * The single write choke point for agent configuration: stamps who changed it, saves, appends one
 * immutable snapshot of the configuration the agent now has and returns the response. Every
 * config-mutating path routes through {@link #saveVersioned}, which is what makes "every config
 * change is recoverable" a property of the write path rather than something each caller must
 * remember. This is the WRITE half only; reading and restoring versions lives in {@code
 * AgentVersionService}, which depends on {@link AgentService}.
 *
 * <p>Snapshot AFTER the save (a label must be able to name the configuration the agent is running
 * now), and never fails a save: {@link AgentVersionSnapshotService#snapshot} swallows and logs its
 * own failures. Deliberately NOT used by {@link AgentModelResolver#resolveEffectiveModel}'s
 * null-model self-heal, which is a repair, not a tenant edit.
 */
@Service
public class AgentVersioningService {

    private final AgentRepository repository;
    private final AgentVersionSnapshotService agentVersionSnapshotService;
    private final AgentResponseAssembler assembler;

    public AgentVersioningService(
            AgentRepository repository,
            AgentVersionSnapshotService agentVersionSnapshotService,
            AgentResponseAssembler assembler) {
        this.repository = repository;
        this.agentVersionSnapshotService = agentVersionSnapshotService;
        this.assembler = assembler;
    }

    /**
     * @param changeSummary short, human-readable, written by the call site - it is what turns a
     *     version list into a changelog. Never client-supplied text.
     */
    public AgentResponse saveVersioned(Agent agent, String changeSummary) {
        // Audit trail (V393): WHO made this change, null with no request context.
        agent.setUpdatedBy(TenantContext.current().map(TenantContext::getSubject).orElse(null));
        Agent saved = repository.save(agent);
        agentVersionSnapshotService.snapshot(saved, changeSummary);
        return assembler.toResponse(saved);
    }
}
