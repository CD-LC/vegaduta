package com.agenticai.core.workflow.share;

import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowShareRepository extends JpaRepository<WorkflowShare, UUID> {

    Optional<WorkflowShare> findByWorkflowId(UUID workflowId);

    Optional<WorkflowShare> findByShareId(String shareId);

    /**
     * Row-locked load for counter mutations (viewer count, abuse reports): concurrent anonymous
     * visitors must not lose each other's increments.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from WorkflowShare s where s.id = :id")
    Optional<WorkflowShare> findByIdForUpdate(@Param("id") UUID id);
}
