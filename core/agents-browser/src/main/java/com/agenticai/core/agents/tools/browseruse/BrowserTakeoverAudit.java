package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The takeover audit trail, extracted from {@link BrowserTakeoverService}: one audit entry per
 * lifecycle event, written on a dedicated daemon thread. The tenant context is built on that thread
 * from values captured on the calling thread and restored in {@code finally} (unchanged behaviour).
 */
final class BrowserTakeoverAudit {

    private static final Logger log = LoggerFactory.getLogger(BrowserTakeoverAudit.class);

    private final ObjectProvider<AuditLogService> audit;
    private final Supplier<Instant> now;
    private final ExecutorService auditExecutor =
            TenantAwareExecutors.newSingleThreadExecutor("browser-takeover-audit", true);

    BrowserTakeoverAudit(ObjectProvider<AuditLogService> audit, Supplier<Instant> now) {
        this.audit = audit;
        this.now = now;
    }

    void shutdown() {
        auditExecutor.shutdown();
    }


    /**
     * One audit entry, off the calling thread (the Playwright thread and the reaper must never wait
     * on a DB write) and never carrying content: ids, the reason KIND, the end cause and counts
     * only.
     */
    void record(String action, BrowserTakeoverControl control) {
        String actor = control.ownerUserId() == null ? "system" : control.ownerUserId();
        String tenant = control.tenantId();
        String detail = detail(action, control);
        String targetId = control.takeoverId().toString();
        AuditOutcome outcome = AuditOutcome.SUCCESS;
        try {
            auditExecutor.submit(
                    () -> {
                        AuditLogService service = audit == null ? null : audit.getIfAvailable();
                        if (service == null) {
                            return;
                        }
                        Optional<TenantContext> previous = TenantContext.current();
                        TenantContext.set(TenantContext.of(tenant, actor, Set.of()));
                        try {
                            service.record(
                                    actor, action, "BrowserSession", targetId, outcome, detail);
                        } catch (RuntimeException e) {
                            log.warn(
                                    "browser takeover audit entry could not be written: {}",
                                    e.getClass().getSimpleName());
                        } finally {
                            if (previous.isPresent()) {
                                TenantContext.set(previous.get());
                            } else {
                                TenantContext.clear();
                            }
                        }
                    });
        } catch (RuntimeException e) {
            log.debug("browser takeover audit executor rejected a task");
        }
    }

    private String detail(String action, BrowserTakeoverControl control) {
        StringBuilder sb = new StringBuilder("agent=").append(control.agentId());
        sb.append("; kind=").append(control.kind());
        BrowserTakeoverControl.Origin origin = control.origin();
        sb.append("; requestedBy=")
                .append(origin == null ? "n/a" : origin.name().toLowerCase(Locale.ROOT));
        if (BrowserTakeoverService.AUDIT_ENDED.equals(action)) {
            Instant started = control.controlStartedAt();
            // controlStartedAt is cleared on a clean hand-back; derive the duration from the clocks
            // we still have.
            sb.append("; endedBy=").append(control.endedBy());
            sb.append("; inputEvents=").append(control.inputEvents());
            if (started != null) {
                sb.append("; seconds=").append(Duration.between(started, now.get()).getSeconds());
            }
        }
        return sb.toString();
    }
}
