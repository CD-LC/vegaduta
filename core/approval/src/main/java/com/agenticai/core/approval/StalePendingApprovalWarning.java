package com.agenticai.core.approval;

import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup WARN: approvals never expire by default ({@code app.approvals.expiry.ttl=0}), so a PENDING
 * request can stay approvable for ever, and approving one weeks later replays a stale, possibly
 * non-idempotent action. When the TTL is off and requests older than {@link #STALE_AFTER} are still
 * PENDING, say so once at startup. Never changes any request and never fails startup.
 */
@Component
public class StalePendingApprovalWarning {

    static final Duration STALE_AFTER = Duration.ofDays(7);

    private static final Logger log = LoggerFactory.getLogger(StalePendingApprovalWarning.class);

    private final ApprovalExpiryProperties properties;
    private final TenantSchemaIterator schemaIterator;
    private final ApprovalRequestRepository requestRepository;
    private volatile SettingsResolver settings;

    public StalePendingApprovalWarning(
            ApprovalExpiryProperties properties,
            TenantSchemaIterator schemaIterator,
            ApprovalRequestRepository requestRepository) {
        this.properties = properties;
        this.schemaIterator = schemaIterator;
        this.requestRepository = requestRepository;
    }

    @Autowired(required = false)
    public void setSettingsResolver(SettingsResolver settings) {
        this.settings = settings;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            warnIfStale(Instant.now());
        } catch (RuntimeException e) {
            log.debug("Stale pending approval check skipped: {}", e.toString());
        }
    }

    /** Returns how many stale PENDING requests it found (across tenants with expiry off). Package-visible for tests. */
    int warnIfStale(Instant now) {
        AtomicInteger total = new AtomicInteger();
        Instant cutoff = now.minus(STALE_AFTER);
        schemaIterator.forEachActiveTenantSchema(schema -> {
            if (ttlIsOn()) {
                return;
            }
            try {
                long stale = requestRepository.countByStatusAndCreatedAtBefore(ApprovalStatus.PENDING, cutoff);
                if (stale > 0) {
                    total.addAndGet((int) Math.min(stale, Integer.MAX_VALUE));
                    log.warn(
                            "{} approval request(s) have been PENDING for more than {} days in schema '{}' and "
                                    + "approvals never expire (app.approvals.expiry.ttl=0): approving one now would "
                                    + "replay a stale action. Decide them, or set app.approvals.expiry.ttl.",
                            stale,
                            STALE_AFTER.toDays(),
                            schema);
                }
            } catch (RuntimeException e) {
                log.debug("Could not count stale pending approvals in schema '{}': {}", schema, e.toString());
            }
        });
        return total.get();
    }

    private boolean ttlIsOn() {
        Duration ttl = properties.getTtl();
        SettingsResolver resolver = settings;
        if (resolver != null) {
            try {
                ttl = resolver.getDuration(ApprovalSettingDefinitions.EXPIRY_TTL, SettingContext.tenant());
            } catch (RuntimeException e) {
                // fall back to the platform value
            }
        }
        return ttl != null && !ttl.isZero() && !ttl.isNegative();
    }
}
