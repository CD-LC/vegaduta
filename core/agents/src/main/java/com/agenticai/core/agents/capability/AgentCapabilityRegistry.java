package com.agenticai.core.agents.capability;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.dto.AgentCapabilityResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Collects every {@link AgentCapability} bean into a key-addressable map -
 * the catalog behind {@code GET /api/agents/{id}/capabilities}. Adding a
 * capability bean is the whole registration: it shows up here (and therefore
 * in the API and the workbench) with no further wiring.
 */
@Component
public class AgentCapabilityRegistry {

    private final Map<String, AgentCapability> byKey;

    public AgentCapabilityRegistry(List<AgentCapability> capabilities) {
        Map<String, AgentCapability> map = new LinkedHashMap<>();
        for (AgentCapability capability : capabilities) {
            AgentCapability previous = map.put(capability.key(), capability);
            if (previous != null) {
                throw new IllegalStateException(
                    "Duplicate AgentCapability key '" + capability.key() + "' on "
                        + previous.getClass().getName() + " and " + capability.getClass().getName()
                );
            }
        }
        this.byKey = Map.copyOf(map);
    }

    /**
     * Every capability that applies to this agent, projected to the wire
     * shape - {@link AgentCapability#appliesTo} is the server-side filter that
     * replaced client-side provider gates (e.g. the old {@code providerName ===
     * "GROK"} check in the workbench).
     */
    public List<AgentCapabilityResponse> listFor(Agent agent) {
        return byKey.values().stream()
            .filter(capability -> capability.appliesTo(agent))
            .map(capability -> toResponse(capability, agent))
            .toList();
    }

    /**
     * Look up a capability by wire key - {@code null} when unknown. Callers
     * turning a null into a 400 keep "typo'd capability key" indistinguishable
     * from "capability removed" on purpose: the set of keys is not secret, but
     * a single not-found shape keeps the API boring.
     */
    public AgentCapability find(String key) {
        return key == null ? null : byKey.get(key);
    }

    /** Read-side helper for runtime gates that prefer key lookups over a dedicated field. */
    public boolean isEnabled(Agent agent, String key) {
        AgentCapability capability = find(key);
        return capability != null && capability.isEnabled(agent);
    }

    private AgentCapabilityResponse toResponse(AgentCapability capability, Agent agent) {
        return new AgentCapabilityResponse(
            capability.key(),
            capability.label(),
            capability.description(),
            capability.category(),
            capability.isEnabled(agent),
            capability.availability(),
            capability.availabilityHint()
        );
    }
}
