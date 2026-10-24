package com.agenticai.core.agents.auxiliary;

import com.agenticai.core.providers.ProviderName;
import java.util.Optional;
import java.util.UUID;

/**
 * What {@link AuxiliaryModelResolver} needs to know about the agent a background call is made for: its provider and
 * model. A port rather than a dependency on the agent service so that this package stays a leaf of the package graph
 * (the agent service package reaches the callers of the resolver, and a direct import would close a cycle).
 */
public interface AuxiliaryAgentModels {

    /** The provider and model an agent is configured with; either may be null. */
    record AgentModel(ProviderName provider, String model) {}

    /**
     * @return the agent's provider and model, or empty when there is no such agent in the calling tenant
     * @throws RuntimeException when the agent cannot be loaded; the resolver logs it and falls through to the next choice
     */
    Optional<AgentModel> modelOf(UUID agentId);
}
