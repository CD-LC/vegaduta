package com.agenticai.core.rl.repo;

import com.agenticai.core.rl.domain.AgentPolicy;
import com.agenticai.core.rl.domain.PolicyDimension;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentPolicyRepository extends JpaRepository<AgentPolicy, UUID> {

    List<AgentPolicy> findByAgentId(UUID agentId);

    Optional<AgentPolicy> findByAgentIdAndDimension(UUID agentId, PolicyDimension dimension);
}
