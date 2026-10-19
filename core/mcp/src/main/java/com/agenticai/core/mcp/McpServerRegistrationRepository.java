package com.agenticai.core.mcp;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpServerRegistrationRepository extends JpaRepository<McpServerRegistration, UUID> {

    Optional<McpServerRegistration> findByName(String name);

    List<McpServerRegistration> findByEnabledTrue();

    /** Registrations stamped with the given structural OAuth marker (e.g. {@code oauth:github}). */
    List<McpServerRegistration> findByOauthProvider(String oauthProvider);
}
