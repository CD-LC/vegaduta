package com.agenticai.core.agents.tools.desktop;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link DesktopRecipe}; tenant-routed like every other JPA repository. */
public interface DesktopRecipeRepository extends JpaRepository<DesktopRecipe, UUID> {

    List<DesktopRecipe> findAllByOrderByNameAsc();

    List<DesktopRecipe> findByAgentIdOrAgentIdIsNullOrderByNameAsc(UUID agentId);

    boolean existsByAgentIdAndNameIgnoreCase(UUID agentId, String name);

    boolean existsByAgentIdIsNullAndNameIgnoreCase(String name);
}
