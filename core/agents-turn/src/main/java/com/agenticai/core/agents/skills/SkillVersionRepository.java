package com.agenticai.core.agents.skills;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillVersionRepository extends JpaRepository<SkillVersion, UUID> {

    List<SkillVersion> findBySkillIdOrderByVersionDesc(UUID skillId);

    Optional<SkillVersion> findFirstBySkillIdOrderByVersionDesc(UUID skillId);

    void deleteBySkillId(UUID skillId);

    boolean existsBySkillIdAndVersion(UUID skillId, int version);
}
