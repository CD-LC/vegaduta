package com.agenticai.core.agents.core;

import java.util.UUID;

public class AgentNotFoundException extends RuntimeException {

    public AgentNotFoundException(UUID agentId) {
        super("No agent found with id " + agentId);
    }
}
