package com.agenticai.core.agents.capability;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Rows of {@link AgentCapabilityState} - see that class's javadoc for the model. */
public interface AgentCapabilityStateRepository extends JpaRepository<AgentCapabilityState, AgentCapabilityStateId> {
}
