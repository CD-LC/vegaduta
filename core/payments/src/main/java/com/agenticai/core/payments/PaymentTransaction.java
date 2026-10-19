package com.agenticai.core.payments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * One agent-initiated money movement under a {@link PaymentMandate}
 * (migration V346). State machine in {@link PaymentTransactionStatus};
 * {@code idempotencyKey} is UNIQUE so a replayed tool call folds into the
 * same row instead of double-creating a gateway link.
 */
@Entity
@Table(name = "payment_transaction")
public class PaymentTransaction {

    @Id
    private UUID id;

    /**
     * Optimistic lock (JAVA-007, migration tenant/V365.2): every UPDATE is
     * {@code WHERE id = ? AND version = ?}, so a concurrent writer's save fails
     * with {@code ObjectOptimisticLockingFailureException} (HTTP 409) instead
     * of silently overwriting the other writer's columns. A wrapper type on
     * purpose: {@code null} marks a never-persisted entity for Spring Data.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "mandate_id", nullable = false)
    private UUID mandateId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private PaymentTransactionType type;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "vpa", length = 128)
    private String vpa;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "merchant_category", length = 64)
    private String merchantCategory;

    @Column(name = "gateway", nullable = false, length = 16)
    private String gateway = "RAZORPAY";

    @Column(name = "razorpay_entity_id", length = 64)
    private String razorpayEntityId;

    @Column(name = "gateway_payment_id", length = 64)
    private String gatewayPaymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PaymentTransactionStatus status = PaymentTransactionStatus.INITIATED;

    @Column(name = "approval_id")
    private UUID approvalId;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 160)
    private String idempotencyKey;

    @Column(name = "short_url", length = 512)
    private String shortUrl;

    @Column(name = "receipt_url", length = 512)
    private String receiptUrl;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "next_poll_at")
    private Instant nextPollAt;

    @Column(name = "poll_count", nullable = false)
    private int pollCount;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PaymentTransaction() {
    }

    public PaymentTransaction(
        UUID mandateId, UUID agentId, String sessionId, PaymentTransactionType type,
        long amountPaise, String vpa, String description, String merchantCategory,
        String idempotencyKey, Instant expiresAt
    ) {
        this.id = UUID.randomUUID();
        this.mandateId = mandateId;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.type = type;
        this.amountPaise = amountPaise;
        this.vpa = vpa;
        this.description = description;
        this.merchantCategory = merchantCategory;
        this.idempotencyKey = idempotencyKey;
        this.expiresAt = expiresAt;
    }

    public void markPendingApproval(UUID approvalRequestId) {
        this.status = PaymentTransactionStatus.PENDING_APPROVAL;
        this.approvalId = approvalRequestId;
        touch();
    }

    /** Gateway accepted: a payable link/QR exists. Reconciliation starts at {@code nextPollAt}. */
    public void markAwaitingPayment(String gatewayEntityId, String shortUrl, Instant firstPollAt) {
        this.status = PaymentTransactionStatus.AWAITING_PAYMENT;
        this.razorpayEntityId = gatewayEntityId;
        this.shortUrl = shortUrl;
        this.nextPollAt = firstPollAt;
        touch();
    }

    public void markCaptured(String gatewayPaymentId, String receiptUrl, Instant paidAt) {
        this.status = PaymentTransactionStatus.CAPTURED;
        this.gatewayPaymentId = gatewayPaymentId;
        this.receiptUrl = receiptUrl;
        this.paidAt = paidAt;
        this.nextPollAt = null;
        touch();
    }

    public void markFailed(String reason) {
        this.status = PaymentTransactionStatus.FAILED;
        this.failureReason = reason;
        this.nextPollAt = null;
        touch();
    }

    public void markExpired() {
        this.status = PaymentTransactionStatus.EXPIRED;
        this.nextPollAt = null;
        touch();
    }

    public void scheduleNextPoll(Instant at) {
        this.nextPollAt = at;
        this.pollCount++;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMandateId() {
        return mandateId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public PaymentTransactionType getType() {
        return type;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getCurrency() {
        return currency;
    }

    public String getVpa() {
        return vpa;
    }

    public String getDescription() {
        return description;
    }

    public String getMerchantCategory() {
        return merchantCategory;
    }

    public String getGateway() {
        return gateway;
    }

    public String getRazorpayEntityId() {
        return razorpayEntityId;
    }

    public String getGatewayPaymentId() {
        return gatewayPaymentId;
    }

    public PaymentTransactionStatus getStatus() {
        return status;
    }

    public UUID getApprovalId() {
        return approvalId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getShortUrl() {
        return shortUrl;
    }

    public String getReceiptUrl() {
        return receiptUrl;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getNextPollAt() {
        return nextPollAt;
    }

    public int getPollCount() {
        return pollCount;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Optimistic-lock version; {@code null} until first persisted. */
    public Long getVersion() {
        return version;
    }
}
