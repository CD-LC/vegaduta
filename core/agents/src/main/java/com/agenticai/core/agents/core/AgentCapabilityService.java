package com.agenticai.core.agents.core;

import com.agenticai.core.agents.capability.AgentCapability;
import com.agenticai.core.agents.capability.AgentCapabilityRegistry;
import com.agenticai.core.agents.dto.AgentCapabilityResponse;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.tools.TrifectaEnforcementService;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The generic capability catalog behind {@code GET/PATCH /api/agents/{id}/capabilities}: lists
 * the capabilities that apply to an agent and toggles one. Behavior moved verbatim from {@link
 * AgentService}, which keeps delegating methods.
 */
@Service
public class AgentCapabilityService {

    private final AgentCapabilityRegistry capabilityRegistry;
    private final AgentAccessService access;
    private final AgentVersioningService versioning;
    private TrifectaEnforcementService trifectaEnforcement;

    public AgentCapabilityService(
            AgentCapabilityRegistry capabilityRegistry,
            AgentAccessService access,
            AgentVersioningService versioning) {
        this.capabilityRegistry = capabilityRegistry;
        this.access = access;
        this.versioning = versioning;
    }

    @Autowired(required = false)
    void setTrifectaEnforcement(TrifectaEnforcementService trifectaEnforcement) {
        this.trifectaEnforcement = trifectaEnforcement;
    }

    @Transactional(readOnly = true)
    public List<AgentCapabilityResponse> listCapabilities(UUID agentId) {
        Agent agent = access.requireAccess(agentId, PermissionLevel.VIEWER);
        return capabilityRegistry.listFor(agent);
    }

    @Transactional
    public AgentResponse setCapabilityEnabled(UUID agentId, String capabilityKey, boolean enabled) {
        Agent agent = access.requireAccess(agentId, PermissionLevel.EDITOR);
        access.assertEditable(agent);
        AgentCapability capability = capabilityRegistry.find(capabilityKey);
        if (capability == null || !capability.appliesTo(agent)) {
            throw new IllegalArgumentException(
                    "Unknown capability '" + capabilityKey + "' for this agent");
        }
        capability.apply(agent, enabled);
        // A capability changes which tools an Auto-select agent is offered: drop its cached
        // trifecta verdict once this transaction commits.
        if (trifectaEnforcement != null) {
            trifectaEnforcement.invalidateAfterCommit(agentId);
        }
        return versioning.saveVersioned(
                agent, (enabled ? "Enabled" : "Disabled") + " " + capability.label());
    }
}
