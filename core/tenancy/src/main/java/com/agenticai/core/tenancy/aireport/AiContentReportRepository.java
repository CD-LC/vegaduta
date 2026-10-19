package com.agenticai.core.tenancy.aireport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AiContentReportRepository extends JpaRepository<AiContentReport, UUID> {

    long countByReporterIdAndCreatedAtAfter(String reporterId, Instant since);

    List<AiContentReport> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    List<AiContentReport> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(String status);

    /** Account deletion: keep the moderation record, drop the link to the person. */
    @Transactional
    @Modifying
    @Query("update AiContentReport r set r.reporterId = null where r.reporterId = :reporterId")
    int anonymiseReporter(@Param("reporterId") String reporterId);
}
