package com.agenticai.core.brief;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BriefBindingRepository extends JpaRepository<BriefBindingEntity, BriefBindingEntity.Key> {

    List<BriefBindingEntity> findByBriefId(UUID briefId);

    /** Which briefs own this entity (an agent id, for a guardrail strike). */
    List<BriefBindingEntity> findByKindAndEntityId(String kind, String entityId);

    /** Every binding pointing at this entity across kinds - the coach drawer's "which envoy owns this" lookup. */
    List<BriefBindingEntity> findByEntityId(String entityId);
}
