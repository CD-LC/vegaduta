package com.agenticai.core.audit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformIssueRepository extends JpaRepository<PlatformIssue, UUID> {

    Optional<PlatformIssue> findByFingerprint(String fingerprint);

    List<PlatformIssue> findTop200ByOrderByLastSeenAtDesc();

    List<PlatformIssue> findTop200ByStatusOrderByLastSeenAtDesc(IssueStatus status);

    /** Paged variants for {@code GET /api/admin/issues?limit=&offset=}; {@code id} breaks lastSeenAt ties. */
    List<PlatformIssue> findByOrderByLastSeenAtDescIdDesc(org.springframework.data.domain.Pageable pageable);

    List<PlatformIssue> findByStatusOrderByLastSeenAtDescIdDesc(
        IssueStatus status, org.springframework.data.domain.Pageable pageable);

    /** Exact per-tenant count for the platform overview's open-issue tile (no row cap). */
    long countByStatus(IssueStatus status);

    /**
     * Phase P5 - the fraud/anomaly monitor's "recent anomalies" read, scoped to {@link
     * IssueSource#ABUSE_ANOMALY}.
     */
    List<PlatformIssue> findTop200BySourceOrderByLastSeenAtDesc(IssueSource source);
}
