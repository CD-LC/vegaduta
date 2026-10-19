package com.agenticai.core.mcp.oauth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpOAuthConnectionRepository extends JpaRepository<McpOAuthConnectionEntity, UUID> {

    Optional<McpOAuthConnectionEntity> findByResourceUrl(String resourceUrl);
}
