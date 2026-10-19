package com.agenticai.core.workflow.voicemenu;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowVoiceMenuRequestRepository extends JpaRepository<WorkflowVoiceMenuRequest, UUID> {

    /** At most one live request per run - the halted node creates exactly one before suspending. */
    Optional<WorkflowVoiceMenuRequest> findByRunIdAndStatus(UUID runId, WorkflowVoiceMenuRequestStatus status);
}
