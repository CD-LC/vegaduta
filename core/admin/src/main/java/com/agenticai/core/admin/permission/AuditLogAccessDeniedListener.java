package com.agenticai.core.admin.permission;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.AccessDeniedEventListener;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Writes authorization denials (HTTP 403s) into the tenant's audit log as
 * {@code authz.denied} events - the denied-attempt trail the platform
 * previously lacked entirely. Deliberately lives here rather than in
 * {@code core/security}: {@code audit_log_entry} is a tenant-schema table
 * written through {@link AuditLogService}, and this module already depends
 * on both {@code audit} and {@code security}.
 *
 * <p>Recorded under the denied caller's own tenant context (the {@code
 * AccessDeniedHandler} runs inside {@code TenantContextFilter}, so the
 * schema resolver still routes the write to the denied tenant's audit log -
 * the person who tried is the person whose workspace log it lands in, which
 * is also the admin who needs to see it).
 *
 * <p><b>Throttled, not sampled.</b> A probing or malfunctioning client could
 * otherwise turn its own 403s into audit-log spam, so repeats of the same
 * {@code subject + method + path} triple are suppressed for {@link
 * #SUPPRESS_SECONDS} - the first attempt always records, the flood does
 * not. A platform-admin's denials ({@code tenantId == null}) are skipped
 * rather than written into a schema that isn't theirs.
 */
@Component
public class AuditLogAccessDeniedListener implements AccessDeniedEventListener {

    private static final long SUPPRESS_SECONDS = 60;

    private final AuditLogService auditLogService;
    private final Map<String, Instant> recent = new ConcurrentHashMap<>();

    public AuditLogAccessDeniedListener(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    public void onAccessDenied(String tenantId, String subject, String method, String path, String detail) {
        if (tenantId == null || subject == null) {
            return;
        }
        String key = subject + "|" + method + "|" + path;
        Instant now = Instant.now();
        Instant last = recent.get(key);
        if (last != null && now.isBefore(last.plusSeconds(SUPPRESS_SECONDS))) {
            return;
        }
        recent.put(key, now);
        auditLogService.record(
            subject, "authz.denied", "http_request", method + " " + path,
            AuditOutcome.FAILURE, "Access denied by security policy"
        );
    }
}
