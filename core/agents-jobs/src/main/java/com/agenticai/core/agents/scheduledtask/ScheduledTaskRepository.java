package com.agenticai.core.agents.scheduledtask;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for {@link ScheduledTask}. The one non-CRUD member is {@link #claimOccurrence}. */
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, UUID> {

    /** The tick's read: enabled tasks whose slot has passed, oldest first, capped so one tick stays bounded. */
    List<ScheduledTask> findTop200ByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(Instant now);

    List<ScheduledTask> findByOwnerSubjectOrderByCreatedAtDesc(String ownerSubject);

    List<ScheduledTask> findAllByOrderByCreatedAtDesc();

    /**
     * Claims one occurrence by advancing {@code next_run_at} from the value this replica OBSERVED to the next slot
     * - a compare-and-set, the same idiom as {@code AgentScheduleRepository#claimOccurrence}. Rowcount 1 means this
     * replica owns the firing; 0 means another replica won or the task was paused in between, and the caller
     * returns silently. {@code next} is computed from NOW, never from the missed slot, so downtime yields at most
     * one firing rather than a backlog.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update ScheduledTask t set t.nextRunAt = :next, t.updatedAt = :now "
        + "where t.id = :id and t.enabled = true and t.nextRunAt = :observed")
    int claimOccurrence(
        @Param("id") UUID id,
        @Param("next") Instant next,
        @Param("now") Instant now,
        @Param("observed") Instant observed
    );
}
