package com.agenticai.core.agents.tools.x402;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One presented payment against an {@link X402Offer} (migration V405, {@code control_plane}).
 * {@link #nonce} is UNIQUE - the replay guard: an EIP-3009 nonce or a {@code
 * delegation-jti:call-nonce} pair can settle only once.
 *
 * <p>{@link Status#SETTLED} = both wallet legs are written (internal scheme); {@link
 * Status#VERIFIED} = the cryptographic authorization checked out but on-chain settlement is the
 * facilitator path (we verify, we do not broadcast); {@link Status#REJECTED} = refused, kept for
 * audit.
 */
@Entity
@Table(name = "x402_payment", schema = "control_plane")
public class X402PaymentRecord {

    public enum Status {
        SETTLED,
        VERIFIED,
        REJECTED
    }

    @Id private UUID id;

    @Column(name = "offer_id", nullable = false)
    private UUID offerId;

    /** Buyer schema for the internal scheme; null for external EVM payers. */
    @Column(name = "payer_tenant_id")
    private String payerTenantId;

    /** Buyer wallet id or 0x address. */
    @Column(name = "payer_ref", nullable = false, length = 256)
    private String payerRef;

    @Column(name = "amount_atomic", nullable = false)
    private long amountAtomic;

    @Column(name = "scheme", nullable = false, length = 32)
    private String scheme;

    @Column(name = "network", nullable = false, length = 32)
    private String network;

    @Column(name = "nonce", nullable = false, length = 160)
    private String nonce;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    /** wallet_ledger.id of the buyer debit (internal scheme). */
    @Column(name = "buyer_ledger_ref")
    private Long buyerLedgerRef;

    /** wallet_ledger.id of the seller credit (internal scheme). */
    @Column(name = "seller_ledger_ref")
    private Long sellerLedgerRef;

    @Column(name = "reject_reason", length = 128)
    private String rejectReason;

    /** Facilitator-returned tx hash / settlement id for the EVM scheme (V407). */
    @Column(name = "settlement_ref", length = 160)
    private String settlementRef;

    /** The presented X-PAYMENT body - audit evidence. */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "settled_at")
    private Instant settledAt;

    protected X402PaymentRecord() {}

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getOfferId() {
        return offerId;
    }

    public void setOfferId(UUID offerId) {
        this.offerId = offerId;
    }

    public String getPayerTenantId() {
        return payerTenantId;
    }

    public void setPayerTenantId(String payerTenantId) {
        this.payerTenantId = payerTenantId;
    }

    public String getPayerRef() {
        return payerRef;
    }

    public void setPayerRef(String payerRef) {
        this.payerRef = payerRef;
    }

    public long getAmountAtomic() {
        return amountAtomic;
    }

    public void setAmountAtomic(long amountAtomic) {
        this.amountAtomic = amountAtomic;
    }

    public String getScheme() {
        return scheme;
    }

    public void setScheme(String scheme) {
        this.scheme = scheme;
    }

    public String getNetwork() {
        return network;
    }

    public void setNetwork(String network) {
        this.network = network;
    }

    public String getNonce() {
        return nonce;
    }

    public void setNonce(String nonce) {
        this.nonce = nonce;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Long getBuyerLedgerRef() {
        return buyerLedgerRef;
    }

    public void setBuyerLedgerRef(Long buyerLedgerRef) {
        this.buyerLedgerRef = buyerLedgerRef;
    }

    public Long getSellerLedgerRef() {
        return sellerLedgerRef;
    }

    public void setSellerLedgerRef(Long sellerLedgerRef) {
        this.sellerLedgerRef = sellerLedgerRef;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public String getSettlementRef() {
        return settlementRef;
    }

    public void setSettlementRef(String settlementRef) {
        this.settlementRef = settlementRef;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(Instant settledAt) {
        this.settledAt = settledAt;
    }
}
