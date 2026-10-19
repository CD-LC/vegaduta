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
 * A pay-per-call offer a tenant publishes for one of its agents (migration V405, {@code
 * control_plane} schema): "GET /x402/{slug} costs {@link #priceMinor} of {@link #currency} via the
 * internal wallet-delegation scheme, or {@link #evmPriceAtomic} of an EVM token via the {@code
 * exact}/EIP-3009 scheme".
 *
 * <p>Control-plane because the public {@code /x402/{slug}} request arrives with no TenantContext -
 * the offer must resolve schema-free, and {@link #tenantId} records which schema owns it for the
 * settlement hop.
 */
@Entity
@Table(name = "x402_offer", schema = "control_plane")
public class X402Offer {

    public enum Status {
        ACTIVE,
        DISABLED
    }

    @Id private UUID id;

    @Column(name = "slug", nullable = false, length = 128)
    private String slug;

    /** The seller's tenant SCHEMA NAME (TenantContext#getTenantId). */
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /** The seller's wallet (in the tenant schema) settlement credits go to. */
    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Internal-scheme price: minor units of {@link #currency}. */
    @Column(name = "price_minor", nullable = false)
    private long priceMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Optional EVM {@code exact}-scheme price in token atomic units. */
    @Column(name = "evm_price_atomic")
    private Long evmPriceAtomic;

    /** EVM token contract address. */
    @Column(name = "evm_asset", length = 64)
    private String evmAsset;

    @Column(name = "evm_network", length = 32)
    private String evmNetwork;

    /** EIP-712 domain name/version of the token (USDC: "USD Coin"/"2"). */
    @Column(name = "evm_asset_name", length = 64)
    private String evmAssetName;

    @Column(name = "evm_asset_version", length = 16)
    private String evmAssetVersion;

    /** EVM address receiving {@code exact}-scheme payments. */
    @Column(name = "pay_to", length = 256)
    private String payTo;

    /**
     * JSON served to the caller once payment verifies - the paid resource itself (v1 serves a
     * static payload configured at publish; upstream proxying is deliberately not built - SSRF
     * surface).
     */
    @Column(name = "resource_payload", columnDefinition = "TEXT")
    private String resourcePayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected X402Offer() {}

    /** Whether this offer also advertises an EVM {@code exact}-scheme requirement. */
    public boolean hasEvmRequirement() {
        return evmPriceAtomic != null
                && evmAsset != null
                && evmNetwork != null
                && evmAssetName != null
                && evmAssetVersion != null
                && payTo != null;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public void setWalletId(UUID walletId) {
        this.walletId = walletId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getPriceMinor() {
        return priceMinor;
    }

    public void setPriceMinor(long priceMinor) {
        this.priceMinor = priceMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Long getEvmPriceAtomic() {
        return evmPriceAtomic;
    }

    public void setEvmPriceAtomic(Long evmPriceAtomic) {
        this.evmPriceAtomic = evmPriceAtomic;
    }

    public String getEvmAsset() {
        return evmAsset;
    }

    public void setEvmAsset(String evmAsset) {
        this.evmAsset = evmAsset;
    }

    public String getEvmNetwork() {
        return evmNetwork;
    }

    public void setEvmNetwork(String evmNetwork) {
        this.evmNetwork = evmNetwork;
    }

    public String getEvmAssetName() {
        return evmAssetName;
    }

    public void setEvmAssetName(String evmAssetName) {
        this.evmAssetName = evmAssetName;
    }

    public String getEvmAssetVersion() {
        return evmAssetVersion;
    }

    public void setEvmAssetVersion(String evmAssetVersion) {
        this.evmAssetVersion = evmAssetVersion;
    }

    public String getPayTo() {
        return payTo;
    }

    public void setPayTo(String payTo) {
        this.payTo = payTo;
    }

    public String getResourcePayload() {
        return resourcePayload;
    }

    public void setResourcePayload(String resourcePayload) {
        this.resourcePayload = resourcePayload;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
