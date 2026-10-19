package com.agenticai.core.agents.learning.preferences;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for {@link PreferenceSignal}. Every read is scoped to one subject. */
public interface PreferenceSignalRepository extends JpaRepository<PreferenceSignal, UUID> {

    long countBySubjectAndSignalKeyAndCreatedAtAfter(String subject, String signalKey, Instant after);

    @Modifying
    @Transactional
    @Query("delete from PreferenceSignal s where s.subject = :subject and s.createdAt < :before")
    int deleteOlderThan(@Param("subject") String subject, @Param("before") Instant before);
}
