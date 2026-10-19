package com.agenticai.core.brief;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BriefTrustEventRepository extends JpaRepository<BriefTrustEventEntity, UUID> {

    List<BriefTrustEventEntity> findByBriefIdOrderByCreatedAtDesc(UUID briefId);
}
