package com.agenticai.core.quality.simulation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Tenant-scoped repository for {@link SimulationRun}. */
@Repository
public interface SimulationRunRepository extends JpaRepository<SimulationRun, UUID> {

    List<SimulationRun> findBySessionIdOrderByStartedAtAsc(UUID sessionId);

    boolean existsBySessionIdAndStatusIn(UUID sessionId, List<SimulationRunStatus> statuses);

    /**
     * Boot orphan sweep (see {@link SimulationRunExecutor}) - the same
     * pattern {@code EvalRunRepository#failAllLiveRuns} documents: runs
     * execute on an in-process executor that dies with the JVM, so any
     * non-terminal row found at startup is unowned by construction.
     * {@code @Transactional} sits on the repository method for the same
     * reason stated there - the caller is a {@code TenantSchemaIterator}
     * lambda inside an {@code @EventListener}, with no surrounding
     * transaction of its own.
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("""
        UPDATE SimulationRun r
        SET r.status = com.agenticai.core.quality.simulation.SimulationRunStatus.ERROR,
            r.errorMessage = :reason,
            r.completedAt = :now
        WHERE r.status IN (com.agenticai.core.quality.simulation.SimulationRunStatus.QUEUED,
                           com.agenticai.core.quality.simulation.SimulationRunStatus.RUNNING)
        """)
    int failAllLiveRuns(@Param("reason") String reason, @Param("now") Instant now);

    /**
     * As {@link #failAllLiveRuns} but only runs whose session was created before {@code cutoff} (a run has no
     * created_at of its own; a QUEUED run has no started_at yet): the multi-replica-safe sweep
     * ({@code app.quality.boot-orphan-sweep=stale}).
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("""
        UPDATE SimulationRun r
        SET r.status = com.agenticai.core.quality.simulation.SimulationRunStatus.ERROR,
            r.errorMessage = :reason,
            r.completedAt = :now
        WHERE r.status IN (com.agenticai.core.quality.simulation.SimulationRunStatus.QUEUED,
                           com.agenticai.core.quality.simulation.SimulationRunStatus.RUNNING)
          AND r.sessionId IN (SELECT s.id FROM SimulationSession s WHERE s.createdAt < :cutoff)
        """)
    int failStaleLiveRuns(@Param("reason") String reason, @Param("now") Instant now, @Param("cutoff") Instant cutoff);
}
