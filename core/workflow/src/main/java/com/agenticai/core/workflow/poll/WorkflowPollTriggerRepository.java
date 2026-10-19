package com.agenticai.core.workflow.poll;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkflowPollTriggerRepository extends JpaRepository<WorkflowPollTrigger, UUID> {

    Optional<WorkflowPollTrigger> findByNodeId(String nodeId);

    /** The ticker's due set: enabled rows whose poll slot has arrived. */
    List<WorkflowPollTrigger> findByEnabledTrueAndNextPollAtLessThanEqual(Instant now);

    void deleteByNodeId(String nodeId);
}
