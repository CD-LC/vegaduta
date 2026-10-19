package com.agenticai.core.payments.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An agent's spendable balance in one currency (migration V404). One wallet
 * per (agent, currency) - the {@code uq_agent_wallet_agent_currency}
 * constraint enforces it, and {@link AgentWalletService#getOrCreate} collapses
 * concurrent first-use creation onto it.
 *
 * <p>{@link #balanceMinor} is mutated ONLY inside {@code
 * AgentWalletService}'s {@code SELECT ... FOR UPDATE} critical section, and
 * every mutation appends a {@link WalletLedgerEntry} with the resulting
 * {@code balance_after} - the ledger alone can reconstruct any wallet's
 * history, so a balance that drifted from its ledger is detectable.
 */
@Entity
@Table(name = "agent_wallet")
public class AgentWallet {

    public enum Status {
        ACTIVE,
        FROZEN,
        CLOSED
    }

    @Id
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AgentWallet() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public long getBalanceMinor() {
        return balanceMinor;
    }

    /** Only {@link AgentWalletService} calls this, inside the row lock. */
    void applyMovement(long signedAmount, Instant at) {
        this.balanceMinor += signedAmount;
        this.updatedAt = at;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
