package com.agenticai.core.agents.trust;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per minted delegation JWT, keyed by its {@code jti} claim
 * (migration V401). Tokens are self-contained and verifiable statelessly,
 * but this table is what makes introspection honest: a revoked grant or an
 * individually revoked jti fails closed here even while the JWT's own {@code
 * exp} would still pass.
 *
 * <p>{@link #walletId} pins WHICH wallet the token may spend from when its
 * scope includes {@code pay:*} - a payment delegation cannot be re-aimed at
 * a different wallet of the same agent after minting.
 */
@Entity
@Table(name = "agent_delegation_token")
public class DelegationTokenRecord {

    /** The JWT's {@code jti} claim - the token's own identity. */
    @Id
    @Column(name = "jti", length = 64)
    private String jti;

    @Column(name = "grant_id", nullable = false)
    private UUID grantId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "audience", nullable = false, length = 512)
    private String audience;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DelegationStatus status = DelegationStatus.ACTIVE;

    @Column(name = "wallet_id")
    private UUID walletId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected DelegationTokenRecord() {
    }

    public String getJti() {
        return jti;
    }

    public void setJti(String jti) {
        this.jti = jti;
    }

    public UUID getGrantId() {
        return grantId;
    }

    public void setGrantId(UUID grantId) {
        this.grantId = grantId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public DelegationStatus getStatus() {
        return status;
    }

    public void setStatus(DelegationStatus status) {
        this.status = status;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public void setWalletId(UUID walletId) {
        this.walletId = walletId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
