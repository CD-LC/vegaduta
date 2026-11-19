package com.agenticai.core.omnichannel.demo;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import org.springframework.stereotype.Service;

/**
 * Looks up the display name of a demo session's agent in the demo tenant's own schema, so the
 * demo webhook controller does not touch {@link AgentRepository} or the {@link Agent} entity.
 */
@Service
public class DemoAgentNameService {

    private final DemoSessionService demoSessionService;
    private final AgentRepository agentRepository;

    public DemoAgentNameService(DemoSessionService demoSessionService, AgentRepository agentRepository) {
        this.demoSessionService = demoSessionService;
        this.agentRepository = agentRepository;
    }

    /** Best effort, in the demo tenant's own schema; null means "don't name it". */
    public String agentName(DemoSession session) {
        try {
            if (session == null || session.getAgentId() == null) {
                return null;
            }
            return demoSessionService.inDemoTenantContext(
                    session,
                    () ->
                            agentRepository
                                    .findById(session.getAgentId())
                                    .map(Agent::getName)
                                    .orElse(null));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
