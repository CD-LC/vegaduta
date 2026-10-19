package com.agenticai.core.agents.tools.openapi;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpenApiToolSourceRepository extends JpaRepository<OpenApiToolSource, UUID> {

    List<OpenApiToolSource> findAllByOrderByCreatedAtDesc();

    boolean existsByToolPrefix(String toolPrefix);
}
