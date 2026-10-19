package com.agenticai.core.tenancy;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin, single-purpose wrapper around {@link PlatformTenantDeletionLogRepository}
 * - see {@link PlatformTenantDeletionLog}'s javadoc for why this durable,
 * control-plane audit trail exists as its own table rather than reusing
 * {@link AuditLogEntry}/{@code AuditLogService}. {@link
 * TenantDataErasureService#eraseTenantById} is the sole caller: {@link
 * #recordInitiated} MUST succeed before any deletion step runs (the
 * audit-before-delete gate - a write failure here aborts the whole
 * deletion), and {@link #recordOutcome} is called exactly once more, at the
 * end, to close the same row out.
 */
@Service
public class PlatformTenantDeletionAuditService {

    private final PlatformTenantDeletionLogRepository repository;

    public PlatformTenantDeletionAuditService(PlatformTenantDeletionLogRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PlatformTenantDeletionLog recordInitiated(UUID tenantId, String tenantName, String actorId) {
        return repository.save(new PlatformTenantDeletionLog(tenantId, tenantName, actorId));
    }

    @Transactional
    public void recordCompleted(UUID logId, String stepsCompleted) {
        PlatformTenantDeletionLog log = repository.findById(logId)
            .orElseThrow(() -> new IllegalStateException("No platform_tenant_deletion_log row with id " + logId));
        log.markCompleted(stepsCompleted);
        repository.save(log);
    }

    @Transactional
    public void recordFailed(UUID logId, String failureDetail, String stepsCompleted) {
        PlatformTenantDeletionLog log = repository.findById(logId)
            .orElseThrow(() -> new IllegalStateException("No platform_tenant_deletion_log row with id " + logId));
        log.markFailed(failureDetail, stepsCompleted);
        repository.save(log);
    }
}
