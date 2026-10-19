package com.agenticai.core.agents.skills;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AgentSkillConfigRepository extends JpaRepository<AgentSkillConfig, UUID> {

    /**
     * Every (agent id, skill name) allow-list pair in one statement. The console's skill list needs
     * "which agents allow this skill" for the whole catalog; going through {@code findAll()} would
     * load the eager name collection once per agent.
     */
    @Query("select c.agentId, n from AgentSkillConfig c join c.allowedSkillNames n")
    List<Object[]> findAllowedPairs();
}
