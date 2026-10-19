package com.agenticai.core.agents.tools;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for {@link StepJournalEntry}; tenant-routed like every other JPA repository. */
public interface StepJournalRepository extends JpaRepository<StepJournalEntry, UUID> {

    Optional<StepJournalEntry> findByRunIdAndStepId(String runId, String stepId);

    @Transactional
    @Modifying
    @Query("delete from StepJournalEntry e where e.runId in :runIds")
    int deleteByRunIdIn(@Param("runIds") Collection<String> runIds);

    boolean existsByRunIdAndKindAndStatus(String runId, String kind, String status);

    @Transactional
    @Modifying
    @Query("delete from StepJournalEntry e where e.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
