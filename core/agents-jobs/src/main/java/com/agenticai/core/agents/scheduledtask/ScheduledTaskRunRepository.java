package com.agenticai.core.agents.scheduledtask;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for {@link ScheduledTaskRun}. */
public interface ScheduledTaskRunRepository extends JpaRepository<ScheduledTaskRun, UUID> {

    /** Newest first; pass a {@code PageRequest} to bound it. */
    List<ScheduledTaskRun> findByTaskIdOrderByStartedAtDesc(UUID taskId, Pageable pageable);

    /** Newest first with {@code id} as tiebreaker so consecutive offset pages never repeat or skip a row. */
    List<ScheduledTaskRun> findByTaskIdOrderByStartedAtDescIdDesc(UUID taskId, Pageable pageable);

    long countByTaskId(UUID taskId);

    /** The settle sweep's read: runs still waiting on their job, oldest first. */
    List<ScheduledTaskRun> findTop100BySettledFalseOrderByStartedAtAsc();

    /**
     * Claims one unsettled run for settlement (compare-and-set on {@code settled}). Rowcount 1 = this sweep owns
     * the delivery; 0 = another replica already did, so nothing is delivered twice. At-most-once on purpose: a
     * crash between the claim and the delivery loses one delivery (the job's result is still on the job row and in
     * the run history) rather than sending the same message twice.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update ScheduledTaskRun r set r.settled = true where r.id = :id and r.settled = false")
    int claimForSettlement(@Param("id") UUID id);
}
