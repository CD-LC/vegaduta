package com.agenticai.core.agents.core;

import java.util.UUID;

public class PromptVersionNotFoundException extends RuntimeException {

    public PromptVersionNotFoundException(UUID versionId, UUID agentId) {
        super("No prompt version found with id " + versionId + " for agent " + agentId);
    }
}
