package com.agenticai.core.brief;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BriefVersionRepository extends JpaRepository<BriefVersionEntity, UUID> {

    List<BriefVersionEntity> findByBriefIdOrderByVersionDesc(UUID briefId);

    Optional<BriefVersionEntity> findByBriefIdAndVersion(UUID briefId, int version);
}
