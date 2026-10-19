package com.agenticai.core.agents.tools.script;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserToolScriptRepository extends JpaRepository<UserToolScript, UUID> {

    List<UserToolScript> findAllByOrderByCreatedAtDesc();

    List<UserToolScript> findAllByEnabledTrue();

    Optional<UserToolScript> findByToolName(String toolName);

    boolean existsByToolNameAndIdNot(String toolName, UUID id);

    boolean existsByToolName(String toolName);
}
