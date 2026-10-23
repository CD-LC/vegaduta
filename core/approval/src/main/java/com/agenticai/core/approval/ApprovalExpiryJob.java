package com.agenticai.core.approval;

import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes approval requests that nobody decided within {@code app.approvals.expiry.ttl}; see {@link
 * ApprovalExpiryProperties}. Does nothing at all while the TTL is off (the default).
 *
 * <p>The TTL is the platform value unless the workspace set its own in the settings catalog ({@code approvals.expiry.ttl},
 * read per tenant through {@link SettingsResolver}): a workspace may turn expiry on, change it, or turn it off (0). With the
 * platform TTL off and a resolver present the sweep still visits every tenant, because any of them may have opted in; that
 * costs one cached settings read per tenant per tick and no query for a tenant whose TTL is off.
 *
 * <p>Same shape as {@link RetryFailedActionsJob}: every tenant schema is visited through {@link
 * TenantSchemaIterator} (a {@code @Scheduled} method has no tenant context, and a plain repository call
 * would only ever see the empty {@code public} schema), one replica at a time under {@link
 * PgAdvisoryJobLock}, and one bad row cannot stop the batch. Each request is expired through {@link
 * HumanApprovalGateService#expire}, which re-checks under a row lock that it is still PENDING, so a
 * human deciding in the same instant wins and the expiry is skipped.
 */
@Component
public class ApprovalExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExpiryJob.class);

    private static final String JOB_LOCK_NAME = "approval-expiry";

    private final ApprovalExpiryProperties properties;
    private final TenantSchemaIterator schemaIterator;
    private final ApprovalRequestRepository requestRepository;
    private final HumanApprovalGateService approvalGateService;
    private final PgAdvisoryJobLock jobLock;

    public ApprovalExpiryJob(
            ApprovalExpiryProperties properties,
            TenantSchemaIterator schemaIterator,
            ApprovalRequestRepository requestRepository,
            HumanApprovalGateService approvalGateService,
            PgAdvisoryJobLock jobLock) {
        this.properties = properties;
        this.schemaIterator = schemaIterator;
        this.requestRepository = requestRepository;
        this.approvalGateService = approvalGateService;
        this.jobLock = jobLock;
    }

    // The interval is read from the same key the properties class binds (a @Scheduled attribute cannot
    // take a bean value); both default to the same 5 minutes / 1 minute.
    @Scheduled(
            fixedDelayString = "${app.approvals.expiry.sweep-interval:PT5M}",
            initialDelayString = "${app.approvals.expiry.initial-delay:PT1M}")
    public void expireOverdueRequests() {
        if (!mayHaveWork()) {
            return;
        }
        jobLock.tryRunExclusive(JOB_LOCK_NAME, () -> sweep(Instant.now()));
    }

    /** Late-bound like the other optional collaborators: absent (a slim context), every tenant gets the platform TTL. */
    @Autowired(required = false)
    public void setSettingsResolver(SettingsResolver settings) {
        this.settings = settings;
    }

    private volatile SettingsResolver settings;

    /** False only when the platform TTL is off and there is no settings catalog a tenant could have turned it on in. */
    private boolean mayHaveWork() {
        return properties.isEnabled() || settings != null;
    }

    /**
     * The TTL of the tenant whose context the iterator has set on this thread: the workspace's own value when it set one,
     * else the platform's. A settings failure falls back to the platform value, never to "no expiry" by accident.
     */
    private Duration ttlForCurrentTenant() {
        SettingsResolver resolver = settings;
        if (resolver == null) {
            return properties.getTtl();
        }
        try {
            return resolver.getDuration(ApprovalSettingDefinitions.EXPIRY_TTL, SettingContext.tenant());
        } catch (RuntimeException e) {
            log.warn("Could not read {}; using the platform TTL: {}", ApprovalSettingDefinitions.EXPIRY_TTL, e.toString());
            return properties.getTtl();
        }
    }

    /** One pass over every tenant at {@code now}; returns how many requests it expired. Package-visible for tests. */
    int sweep(Instant now) {
        if (!mayHaveWork()) {
            return 0;
        }
        AtomicInteger expired = new AtomicInteger();
        schemaIterator.forEachActiveTenantSchema(schema -> {
            Duration ttl = ttlForCurrentTenant();
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                return;
            }
            Instant cutoff = now.minus(ttl);
            String reason = properties.reason(ttl);
            List<ApprovalRequest> overdue = requestRepository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                    ApprovalStatus.PENDING, cutoff, PageRequest.of(0, properties.getBatchSize()));
            int expiredHere = 0;
            for (ApprovalRequest request : overdue) {
                try {
                    approvalGateService.expire(request.getId(), reason);
                    expired.incrementAndGet();
                    expiredHere++;
                } catch (ApprovalRequestAlreadyDecidedException decidedFirst) {
                    log.debug("Approval request {} was decided before it could expire", request.getId());
                } catch (RuntimeException e) {
                    log.error("Could not expire approval request {} in schema '{}'", request.getId(), schema, e);
                }
            }
            if (expiredHere > 0) {
                log.info("Expired {} approval request(s) older than {} in schema '{}'", expiredHere, ttl, schema);
            }
        });
        return expired.get();
    }
}
