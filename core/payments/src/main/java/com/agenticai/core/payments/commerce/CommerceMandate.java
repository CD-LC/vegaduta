package com.agenticai.core.payments.commerce;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One AP2-style mandate - an INTENT ("agent may shop for X up to N") or a
 * CART ("user authorized THIS spend") - migration V403.
 *
 * <p>Same immutability contract as {@code PaymentMandate}: {@code
 * canonicalJson} is the exact byte string the tenant key's Ed25519 {@code
 * signature} covers; a change is revoke + re-create.
 *
 * <p>CART rows carry a second signed document: {@code buyerAuthJson} +
 * {@code buyerSignature} - the cryptographic proof that the buyer authorized
 * this specific spend, bound to the cart's own canonical hash.
 */
@Entity
@Table(name = "commerce_mandate")
public class CommerceMandate {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 8)
    private CommerceMandateKind kind;

    @Column(name = "intent_mandate_id")
    private UUID intentMandateId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /** The authorizing user's JWT subject - never caller-supplied. */
    @Column(name = "buyer_user_id", nullable = false, length = 128)
    private String buyerUserId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** INTENT: the spend ceiling. */
    @Column(name = "max_total_minor")
    private Long maxTotalMinor;

    /** CART: the exact authorized total (sum of items). */
    @Column(name = "cart_total_minor")
    private Long cartTotalMinor;

    /** CART: JSON array [{sku,name,qty,unit_price_minor}]. */
    @Column(name = "items_json", columnDefinition = "TEXT")
    private String itemsJson;

    @Column(name = "payee", length = 256)
    private String payee;

    /** INTENT: JSON array of allowed category strings; NULL = unrestricted. */
    @Column(name = "categories", columnDefinition = "TEXT")
    private String categories;

    /** INTENT: the buyer's own words - evidence, not enforcement. */
    @Column(name = "intent_text", columnDefinition = "TEXT")
    private String intentText;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private CommerceMandateStatus status;

    @Column(name = "canonical_json", nullable = false, columnDefinition = "TEXT")
    private String canonicalJson;

    @Column(name = "signature", nullable = false)
    private byte[] signature;

    @Column(name = "key_id", nullable = false, length = 64)
    private String keyId;

    /** CART: the signed buyer-authorization sub-document. */
    @Column(name = "buyer_auth_json", columnDefinition = "TEXT")
    private String buyerAuthJson;

    @Column(name = "buyer_signature")
    private byte[] buyerSignature;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    /** wallet_ledger.id of the capture debit. */
    @Column(name = "wallet_debit_ref")
    private Long walletDebitRef;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

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

    protected CommerceMandate() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public CommerceMandateKind getKind() {
        return kind;
    }

    public void setKind(CommerceMandateKind kind) {
        this.kind = kind;
    }

    public UUID getIntentMandateId() {
        return intentMandateId;
    }

    public void setIntentMandateId(UUID intentMandateId) {
        this.intentMandateId = intentMandateId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public String getBuyerUserId() {
        return buyerUserId;
    }

    public void setBuyerUserId(String buyerUserId) {
        this.buyerUserId = buyerUserId;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Long getMaxTotalMinor() {
        return maxTotalMinor;
    }

    public void setMaxTotalMinor(Long maxTotalMinor) {
        this.maxTotalMinor = maxTotalMinor;
    }

    public Long getCartTotalMinor() {
        return cartTotalMinor;
    }

    public void setCartTotalMinor(Long cartTotalMinor) {
        this.cartTotalMinor = cartTotalMinor;
    }

    public String getItemsJson() {
        return itemsJson;
    }

    public void setItemsJson(String itemsJson) {
        this.itemsJson = itemsJson;
    }

    public String getPayee() {
        return payee;
    }

    public void setPayee(String payee) {
        this.payee = payee;
    }

    public String getCategories() {
        return categories;
    }

    public void setCategories(String categories) {
        this.categories = categories;
    }

    public String getIntentText() {
        return intentText;
    }

    public void setIntentText(String intentText) {
        this.intentText = intentText;
    }

    public CommerceMandateStatus getStatus() {
        return status;
    }

    public void setStatus(CommerceMandateStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
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

    public String getBuyerAuthJson() {
        return buyerAuthJson;
    }

    public byte[] getBuyerSignature() {
        return buyerSignature;
    }

    public Instant getAuthorizedAt() {
        return authorizedAt;
    }

    public void applyBuyerAuthorization(String authJson, byte[] sig, Instant at) {
        this.buyerAuthJson = authJson;
        this.buyerSignature = sig;
        this.authorizedAt = at;
        this.status = CommerceMandateStatus.AUTHORIZED;
        this.updatedAt = at;
    }

    public Long getWalletDebitRef() {
        return walletDebitRef;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
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

    public void markCaptured(Long debitRef, Instant at) {
        this.status = CommerceMandateStatus.CAPTURED;
        this.walletDebitRef = debitRef;
        this.updatedAt = at;
    }

    public void markFailed(Instant at) {
        this.status = CommerceMandateStatus.FAILED;
        this.updatedAt = at;
    }

    public void markRefunded(Instant at) {
        this.status = CommerceMandateStatus.REFUNDED;
        this.updatedAt = at;
    }

    public void markRevoked(String decidedBy, String reason, Instant at) {
        this.status = CommerceMandateStatus.REVOKED;
        this.revokedAt = at;
        this.revokedBy = decidedBy;
        this.revokeReason = reason;
        this.updatedAt = at;
    }
}
