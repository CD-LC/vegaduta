package com.agenticai.core.payments;

import com.agenticai.core.payments.adapter.UpiAgentProtocolAdapter;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * §E1 reconciliation: the {@code AWAITING_PAYMENT} backstop for when the
 * webhook never lands (gateway retries exhausted, deploy window, consumer
 * lag). Runs per tenant schema under {@code PaymentReconciliationJob}'s
 * {@code TenantSchemaIterator}.
 *
 * <p>Poll ladder per transaction: poll 1 lands 1 minute after initiation,
 * poll 2 five minutes after that, then every 30 minutes until {@code
 * expires_at} - matching the spec's "poll at 1m/5m/30m, expire on TTL".
 * Gateway reads are best-effort (the adapter returns UNKNOWN on failure)
 * so a gateway blip reschedules, never fails a payment.
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);

    private static final Duration SECOND_POLL_DELAY = Duration.ofMinutes(5);
    private static final Duration STEADY_STATE_DELAY = Duration.ofMinutes(30);

    private final PaymentTransactionRepository transactionRepository;
    private final PaymentExecutionService executionService;
    private final ObjectProvider<UpiAgentProtocolAdapter> adapterProvider;

    public PaymentReconciliationService(
        PaymentTransactionRepository transactionRepository,
        PaymentExecutionService executionService,
        ObjectProvider<UpiAgentProtocolAdapter> adapterProvider
    ) {
        this.transactionRepository = transactionRepository;
        this.executionService = executionService;
        this.adapterProvider = adapterProvider;
    }

    /** Polls every due {@code AWAITING_PAYMENT} row in the current tenant schema. Runs inside the iterator's per-schema TenantContext. */
    public void pollDue() {
        Instant now = Instant.now();
        List<PaymentTransaction> due = transactionRepository
            .findByStatusAndNextPollAtLessThanEqual(PaymentTransactionStatus.AWAITING_PAYMENT, now);
        for (PaymentTransaction txn : due) {
            try {
                pollOne(txn, now);
            } catch (RuntimeException e) {
                log.warn("Reconciliation poll failed for payment transaction {} - rescheduling", txn.getId(), e);
                reschedule(txn, now);
            }
        }
    }

    // Not @Transactional itself: the settle* methods on
    // PaymentExecutionService carry their own transactions, and a proxy can
    // never intercept this same-class call anyway.
    private void pollOne(PaymentTransaction txn, Instant now) {
        // TTL first: an expired link can never pay, so expire-and-release
        // beats spending a gateway call on it.
        if (txn.getExpiresAt() != null && !now.isBefore(txn.getExpiresAt())) {
            executionService.settleExpired(txn.getId());
            return;
        }
        UpiAgentProtocolAdapter adapter = adapterProvider.getIfAvailable();
        if (adapter == null || txn.getRazorpayEntityId() == null) {
            reschedule(txn, now);
            return;
        }
        UpiAgentProtocolAdapter.AdapterPaymentStatus status =
            adapter.fetchCollectionStatus(txn.getRazorpayEntityId());
        switch (status.status()) {
            case PAID -> executionService.settleCaptured(txn.getId(), status.paymentId(), status.amountPaise());
            case EXPIRED, CANCELLED -> executionService.settleFailed(txn.getId(), "gateway reports link " + status.status());
            case PENDING, UNKNOWN -> reschedule(txn, now);
        }
    }

    /** The 1m / 5m / 30m ladder by poll count. */
    private void reschedule(PaymentTransaction txn, Instant now) {
        Duration delay = switch (Math.min(txn.getPollCount(), 2)) {
            case 0 -> PaymentExecutionService.FIRST_POLL_DELAY;
            case 1 -> SECOND_POLL_DELAY;
            default -> STEADY_STATE_DELAY;
        };
        txn.scheduleNextPoll(now.plus(delay));
        transactionRepository.save(txn);
    }
}
