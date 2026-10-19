package com.agenticai.core.payments.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One append-only balance movement (migration V404). {@code
 * idempotency_key} is caller-supplied and UNIQUE - unlike {@code
 * payment_transaction}'s argument-derived key, wallet movements are invoked
 * by other subsystems (cart-mandate capture, x402 settlement) that retry the
 * same logical operation and must fold onto the first attempt.
 *
 * <p>{@code reference_type}/{@code reference_id} link back to the causing
 * artifact ({@code commerce_mandate}/cart id, {@code x402}/payment id)
 * without a join contract the other table must keep.
 */
@Entity
@Table(name = "wallet_ledger")
public class WalletLedgerEntry {

    public enum EntryKind {
        CREDIT,
        DEBIT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_kind", nullable = false, length = 8)
    private EntryKind entryKind;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;

    @Column(name = "reference_type", length = 32)
    private String referenceType;

    @Column(name = "reference_id", length = 128)
    private String referenceId;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected WalletLedgerEntry() {
    }

    public static WalletLedgerEntry of(
        UUID walletId, EntryKind kind, long amountMinor, long balanceAfter,
        String idempotencyKey, String referenceType, String referenceId,
        String note, String createdBy
    ) {
        WalletLedgerEntry e = new WalletLedgerEntry();
        e.walletId = walletId;
        e.entryKind = kind;
        e.amountMinor = amountMinor;
        e.balanceAfter = balanceAfter;
        e.idempotencyKey = idempotencyKey;
        e.referenceType = referenceType;
        e.referenceId = referenceId;
        e.note = note;
        e.createdBy = createdBy;
        return e;
    }

    public Long getId() {
        return id;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public EntryKind getEntryKind() {
        return entryKind;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public long getBalanceAfter() {
        return balanceAfter;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public String getNote() {
        return note;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
