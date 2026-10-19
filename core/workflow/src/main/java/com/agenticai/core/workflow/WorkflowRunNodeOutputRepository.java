package com.agenticai.core.workflow;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WorkflowRunNodeOutputRepository extends JpaRepository<WorkflowRunNodeOutput, WorkflowRunNodeOutput.Key> {

    /** Highest iteration wins; ties (several nodes sharing a label) go to the most recently written. */
    Optional<WorkflowRunNodeOutput> findFirstByRunIdAndNodeLabelOrderBySeqDescCreatedAtDesc(UUID runId, String nodeLabel);

    Optional<WorkflowRunNodeOutput> findFirstByRunIdAndNodeIdOrderBySeqDesc(UUID runId, String nodeId);

    List<WorkflowRunNodeOutput> findByRunIdOrderByCreatedAtAscSeqAsc(UUID runId);

    /**
     * TTL sweep; {@code @Transactional} here because the scheduled caller has no surrounding transaction.
     * Rows of a run that is parked (waiting for approval / input / voice, or paused) are kept: it resumes
     * later and its next node must still read the outputs of the nodes before the pause.
     */
    @Transactional
    @Modifying
    @Query("delete from WorkflowRunNodeOutput o where o.createdAt < :cutoff and not exists "
        + "(select 1 from WorkflowRun r where r.id = o.runId and r.status in :parked)")
    int deleteOlderThan(@Param("cutoff") Instant cutoff, @Param("parked") Collection<WorkflowRunStatus> parked);
}
