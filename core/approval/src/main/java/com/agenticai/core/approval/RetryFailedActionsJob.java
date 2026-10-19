package com.agenticai.core.approval;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Autonomy/Guardrails Strategy Stage C - the background half of
 * retry-with-backoff. {@link HumanApprovalGateService#retryExecution}
 * already knows how to retry a single {@link ApprovalRequest}; this class
 * is what actually finds due requests and calls it, on a schedule, across
 * every tenant schema.
 *
 * <p>Runs every minute ({@link #FIXED_DELAY_MS}) - deliberately more
 * frequent than {@code AbandonedSignupCleanupJob}'s hourly cadence, since a
 * transient failure's whole point is that it should recover quickly (the
 * backoff delay itself, not this job's polling interval, is what actually
 * paces how soon a specific request is retried; {@code baseDelaySeconds}
 * defaults to 30s, so a 60s poll interval does not meaningfully add
 * latency on top of that).
 *
 * <p>Uses {@link TenantSchemaIterator} rather than querying {@link
 * ApprovalRequestRepository} directly - see that class's javadoc for why a
 * naive version of this job would silently only ever process the {@code
 * public} schema and never actually retry anything for a real tenant.
 *
 * <p>Multi-replica: each tick runs under {@link PgAdvisoryJobLock} so only
 * one replica scans per minute. Two replicas racing the same due request
 * would both call {@code retryExecution} on it - the executor re-fires and
 * the retry bookkeeping double-counts - so the lock is load-bearing here,
 * not just insurance.
 */
@Component
public class RetryFailedActionsJob {

    private static final long FIXED_DELAY_MS = 60_000L;
    private static final long INITIAL_DELAY_MS = 30_000L;

    private static final Logger log = LoggerFactory.getLogger(RetryFailedActionsJob.class);

    private static final String JOB_LOCK_NAME = "retry-failed-actions";

    private final TenantSchemaIterator schemaIterator;
    private final ApprovalRequestRepository requestRepository;
    private final HumanApprovalGateService approvalGateService;
    private final PgAdvisoryJobLock jobLock;

    public RetryFailedActionsJob(
        TenantSchemaIterator schemaIterator,
        ApprovalRequestRepository requestRepository,
        HumanApprovalGateService approvalGateService,
        PgAdvisoryJobLock jobLock
    ) {
        this.schemaIterator = schemaIterator;
        this.requestRepository = requestRepository;
        this.approvalGateService = approvalGateService;
        this.jobLock = jobLock;
    }

    @Scheduled(fixedDelay = FIXED_DELAY_MS, initialDelay = INITIAL_DELAY_MS)
    public void retryDueActions() {
        // Lock-not-acquired is only DEBUG-logged (by the lock service itself) -
        // another replica retrying is success, not an incident.
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::retryDueActionsExclusively);
    }

    private void retryDueActionsExclusively() {
        schemaIterator.forEachActiveTenantSchema(schema -> {
            List<ApprovalRequest> due = requestRepository.findByStatusAndNextRetryAtBefore(
                ApprovalStatus.EXECUTION_FAILED, Instant.now()
            );
            for (ApprovalRequest request : due) {
                try {
                    approvalGateService.retryExecution(request);
                } catch (RuntimeException e) {
                    // retryExecution() already catches and records executor
                    // failures itself - this catch is a last-resort net for
                    // anything unexpected (e.g. a DB error mid-record) so
                    // one bad row can never stop the rest of this tenant's
                    // batch, matching TenantSchemaIterator's own per-tenant
                    // isolation guarantee at the per-request level too.
                    log.error("Unexpected error retrying approval request {} in schema '{}'",
                        request.getId(), schema, e);
                }
            }
        });
    }
}
