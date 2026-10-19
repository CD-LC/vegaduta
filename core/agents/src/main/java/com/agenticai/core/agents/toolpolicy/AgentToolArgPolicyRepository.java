package com.agenticai.core.agents.toolpolicy;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentToolArgPolicyRepository extends JpaRepository<AgentToolArgPolicy, UUID> {
}
