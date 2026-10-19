package com.agenticai.core.agents.learning.review;

import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Claims and records the daily review per person. */
public interface LearningReviewRunRepository extends JpaRepository<LearningReviewRun, LearningReviewRun.Key> {

    /** 1 when this call claimed (subject, day), 0 when it was already claimed. Atomic via the primary key. */
    @Transactional
    @Modifying
    @Query(
            value =
                    "INSERT INTO learning_review_runs (subject, run_date, filed, created_at) "
                            + "VALUES (:subject, :day, 0, now()) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int claim(@Param("subject") String subject, @Param("day") LocalDate day);

    @Transactional
    @Modifying
    @Query(
            value = "UPDATE learning_review_runs SET filed = :filed WHERE subject = :subject AND run_date = :day",
            nativeQuery = true)
    int recordFiled(@Param("subject") String subject, @Param("day") LocalDate day, @Param("filed") int filed);

    /** Gives a claim back after a failed review, so the person is retried on the next tick rather than tomorrow. */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM learning_review_runs WHERE subject = :subject AND run_date = :day", nativeQuery = true)
    int release(@Param("subject") String subject, @Param("day") LocalDate day);

    /** Forgets claims older than {@code before}, so the table does not grow forever. */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM learning_review_runs WHERE run_date < :before", nativeQuery = true)
    int deleteOlderThan(@Param("before") LocalDate before);
}
