package com.agenticai.core.agents.capability;

import com.agenticai.core.agents.core.Agent;

/**
 * Base class for capabilities whose flag lives in the generic {@code
 * agent_capability} table (migration V330) rather than a dedicated {@code
 * agent} column. A new provider/capability plugs in by subclassing this with
 * a {@code @Component} supplying key/label/description/availability - no
 * Flyway migration, no endpoint, no frontend change.
 *
 * <p>Prefer this over adding another {@code agent.*_enabled} column for
 * anything new. The four column-backed flags predate this mechanism and stay
 * column-backed (see {@link AgentCapabilityState}'s javadoc for why they were
 * not migrated).
 */
public abstract class StoredAgentCapability implements AgentCapability {

    private final AgentCapabilityStateRepository states;

    protected StoredAgentCapability(AgentCapabilityStateRepository states) {
        this.states = states;
    }

    @Override
    public boolean isEnabled(Agent agent) {
        return states
            .findById(new AgentCapabilityStateId(agent.getId(), key()))
            .map(AgentCapabilityState::isEnabled)
            .orElse(false);
    }

    @Override
    public void apply(Agent agent, boolean enabled) {
        AgentCapabilityStateId id = new AgentCapabilityStateId(agent.getId(), key());
        AgentCapabilityState row = states.findById(id)
            .orElseGet(() -> new AgentCapabilityState(agent.getId(), key(), false));
        row.setEnabled(enabled);
        states.save(row);
    }
}
