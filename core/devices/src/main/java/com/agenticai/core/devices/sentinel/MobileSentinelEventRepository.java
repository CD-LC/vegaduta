package com.agenticai.core.devices.sentinel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface MobileSentinelEventRepository extends JpaRepository<MobileSentinelEvent, UUID> {

    boolean existsByUserIdAndClientEventId(String userId, String clientEventId);

    List<MobileSentinelEvent> findByUserIdOrderByOccurredAtDesc(String userId, Pageable pageable);

    List<MobileSentinelEvent> findByUserIdAndSentinelIdOrderByOccurredAtDesc(
        String userId, String sentinelId, Pageable pageable);

    /** Latest escalation for this (user, sentinel) - the 30-minute rate limit reads it. */
    Optional<MobileSentinelEvent> findFirstByUserIdAndSentinelIdAndEscalatedAtIsNotNullOrderByEscalatedAtDesc(
        String userId, String sentinelId);

    /** Per-user hourly escalation budget (caps caregiver-email volume regardless of sentinelId). */
    long countByUserIdAndEscalatedAtGreaterThanEqual(String userId, Instant since);

    /** Retention purge across the whole tenant table. */
    @Transactional
    @Modifying
    @Query("delete from MobileSentinelEvent e where e.occurredAt < :cutoff")
    int deleteOccurredBefore(@Param("cutoff") Instant cutoff);

    /** Right to erasure: the caller's own rows only. */
    @Transactional
    @Modifying
    @Query("delete from MobileSentinelEvent e where e.userId = :userId")
    int deleteAllOfUser(@Param("userId") String userId);

    // --- admin aggregate (counts only) -------------------------------------

    @Query("select count(distinct e.userId) from MobileSentinelEvent e where e.occurredAt >= :since")
    long countActiveUsersSince(@Param("since") Instant since);

    long countByOccurredAtGreaterThanEqual(Instant since);

    long countByEscalatedAtGreaterThanEqual(Instant since);

    @Query("select e.severity, count(e) from MobileSentinelEvent e where e.occurredAt >= :since group by e.severity")
    List<Object[]> countBySeveritySince(@Param("since") Instant since);
}
