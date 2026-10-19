package com.agenticai.core.agents.crew;

import java.util.UUID;

/** A resolved, currently-existing specialist agent for one capability tag - {@link SpecialistHandoffService#findSpecialist}'s result. */
public record SpecialistCandidate(UUID agentId, String agentName, String capabilityTag) {
}
