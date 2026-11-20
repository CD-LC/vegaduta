package com.agenticai.core.omnichannel.demo;

import com.agenticai.core.agents.core.AgentLookupService;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import org.springframework.stereotype.Service;

/**
 * Looks up the display name of a demo session's agent in the demo tenant's own schema, so the demo
 * webhook controller does not touch {@link AgentRepository} or the {@link Agent} entity.
 */
@Service
public class DemoAgentNameService {

    private final DemoSessionService demoSessionService;
    private final AgentLookupService agentLookup;

    public DemoAgentNameService(
            DemoSessionService demoSessionService, AgentLookupService agentLookup) {
        this.demoSessionService = demoSessionService;
        this.agentLookup = agentLookup;
    }

    /** Best effort, in the demo tenant's own schema; null means "don't name it". */
    public String agentName(DemoSession session) {
        try {
            if (session == null || session.getAgentId() == null) {
                return null;
            }
            return demoSessionService.inDemoTenantContext(
                    session, () -> agentLookup.nameOf(session.getAgentId()).orElse(null));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
