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
 * A recorded authorization by which one user (the delegator) lets an agent
 * act on their behalf toward a named audience - RFC 8693 token-exchange
 * semantics: tokens minted under a grant carry {@code sub}=agent, {@code
 * act}=delegator, {@code aud}={@link #audience}, {@code scope}={@link
 * #scopesJson}. Migration V401.
 *
 * <p>Immutable after creation like {@code PaymentMandate}: scopes, audience,
 * ttl cap and expiry are exactly what the delegator saw. A change is revoke +
 * re-grant, which keeps the audit record honest.
 */
@Entity
@Table(name = "agent_delegation_grant")
public class DelegationGrant {

    @Id
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /** The delegating user's JWT subject - never caller-supplied. */
    @Column(name = "granted_by", nullable = false, length = 128)
    private String grantedBy;

    /** The aud claim every token minted under this grant is bound to. */
    @Column(name = "audience", nullable = false, length = 512)
    private String audience;

    /** JSON array of scope strings, e.g. {@code ["read:calendar","pay:x402"]}. */
    @Column(name = "scopes", nullable = false, columnDefinition = "TEXT")
    private String scopesJson;

    /** Ceiling on any single token's lifetime minted under this grant. */
    @Column(name = "max_token_ttl_seconds", nullable = false)
    private long maxTokenTtlSeconds;

    /** Grant-level hard expiry - no token may outlive this. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * The wallet this grant may spend from - set iff {@link #scopesJson}
     * contains a {@code pay:*} scope. Bound at grant level so a payment
     * delegation cannot be re-aimed at a different wallet after creation.
     */
    @Column(name = "wallet_id")
    private UUID walletId;

    /**
     * Optional cumulative lifetime cap (minor units) across EVERY token minted
     * under this grant (migration V406). NULL = bounded only by the bound
     * wallet's balance. Enforced under the grant row lock at settlement, so
     * concurrent spends cannot overshoot.
     */
    @Column(name = "spend_cap_minor")
    private Long spendCapMinor;

    /** Cumulative spend recorded against {@link #spendCapMinor}. */
    @Column(name = "spent_minor", nullable = false)
    private long spentMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DelegationStatus status = DelegationStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by", length = 128)
    private String revokedBy;

    @Column(name = "revoke_reason", columnDefinition = "TEXT")
    private String revokeReason;

    protected DelegationGrant() {
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

    public String getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(String grantedBy) {
        this.grantedBy = grantedBy;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getScopesJson() {
        return scopesJson;
    }

    public void setScopesJson(String scopesJson) {
        this.scopesJson = scopesJson;
    }

    public long getMaxTokenTtlSeconds() {
        return maxTokenTtlSeconds;
    }

    public void setMaxTokenTtlSeconds(long maxTokenTtlSeconds) {
        this.maxTokenTtlSeconds = maxTokenTtlSeconds;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public void setWalletId(UUID walletId) {
        this.walletId = walletId;
    }

    public DelegationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokedBy() {
        return revokedBy;
    }

    public String getRevokeReason() {
        return revokeReason;
    }

    public Long getSpendCapMinor() {
        return spendCapMinor;
    }

    public void setSpendCapMinor(Long spendCapMinor) {
        this.spendCapMinor = spendCapMinor;
    }

    public long getSpentMinor() {
        return spentMinor;
    }

    /**
     * Record spend against the cap - ONLY called under the grant's
     * SELECT ... FOR UPDATE lock ({@link DelegationTokenService#recordSpend}).
     */
    void applySpend(long amountMinor, Instant at) {
        this.spentMinor = Math.addExact(this.spentMinor, amountMinor);
        this.updatedAt = at;
    }

    /** Compensating release when a settled debit is reversed. */
    void releaseSpend(long amountMinor, Instant at) {
        this.spentMinor = Math.max(0, this.spentMinor - amountMinor);
        this.updatedAt = at;
    }

    public void markRevoked(String decidedBy, String reason, Instant at) {
        this.status = DelegationStatus.REVOKED;
        this.revokedAt = at;
        this.revokedBy = decidedBy;
        this.revokeReason = reason;
        this.updatedAt = at;
    }
}
