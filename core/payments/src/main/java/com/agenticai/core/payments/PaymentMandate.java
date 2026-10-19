package com.agenticai.core.payments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A human-granted, cryptographically signed authorization letting one agent move money (migration
 * V345). Immutable after signing: {@code canonicalJson} is the exact byte string the Ed25519 {@code
 * signature} covers, so {@link MandateEnforcer} verifies the stored artifact as-is instead of
 * trusting any field to have stayed consistent. A "change" is revoke + re-grant - there is
 * deliberately no update path that could leave caps drifted away from what the grantor actually
 * signed.
 */
@com.agenticai.core.security.TenantConfigEntity
@Entity
@Table(name = "payment_mandate")
public class PaymentMandate {

    @Id private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "granted_by_user_id", nullable = false, length = 128)
    private String grantedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 8)
    private MandateScope scope;

    @Column(name = "max_per_txn_paise", nullable = false)
    private long maxPerTxnPaise;

    @Column(name = "max_daily_paise", nullable = false)
    private long maxDailyPaise;

    @Column(name = "max_monthly_paise", nullable = false)
    private long maxMonthlyPaise;

    /** JSON array of VPA strings; NULL/empty array = unrestricted. */
    @Column(name = "allowed_vpas", columnDefinition = "TEXT")
    private String allowedVpas;

    /** JSON array of merchant-category strings; NULL/empty = unrestricted. */
    @Column(name = "allowed_merchant_categories", columnDefinition = "TEXT")
    private String allowedMerchantCategories;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_policy", nullable = false, length = 20)
    private MandateApprovalPolicy approvalPolicy = MandateApprovalPolicy.ALWAYS;

    @Column(name = "approval_threshold_paise")
    private Long approvalThresholdPaise;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MandateStatus status = MandateStatus.ACTIVE;

    @Column(name = "canonical_json", nullable = false, columnDefinition = "TEXT")
    private String canonicalJson;

    @Column(name = "signature", nullable = false)
    private byte[] signature;

    @Column(name = "key_id", nullable = false, length = 64)
    private String keyId;

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

    protected PaymentMandate() {}

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

    public String getGrantedByUserId() {
        return grantedByUserId;
    }

    public void setGrantedByUserId(String grantedByUserId) {
        this.grantedByUserId = grantedByUserId;
    }

    public MandateScope getScope() {
        return scope;
    }

    public void setScope(MandateScope scope) {
        this.scope = scope;
    }

    public long getMaxPerTxnPaise() {
        return maxPerTxnPaise;
    }

    public void setMaxPerTxnPaise(long maxPerTxnPaise) {
        this.maxPerTxnPaise = maxPerTxnPaise;
    }

    public long getMaxDailyPaise() {
        return maxDailyPaise;
    }

    public void setMaxDailyPaise(long maxDailyPaise) {
        this.maxDailyPaise = maxDailyPaise;
    }

    public long getMaxMonthlyPaise() {
        return maxMonthlyPaise;
    }

    public void setMaxMonthlyPaise(long maxMonthlyPaise) {
        this.maxMonthlyPaise = maxMonthlyPaise;
    }

    public String getAllowedVpas() {
        return allowedVpas;
    }

    public void setAllowedVpas(String allowedVpas) {
        this.allowedVpas = allowedVpas;
    }

    public String getAllowedMerchantCategories() {
        return allowedMerchantCategories;
    }

    public void setAllowedMerchantCategories(String allowedMerchantCategories) {
        this.allowedMerchantCategories = allowedMerchantCategories;
    }

    public MandateApprovalPolicy getApprovalPolicy() {
        return approvalPolicy;
    }

    public void setApprovalPolicy(MandateApprovalPolicy approvalPolicy) {
        this.approvalPolicy = approvalPolicy;
    }

    public Long getApprovalThresholdPaise() {
        return approvalThresholdPaise;
    }

    public void setApprovalThresholdPaise(Long approvalThresholdPaise) {
        this.approvalThresholdPaise = approvalThresholdPaise;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(Instant validFrom) {
        this.validFrom = validFrom;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(Instant validUntil) {
        this.validUntil = validUntil;
    }

    public MandateStatus getStatus() {
        return status;
    }

    public void setStatus(MandateStatus status) {
        this.status = status;
    }

    public String getCanonicalJson() {
        return canonicalJson;
    }

    public void setCanonicalJson(String canonicalJson) {
        this.canonicalJson = canonicalJson;
    }

    public byte[] getSignature() {
        return signature;
    }

    public void setSignature(byte[] signature) {
        this.signature = signature;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
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

    public void markRevoked(String decidedBy, String reason, Instant at) {
        this.status = MandateStatus.REVOKED;
        this.revokedAt = at;
        this.revokedBy = decidedBy;
        this.revokeReason = reason;
        this.updatedAt = at;
    }

    public void markExpired(Instant at) {
        this.status = MandateStatus.EXPIRED;
        this.updatedAt = at;
    }
}
