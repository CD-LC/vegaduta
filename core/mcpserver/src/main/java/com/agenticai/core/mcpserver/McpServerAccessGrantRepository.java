package com.agenticai.core.mcpserver;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpServerAccessGrantRepository extends JpaRepository<McpServerAccessGrant, UUID> {

    /** Looked up by {@link McpApiKeyAuthFilter} AFTER {@code TenantContext} has already been switched to the resolved tenant schema - see that class's javadoc for the two-step (control_plane hash lookup, then tenant-schema row lookup) resolution. */
    Optional<McpServerAccessGrant> findByApiKeyHash(String apiKeyHash);

    List<McpServerAccessGrant> findAllByOrderByCreatedAtDesc();
}
