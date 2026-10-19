package com.agenticai.core.agents.core;

import com.agenticai.core.sharing.ResourceOwnerLookup;
import com.agenticai.core.sharing.ResourceType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Cross-user resource sharing retrofit (Round 2) - the {@code core/agents}
 * adapter for core/sharing's {@link ResourceOwnerLookup} seam. Lets {@code
 * ShareController} (which only ever has a bare agent id off the URL, never a
 * loaded {@link Agent}) resolve "who owns this agent" without core/sharing
 * needing a forward dependency on this module - see {@link
 * ResourceOwnerLookup}'s own javadoc for the full "why".
 *
 * <p>Deliberately a thin {@link AgentRepository} lookup, not a call through
 * {@link AgentService} - {@code AgentService#get}/{@code #getEntity} either
 * enforce access themselves (which would be circular: this class exists to
 * answer "who owns it" for THAT enforcement) or 404 on a legacy/cross-tenant
 * id, neither of which is what an owner-resolution seam needs. {@code
 * Optional.empty()} for both "no such agent" and "agent exists but predates
 * this feature (createdBy is null)" - {@code Agent::getCreatedBy} returning
 * null already collapses into {@code Optional.empty()} via {@link
 * Optional#map}, so no explicit null-check is needed here.
 */
@Component
public class AgentResourceOwnerLookup implements ResourceOwnerLookup {

    private final AgentRepository repository;

    public AgentResourceOwnerLookup(AgentRepository repository) {
        this.repository = repository;
    }

    @Override
    public ResourceType supportedType() {
        return ResourceType.AGENT;
    }

    @Override
    public Optional<String> findCreatedBy(UUID resourceId) {
        return repository.findById(resourceId).map(Agent::getCreatedBy);
    }
}
