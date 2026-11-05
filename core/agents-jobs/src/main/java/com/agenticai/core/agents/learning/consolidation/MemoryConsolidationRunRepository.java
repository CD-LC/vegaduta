package com.agenticai.core.agents.learning.consolidation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Claims and reports the nightly consolidation per person. */
public interface MemoryConsolidationRunRepository
        extends JpaRepository<MemoryConsolidationRun, MemoryConsolidationRun.Key> {

    /** 1 when this call claimed (subject, day), 0 when it was already claimed. Atomic via the primary key. */
    @Transactional
    @Modifying
    @Query(
            value = "INSERT INTO memory_consolidation_runs (subject, run_date, mode, status, created_at) "
                    + "VALUES (:subject, :day, 'dry_run', 'running', now()) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int claim(@Param("subject") String subject, @Param("day") LocalDate day);

    @Transactional
    @Modifying
    @Query(
            value = "UPDATE memory_consolidation_runs SET mode = :mode, status = :status, rows_seen = :rows,"
                    + " forgets = :forgets, merges = :merges, summaries = :summaries, deferred = :deferred,"
                    + " tombstoned = :tombstoned, proposals = :proposals WHERE subject = :subject AND run_date = :day",
            nativeQuery = true)
    int record(
            @Param("subject") String subject,
            @Param("day") LocalDate day,
            @Param("mode") String mode,
            @Param("status") String status,
            @Param("rows") int rows,
            @Param("forgets") int forgets,
            @Param("merges") int merges,
            @Param("summaries") int summaries,
            @Param("deferred") int deferred,
            @Param("tombstoned") int tombstoned,
            @Param("proposals") int proposals);

    /** Gives a claim back after a failed run, so the person is retried on the next tick rather than tomorrow. */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM memory_consolidation_runs WHERE subject = :subject AND run_date = :day", nativeQuery = true)
    int release(@Param("subject") String subject, @Param("day") LocalDate day);

    /** Forgets claims older than {@code before}, so the table does not grow forever. */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM memory_consolidation_runs WHERE run_date < :before", nativeQuery = true)
    int deleteOlderThan(@Param("before") LocalDate before);

    /** The first day consolidation ran in this tenant, or empty - feeds the dry-run window. */
    @Query(value = "SELECT min(run_date) FROM memory_consolidation_runs", nativeQuery = true)
    Optional<LocalDate> firstRunDate();
}
