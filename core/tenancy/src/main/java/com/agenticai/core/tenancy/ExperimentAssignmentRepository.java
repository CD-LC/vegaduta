package com.agenticai.core.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperimentAssignmentRepository extends JpaRepository<ExperimentAssignment, UUID> {

    Optional<ExperimentAssignment> findByFlagKeyAndTenantId(String flagKey, String tenantId);

    List<ExperimentAssignment> findByFlagKeyOrderByAssignedAtDesc(String flagKey);
}
