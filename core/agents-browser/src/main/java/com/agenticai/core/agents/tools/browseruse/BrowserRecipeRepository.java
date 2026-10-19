package com.agenticai.core.agents.tools.browseruse;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link BrowserRecipe}; tenant-routed like every other JPA repository. */
public interface BrowserRecipeRepository extends JpaRepository<BrowserRecipe, UUID> {

    List<BrowserRecipe> findAllByOrderByNameAsc();

    List<BrowserRecipe> findByAgentIdOrAgentIdIsNullOrderByNameAsc(UUID agentId);

    boolean existsByAgentIdAndNameIgnoreCase(UUID agentId, String name);

    boolean existsByAgentIdIsNullAndNameIgnoreCase(String name);
}
