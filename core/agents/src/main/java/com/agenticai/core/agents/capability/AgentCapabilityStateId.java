package com.agenticai.core.agents.capability;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key for {@link AgentCapabilityState} - (agent_id, capability_key). */
public class AgentCapabilityStateId implements Serializable {

    private UUID agentId;
    private String capabilityKey;

    public AgentCapabilityStateId() {
    }

    public AgentCapabilityStateId(UUID agentId, String capabilityKey) {
        this.agentId = agentId;
        this.capabilityKey = capabilityKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AgentCapabilityStateId that)) return false;
        return Objects.equals(agentId, that.agentId) && Objects.equals(capabilityKey, that.capabilityKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(agentId, capabilityKey);
    }
}
