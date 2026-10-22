package com.agenticai.core.platform.ops;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records a platform operator's action in the TARGET tenant's own audit log - the same mechanism
 * {@code PlatformUserController} uses: the operator's token is tenant-less, so the write runs with
 * the target tenant's schema bound and the caller's context restored afterwards. A structured
 * {@code platform-ops-audit} log line is always written first and is the fallback record when the
 * tenant write fails (a failed audit write must not turn an already-applied action into an error
 * the operator would retry).
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Component
public class PlatformTenantAuditor {

    private static final Logger log = LoggerFactory.getLogger(PlatformTenantAuditor.class);

    private final AuditLogService auditLogService;

    public PlatformTenantAuditor(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    public void record(
            String tenantSchema,
            String actor,
            String action,
            String targetType,
            String targetId,
            String detail) {
        log.info(
                "platform-ops-audit actor={} action={} targetType={} target={} tenant={} detail={}",
                actor,
                action,
                targetType,
                targetId,
                tenantSchema,
                detail);
        if (tenantSchema == null || tenantSchema.isBlank()) {
            return;
        }
        try {
            asTenant(
                    tenantSchema,
                    actor,
                    () ->
                            auditLogService.record(
                                    actor,
                                    action,
                                    targetType,
                                    targetId,
                                    AuditOutcome.SUCCESS,
                                    detail));
        } catch (RuntimeException e) {
            log.error(
                    "Could not write tenant audit row for {} on {} (tenant {})",
                    action,
                    targetId,
                    tenantSchema,
                    e);
        }
    }

    /**
     * Runs {@code work} with {@code tenantSchema} bound, then restores whatever context the caller
     * had.
     */
    public static <T> T asTenant(String tenantSchema, String actor, Supplier<T> work) {
        Optional<TenantContext> previous = TenantContext.current();
        try {
            TenantContext.set(
                    TenantContext.of(
                            tenantSchema,
                            actor,
                            previous.map(TenantContext::getRoles).orElse(Set.of())));
            return work.get();
        } finally {
            if (previous.isPresent()) {
                TenantContext.set(previous.get());
            } else {
                TenantContext.clear();
            }
        }
    }
}
