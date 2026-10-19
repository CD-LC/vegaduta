package com.agenticai.core.agents.research;

import com.agenticai.core.agents.capability.AgentCapabilityState;
import com.agenticai.core.agents.capability.AgentCapabilityStateId;
import com.agenticai.core.agents.capability.AgentCapabilityStateRepository;
import com.agenticai.core.agents.capability.DeepResearchCapability;
import com.agenticai.core.agents.job.AgentJobSubmitGate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Applies the research run cap ({@link ResearchRunUsageService}) to jobs submitted straight to an agent
 * that carries the "Deep research" capability ({@code POST /api/agents/{id}/jobs}). Without it the cap
 * only bound the {@code /api/research/runs} facade, and a direct job to the same agent dodged it.
 *
 * <p>Normal agents are untouched: only an agent with an ENABLED deep-research capability row is gated.
 * The facade reserves the run itself and tells {@code AgentJobService} so, so a facade run is not
 * counted twice.
 */
@Component
public class ResearchAgentSubmitGate implements AgentJobSubmitGate {

    private static final Logger log = LoggerFactory.getLogger(ResearchAgentSubmitGate.class);

    private final AgentCapabilityStateRepository capabilityStates;
    private final ResearchRunUsageService usage;

    public ResearchAgentSubmitGate(AgentCapabilityStateRepository capabilityStates, ResearchRunUsageService usage) {
        this.capabilityStates = capabilityStates;
        this.usage = usage;
    }

    @Override
    public Runnable reserve(UUID agentId, String tenantId, String subject) {
        if (agentId == null || subject == null || subject.isBlank() || !hasDeepResearch(agentId)) {
            return null;
        }
        usage.reserve(tenantId, subject);
        return () -> usage.release(tenantId, subject);
    }

    private boolean hasDeepResearch(UUID agentId) {
        try {
            return capabilityStates.findById(new AgentCapabilityStateId(agentId, DeepResearchCapability.CAPABILITY_KEY))
                .map(AgentCapabilityState::isEnabled)
                .orElse(false);
        } catch (RuntimeException e) {
            // Cannot tell: do not break job submission for every agent over a capability lookup.
            log.warn("Could not read the deep-research capability of agent {}; not gating this submit", agentId, e);
            return false;
        }
    }
}
