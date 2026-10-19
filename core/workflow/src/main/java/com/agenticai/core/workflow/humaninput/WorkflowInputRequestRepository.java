package com.agenticai.core.workflow.humaninput;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowInputRequestRepository extends JpaRepository<WorkflowInputRequest, UUID> {

    List<WorkflowInputRequest> findByStatusOrderByCreatedAtDesc(WorkflowInputRequestStatus status);

    List<WorkflowInputRequest> findAllByOrderByCreatedAtDesc();

    List<WorkflowInputRequest> findByRunIdOrderByCreatedAtAsc(UUID runId);

    /** At most one live request per run - the halted node creates exactly one before suspending. */
    Optional<WorkflowInputRequest> findByRunIdAndStatus(UUID runId, WorkflowInputRequestStatus status);
}
