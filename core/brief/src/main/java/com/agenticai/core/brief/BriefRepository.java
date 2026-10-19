package com.agenticai.core.brief;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-schema persistence for {@link BriefEntity}; scoping is by schema, not by a column. */
public interface BriefRepository extends JpaRepository<BriefEntity, UUID> {

    List<BriefEntity> findAllByOrderByUpdatedAtDesc();
}
