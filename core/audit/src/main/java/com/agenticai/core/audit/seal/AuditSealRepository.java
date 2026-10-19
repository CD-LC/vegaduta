package com.agenticai.core.audit.seal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-schema repository for {@link AuditSeal}. Every parameter is non-null by construction (callers
 * substitute an epoch / far-future bound for an open end) so Postgres never has to infer the type of a
 * bare NULL bind - the trap {@code AuditLogRepository}'s header comment describes.
 */
public interface AuditSealRepository extends JpaRepository<AuditSeal, UUID> {

    /** The chain head: the newest seal of this tenant. */
    Optional<AuditSeal> findTopByOrderBySeqDesc();

    Optional<AuditSeal> findBySeq(long seq);

    /** Newest first - the listing endpoint. */
    Page<AuditSeal> findAllByOrderBySeqDesc(Pageable pageable);

    /** Seals whose covered row range overlaps [from, to], oldest first. */
    List<AuditSeal> findByLastRowAtGreaterThanEqualAndFirstRowAtLessThanEqualOrderBySeqAsc(
        Instant from, Instant to, Pageable pageable
    );

    /**
     * Retention marker: flags every not-yet-purged seal whose whole range ends before {@code cutoff}. The
     * V422 trigger allows this one column to change and nothing else.
     */
    @Modifying
    @Query("UPDATE AuditSeal s SET s.purgedAt = :at WHERE s.purgedAt IS NULL AND s.lastRowAt < :cutoff")
    int markPurgedBefore(@Param("cutoff") Instant cutoff, @Param("at") Instant at);
}
