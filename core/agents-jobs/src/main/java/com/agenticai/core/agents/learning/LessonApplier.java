package com.agenticai.core.agents.learning;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.MemoryCapExceededException;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Approved LESSON: stored as an agent-private memory fact (the same store and per-person cap as a
 * fact the person types in by hand), so the agent recalls it in later conversations with that
 * person. The agent is the proposal's {@code agentId}, else payload {@code agentId}.
 */
@Component
@ConditionalOnProperty(name = "app.learning.proposals.enabled", havingValue = "true")
public class LessonApplier implements LearningProposalApplier {

    private final ObjectProvider<AgentMemoryService> memory;
    private final AgentService agents;
    private final int maxFactsPerAgent;

    public LessonApplier(
            ObjectProvider<AgentMemoryService> memory,
            AgentService agents,
            @Value("${app.memory.self-service.max-facts-per-agent:25}") int maxFactsPerAgent) {
        this.memory = memory;
        this.agents = agents;
        this.maxFactsPerAgent = Math.max(1, maxFactsPerAgent);
    }

    @Override
    public LearningProposalKind kind() {
        return LearningProposalKind.LESSON;
    }

    @Override
    public void apply(LearningProposal proposal, String text)
            throws LearningProposalApplyException {
        AgentMemoryService service = memory.getIfAvailable();
        if (service == null) {
            throw new LearningProposalApplyException("Memory is not available on this install.");
        }
        UUID agentId = agentOf(proposal);
        try {
            agents.requireAccess(agentId, PermissionLevel.RUNNER);
        } catch (RuntimeException e) {
            throw new LearningProposalApplyException("That agent is no longer available to you.");
        }
        try {
            service.rememberOwn(agentId, proposal.getSubject(), text, maxFactsPerAgent);
        } catch (MemoryCapExceededException e) {
            throw new LearningProposalApplyException(e.getMessage());
        }
    }

    static UUID agentOf(LearningProposal p) throws LearningProposalApplyException {
        if (p.getAgentId() != null) {
            return p.getAgentId();
        }
        try {
            JsonNode payload = JsonMappers.plain().readTree(p.getPayload());
            return UUID.fromString(payload.path("agentId").asString(""));
        } catch (Exception e) {
            throw new LearningProposalApplyException(
                    "This lesson does not say which agent should remember it.");
        }
    }
}
