package com.agenticai.core.mcpserver.expose;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpToolExposureRepository extends JpaRepository<McpToolExposure, UUID> {

    Optional<McpToolExposure> findByKindAndResourceId(McpExposureKind kind, UUID resourceId);

    List<McpToolExposure> findByExposedTrue();
}
