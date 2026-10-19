package com.agenticai.core.payments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Append-only running-total row (migration V346). The LATEST row for a
 * mandate carries current daily/monthly spend; {@code MandateLedgerRepository
 * #findLatestForUpdate} takes it {@code FOR UPDATE} so cap checks + the
 * insert of the next row are serialized per mandate - totals are never
 * recomputed by summing history. CHARGE reserves at initiation; REVERSAL
 * releases on rejection/failure/expiry (UNIQUE(transaction_id, entry_kind)
 * makes a reversal idempotent).
 */
@Entity
@Table(name = "mandate_ledger")
public class MandateLedgerEntry {

    public static final String KIND_CHARGE = "CHARGE";
    public static final String KIND_REVERSAL = "REVERSAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mandate_id", nullable = false)
    private UUID mandateId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "entry_kind", nullable = false, length = 12)
    private String entryKind;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "day_key", nullable = false)
    private LocalDate dayKey;

    @Column(name = "daily_spend_paise", nullable = false)
    private long dailySpendPaise;

    @Column(name = "month_key", nullable = false, length = 7)
    private String monthKey;

    @Column(name = "monthly_spend_paise", nullable = false)
    private long monthlySpendPaise;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected MandateLedgerEntry() {
    }

    public MandateLedgerEntry(
        UUID mandateId, UUID transactionId, String entryKind, long amountPaise,
        LocalDate dayKey, long dailySpendPaise, String monthKey, long monthlySpendPaise
    ) {
        this.mandateId = mandateId;
        this.transactionId = transactionId;
        this.entryKind = entryKind;
        this.amountPaise = amountPaise;
        this.dayKey = dayKey;
        this.dailySpendPaise = dailySpendPaise;
        this.monthKey = monthKey;
        this.monthlySpendPaise = monthlySpendPaise;
    }

    public Long getId() {
        return id;
    }

    public UUID getMandateId() {
        return mandateId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getEntryKind() {
        return entryKind;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public LocalDate getDayKey() {
        return dayKey;
    }

    public long getDailySpendPaise() {
        return dailySpendPaise;
    }

    public String getMonthKey() {
        return monthKey;
    }

    public long getMonthlySpendPaise() {
        return monthlySpendPaise;
    }
}
