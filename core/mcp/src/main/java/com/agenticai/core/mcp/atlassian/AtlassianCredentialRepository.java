package com.agenticai.core.mcp.atlassian;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AtlassianCredentialRepository extends JpaRepository<AtlassianCredential, UUID> {

    Optional<AtlassianCredential> findFirstByOrderByCreatedAtAsc();
}
