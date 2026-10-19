package com.agenticai.core.chat.gateway;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmGatewayContentPolicyRepository extends JpaRepository<LlmGatewayContentPolicy, UUID> {

    Optional<LlmGatewayContentPolicy> findByTenantId(String tenantId);
}
