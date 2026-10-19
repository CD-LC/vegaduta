package com.agenticai.core.agents.tools.code;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * No {@code tenantId} parameter anywhere by design - schema-per-tenant means every query here is
 * already scoped to the caller's schema by the connection's search_path. Same shape as {@code
 * UserToolScriptRepository}.
 */
public interface CustomCodeToolRepository extends JpaRepository<CustomCodeTool, UUID> {

    List<CustomCodeTool> findAllByOrderByCreatedAtDesc();

    /**
     * What {@code AgentToolProvider} needs at chat-turn time - see {@link
     * CustomCodeTool#isRunnable()}.
     */
    List<CustomCodeTool> findAllByEnabledTrueAndStatus(CustomCodeToolStatus status);

    Optional<CustomCodeTool> findByToolName(String toolName);

    boolean existsByToolName(String toolName);

    boolean existsByToolNameAndIdNot(String toolName, UUID id);
}
