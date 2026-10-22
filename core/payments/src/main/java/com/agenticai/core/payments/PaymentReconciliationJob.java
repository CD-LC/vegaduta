package com.agenticai.core.payments;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduler for {@link PaymentReconciliationService} - a tenant-scoped {@code @Scheduled} job,
 * so it MUST iterate via {@link TenantSchemaIterator} (a scheduled thread has no {@code
 * TenantContext}; querying the repository directly would silently only ever see the empty public
 * schema - the exact bug class that class exists to prevent). Interval is fixed; the
 * per-transaction cadence (1m/5m/30m) is the poller's own {@code next_poll_at} ladder.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class PaymentReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);

    private final TenantSchemaIterator schemaIterator;
    private final PaymentReconciliationService reconciliationService;

    /**
     * Cross-replica guard (JAVA-002). Null = run unlocked (single-instance fallback); without it
     * two replicas both poll the payment provider for the same AWAITING_PAYMENT rows every tick.
     */
    private final PgAdvisoryJobLock jobLock;

    public PaymentReconciliationJob(
            TenantSchemaIterator schemaIterator,
            PaymentReconciliationService reconciliationService,
            PgAdvisoryJobLock jobLock) {
        this.schemaIterator = schemaIterator;
        this.reconciliationService = reconciliationService;
        this.jobLock = jobLock;
    }

    /**
     * Sweeps every ACTIVE tenant schema for due AWAITING_PAYMENT rows. One replica per tick
     * (advisory job lock); the per-row settle transitions are additionally row-locked, so the
     * webhook racing this sweep is safe.
     */
    @Scheduled(fixedDelayString = "${app.payments.reconcile-interval-ms:60000}")
    public void sweep() {
        PgAdvisoryJobLock lock = jobLock;
        if (lock == null) {
            doSweep();
            return;
        }
        lock.tryRunExclusive("payment-reconciliation", this::doSweep);
    }

    void doSweep() {
        schemaIterator.forEachActiveTenantSchema(schema -> reconciliationService.pollDue());
    }
}
