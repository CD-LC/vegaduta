package com.agenticai.core.payments;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.payments.adapter.UpiAgentProtocolAdapter;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes an approved (or policy-free) {@link PaymentTransaction} against the gateway and settles
 * its terminal states. Deliberately split into short transactions around the gateway call - a
 * hosted-link create is a synchronous third-party HTTP call and must never run inside a DB
 * transaction holding a pooled connection (the same anti-pattern {@code HumanApprovalGateService}'s
 * javadoc documents).
 *
 * <p><b>Concurrency (JAVA-002):</b> every status transition loads the row with {@link
 * PaymentTransactionRepository#findByIdForUpdate} (a row lock held to commit) and re-checks {@code
 * isTerminal()} under that lock. The webhook and the reconciliation poller can race on one
 * transaction; the loser blocks, then reads the winner's terminal status and returns without side
 * effects - no double cap release, no second event/audit, and a CAPTURED row can never be flipped
 * to EXPIRED or FAILED.
 *
 * <p>The mandate is re-verified AT EXECUTION TIME ({@link MandateEnforcer #assertMandateUsable}
 * again, including the Redis revocation tombstone) - a grant that was revoked while the approval
 * sat PENDING must fail the call, which is exactly what the tombstone is for.
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class PaymentExecutionService {

    private static final Logger log = LoggerFactory.getLogger(PaymentExecutionService.class);

    /** First reconciliation poll 1 minute after the link goes live (ladder: 1m, 5m, then 30m). */
    static final java.time.Duration FIRST_POLL_DELAY = java.time.Duration.ofMinutes(1);

    private final PaymentTransactionRepository transactionRepository;
    private final PaymentMandateRepository mandateRepository;
    private final MandateEnforcer enforcer;
    private final ObjectProvider<UpiAgentProtocolAdapter> adapterProvider;
    private final WorkflowEventPublisher eventPublisher;
    private final AuditLogService auditLogService;
    private final TransactionTemplate transactionTemplate;

    public PaymentExecutionService(
            PaymentTransactionRepository transactionRepository,
            PaymentMandateRepository mandateRepository,
            MandateEnforcer enforcer,
            ObjectProvider<UpiAgentProtocolAdapter> adapterProvider,
            WorkflowEventPublisher eventPublisher,
            AuditLogService auditLogService,
            PlatformTransactionManager transactionManager) {
        this.transactionRepository = transactionRepository;
        this.mandateRepository = mandateRepository;
        this.enforcer = enforcer;
        this.adapterProvider = adapterProvider;
        this.eventPublisher = eventPublisher;
        this.auditLogService = auditLogService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Executes the transaction's collection: re-verifies the mandate (revocation tombstone +
     * signature + status + window), creates the gateway link, flips to AWAITING_PAYMENT with the
     * first poll scheduled. Any failure settles the row FAILED with a ledger reversal - a
     * reserved-but-failed transaction must not hold cap forever.
     */
    public void execute(UUID transactionId) {
        PaymentTransaction txn;
        PaymentMandate mandate;
        try {
            txn = loadExecutable(transactionId);
            mandate = mandateRepository.findById(txn.getMandateId()).orElse(null);
            if (mandate == null) {
                inTransaction(
                        () -> settleFailed(transactionId, "mandate row missing at execution time"));
                return;
            }
            // Re-verify NOW - the mandate may have been revoked/suspended or
            // expired while this sat PENDING_APPROVAL.
            enforcer.assertMandateUsable(mandate, Instant.now());
        } catch (MandateViolationException e) {
            inTransaction(
                    () ->
                            settleFailed(
                                    transactionId,
                                    "mandate check failed at execution: " + e.getCode()));
            return;
        } catch (RuntimeException e) {
            inTransaction(
                    () ->
                            settleFailed(
                                    transactionId,
                                    "execution precondition failed: " + e.getMessage()));
            return;
        }

        UpiAgentProtocolAdapter adapter = adapterProvider.getIfAvailable();
        if (adapter == null) {
            inTransaction(
                    () ->
                            settleFailed(
                                    transactionId,
                                    "no UPI payment adapter is configured (payments unavailable)"));
            return;
        }

        UpiAgentProtocolAdapter.AdapterInitiation initiation;
        try {
            // Outside any transaction - the gateway call is third-party HTTP.
            String tenantId =
                    TenantContext.current().map(TenantContext::getTenantId).orElse("unknown");
            initiation =
                    adapter.initiateCollection(
                            new UpiAgentProtocolAdapter.CollectionRequest(
                                    tenantId,
                                    txn.getAmountPaise(),
                                    txn.getDescription(),
                                    null,
                                    null,
                                    txn.getVpa(),
                                    Map.of(
                                            "agentic_ai_transaction_id", txn.getId().toString(),
                                            "agentic_ai_mandate_id", mandate.getId().toString(),
                                            "agentic_ai_agent_id", txn.getAgentId().toString()),
                                    txn.getExpiresAt()));
        } catch (RuntimeException e) {
            inTransaction(
                    () ->
                            settleFailed(
                                    transactionId,
                                    "gateway refused the collection: " + e.getMessage()));
            return;
        }
        inTransaction(() -> recordInitiated(transactionId, initiation));
    }

    /**
     * Runs a settle step of {@link #execute} in a transaction. Those are {@code this.method()}
     * calls, which bypass the proxy and so their own {@code @Transactional}; {@code releaseCaps} is
     * {@code Propagation.MANDATORY} and the {@code ...ForUpdate} lock needs a transaction to hold.
     */
    private void inTransaction(Runnable step) {
        transactionTemplate.executeWithoutResult(status -> step.run());
    }

    /**
     * Loads the row only if it is in a state where execution is legitimate. Throws {@link
     * MandateViolationException} so the caller's catch settles it FAILED rather than
     * double-executing a CAPTURED row.
     */
    @Transactional(readOnly = true)
    public PaymentTransaction loadExecutable(UUID transactionId) {
        PaymentTransaction txn = transactionRepository.findById(transactionId).orElse(null);
        if (txn == null) {
            throw new MandateViolationException(
                    "TXN_NOT_FOUND", "No payment transaction " + transactionId);
        }
        if (txn.getStatus() != PaymentTransactionStatus.INITIATED
                && txn.getStatus() != PaymentTransactionStatus.PENDING_APPROVAL) {
            throw new MandateViolationException(
                    "TXN_NOT_EXECUTABLE",
                    "Transaction is "
                            + txn.getStatus()
                            + " - already settled, not executing again");
        }
        return txn;
    }

    @Transactional
    public void recordInitiated(
            UUID transactionId, UpiAgentProtocolAdapter.AdapterInitiation initiation) {
        PaymentTransaction txn =
                transactionRepository.findByIdForUpdate(transactionId).orElse(null);
        if (txn == null
                || txn.getStatus() == PaymentTransactionStatus.AWAITING_PAYMENT
                || txn.getStatus().isTerminal()) {
            // Replay-safe, and never resurrect a row another path already
            // settled (e.g. FAILED by a concurrent reject) into AWAITING.
            return;
        }
        txn.markAwaitingPayment(
                initiation.gatewayEntityId(),
                initiation.shortUrl(),
                Instant.now().plus(FIRST_POLL_DELAY));
        transactionRepository.save(txn);
        publishStatusChanged(txn, "link live at gateway");
        audit(
                "payment.transaction.initiated",
                txn,
                AuditOutcome.SUCCESS,
                "gatewayEntityId=" + initiation.gatewayEntityId());
    }

    /** Terminal FAILED + cap release + event. Idempotent - a settled row is never re-failed. */
    @Transactional
    public void settleFailed(UUID transactionId, String reason) {
        PaymentTransaction txn =
                transactionRepository.findByIdForUpdate(transactionId).orElse(null);
        if (txn == null || txn.getStatus().isTerminal()) {
            return;
        }
        txn.markFailed(reason);
        transactionRepository.save(txn);
        enforcer.releaseCaps(txn.getMandateId(), txn.getId(), Instant.now());
        publishStatusChanged(txn, reason);
        audit("payment.transaction.failed", txn, AuditOutcome.FAILURE, reason);
    }

    /** Terminal CAPTURED - from the webhook or the reconciliation poller. Idempotent on status. */
    @Transactional
    public boolean settleCaptured(UUID transactionId, String gatewayPaymentId, Long amountPaise) {
        PaymentTransaction txn =
                transactionRepository.findByIdForUpdate(transactionId).orElse(null);
        if (txn == null || txn.getStatus().isTerminal()) {
            return false;
        }
        if (amountPaise != null && amountPaise.longValue() != txn.getAmountPaise()) {
            // Amount mismatch is a reconciliation red flag - mark FAILED for
            // review rather than silently capturing a different amount.
            txn.markFailed(
                    "gateway captured "
                            + amountPaise
                            + " paise but the transaction expected "
                            + txn.getAmountPaise());
            transactionRepository.save(txn);
            enforcer.releaseCaps(txn.getMandateId(), txn.getId(), Instant.now());
            publishStatusChanged(txn, "amount mismatch at capture");
            audit(
                    "payment.transaction.amount_mismatch",
                    txn,
                    AuditOutcome.FAILURE,
                    "expected=" + txn.getAmountPaise() + " gateway=" + amountPaise);
            return true;
        }
        txn.markCaptured(gatewayPaymentId, txn.getShortUrl(), Instant.now());
        transactionRepository.save(txn);
        publishStatusChanged(txn, "paid");
        audit(
                "payment.transaction.captured",
                txn,
                AuditOutcome.SUCCESS,
                "paymentId=" + gatewayPaymentId);
        return true;
    }

    /** Terminal EXPIRED - the link's TTL elapsed without payment; releases the cap reservation. */
    @Transactional
    public void settleExpired(UUID transactionId) {
        PaymentTransaction txn =
                transactionRepository.findByIdForUpdate(transactionId).orElse(null);
        if (txn == null || txn.getStatus().isTerminal()) {
            return;
        }
        txn.markExpired();
        transactionRepository.save(txn);
        enforcer.releaseCaps(txn.getMandateId(), txn.getId(), Instant.now());
        publishStatusChanged(txn, "payment link expired without payment");
        audit("payment.transaction.expired", txn, AuditOutcome.SUCCESS, "TTL elapsed");
    }

    /** A rejected approval: the transaction never reaches the gateway; releases caps. */
    @Transactional
    public void settleRejected(UUID transactionId, String decidedBy, String reason) {
        PaymentTransaction txn =
                transactionRepository.findByIdForUpdate(transactionId).orElse(null);
        if (txn == null || txn.getStatus().isTerminal()) {
            return;
        }
        txn.markFailed("approval rejected" + (reason == null ? "" : ": " + reason));
        transactionRepository.save(txn);
        enforcer.releaseCaps(txn.getMandateId(), txn.getId(), Instant.now());
        publishStatusChanged(txn, "rejected by " + decidedBy);
        audit("payment.transaction.rejected", txn, AuditOutcome.FAILURE, "by=" + decidedBy);
    }

    /** {@code payment.status.changed} through the outbox publisher - flag-gated inside it. */
    void publishStatusChanged(PaymentTransaction txn, String detail) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionId", txn.getId().toString());
        payload.put("mandateId", txn.getMandateId().toString());
        payload.put("agentId", txn.getAgentId().toString());
        payload.put("type", txn.getType().name());
        payload.put("status", txn.getStatus().name());
        payload.put("amountPaise", txn.getAmountPaise());
        if (txn.getRazorpayEntityId() != null) {
            payload.put("gatewayEntityId", txn.getRazorpayEntityId());
        }
        if (detail != null) {
            payload.put("detail", detail);
        }
        // runId = the transaction id - the run every payment.* event for one
        // transaction shares, so consumers can group a payment's lifecycle.
        eventPublisher.publish(PaymentEvents.STATUS_CHANGED, txn.getId().toString(), payload);
    }

    private void audit(
            String actionType, PaymentTransaction txn, AuditOutcome outcome, String detail) {
        try {
            String actor = TenantContext.current().map(TenantContext::getSubject).orElse("system");
            auditLogService.record(
                    actor,
                    actionType,
                    "payment_transaction",
                    txn.getId().toString(),
                    outcome,
                    detail);
        } catch (RuntimeException e) {
            log.warn("Audit write failed for {} on transaction {}", actionType, txn.getId(), e);
        }
    }
}
