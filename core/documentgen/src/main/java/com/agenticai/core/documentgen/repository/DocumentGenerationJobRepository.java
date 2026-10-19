package com.agenticai.core.documentgen.repository;

import com.agenticai.core.documentgen.entity.DocumentGenerationJob;
import com.agenticai.core.documentgen.model.DocumentGenerationStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentGenerationJobRepository extends JpaRepository<DocumentGenerationJob, UUID> {

    /** Backs the Files page's "Generated documents" listing, newest first. */
    Page<DocumentGenerationJob> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    /** Same listing, filtered by the Files page's search box (matches on filename, case-insensitively). */
    Page<DocumentGenerationJob> findByTenantIdAndFilenameContainingIgnoreCaseOrderByCreatedAtDesc(
        String tenantId, String search, Pageable pageable);

    /** Tenant-scoped single-job lookup - backs both status polling and the download endpoint's 404-on-missing/cross-tenant behavior. */
    Optional<DocumentGenerationJob> findByIdAndTenantId(UUID id, String tenantId);

    /** The tenant's own job rows double as the usage ledger for {@code DocumentGenerationQuotaService} - no separate counter table needed. */
    long countByTenantIdAndCreatedAtAfterAndStatusNot(String tenantId, Instant after, DocumentGenerationStatus excludedStatus);
}
