package com.agenticai.core.agents.learning.routines;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for {@link RoutineObservation}. Every read is scoped to one subject and bounded. */
public interface RoutineObservationRepository extends JpaRepository<RoutineObservation, UUID> {

    /** The newest 500 requests of one person since {@code since}, newest first. */
    List<RoutineObservation> findTop500BySubjectAndObservedAtAfterOrderByObservedAtDesc(
            String subject, Instant since);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("delete from RoutineObservation o where o.observedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
