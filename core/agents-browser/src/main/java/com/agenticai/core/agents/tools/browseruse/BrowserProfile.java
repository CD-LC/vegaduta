package com.agenticai.core.agents.tools.browseruse;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of {@code browser_profile} (V443): a named, domain-scoped login a browser agent can
 * reuse. {@link #getStateEncrypted()} is AES-256-GCM ciphertext (the shared EncryptionService) of
 * the domain-filtered Playwright storageState; it is never exposed outside {@link
 * BrowserProfileService}. See {@link BrowserProfileService} for the rules around it.
 */
@Entity
@Table(name = "browser_profile")
public class BrowserProfile {

    public static final String SCOPE_TENANT = "TENANT";
    public static final String SCOPE_USER = "USER";

    @Id private UUID id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "scope", nullable = false, length = 16)
    private String scope;

    @Column(name = "owner_subject", length = 255)
    private String ownerSubject;

    @Column(name = "allowed_domains", nullable = false, columnDefinition = "TEXT")
    private String allowedDomains;

    @Column(name = "state_encrypted", columnDefinition = "TEXT")
    private String stateEncrypted;

    @Column(name = "state_bytes", nullable = false)
    private int stateBytes;

    @Column(name = "state_entries", nullable = false)
    private int stateEntries;

    @Column(name = "ttl_days", nullable = false)
    private int ttlDays;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_saved_at")
    private Instant lastSavedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BrowserProfile() {
        // JPA
    }

    BrowserProfile(
            String name,
            String scope,
            String ownerSubject,
            List<String> allowedDomains,
            int ttlDays,
            String createdBy,
            Instant now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.scope = scope;
        this.ownerSubject = ownerSubject;
        this.allowedDomains = BrowserStorageState.joinDomains(allowedDomains);
        this.ttlDays = ttlDays;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getScope() {
        return scope;
    }

    public String getOwnerSubject() {
        return ownerSubject;
    }

    public List<String> getAllowedDomainList() {
        return BrowserStorageState.splitDomains(allowedDomains);
    }

    String getStateEncrypted() {
        return stateEncrypted;
    }

    public boolean hasState() {
        return stateEncrypted != null && !stateEncrypted.isBlank();
    }

    public int getStateBytes() {
        return stateBytes;
    }

    public int getStateEntries() {
        return stateEntries;
    }

    public int getTtlDays() {
        return ttlDays;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastSavedAt() {
        return lastSavedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** True once the (sliding) TTL has lapsed; a profile with no expiry never expires. */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    void rename(String newName, Instant now) {
        this.name = newName;
        this.updatedAt = now;
    }

    void setAllowedDomains(List<String> domains, Instant now) {
        this.allowedDomains = BrowserStorageState.joinDomains(domains);
        this.updatedAt = now;
    }

    void setTtlDays(int ttlDays, Instant now) {
        this.ttlDays = ttlDays;
        this.updatedAt = now;
    }

    void markUsed(Instant now) {
        this.lastUsedAt = now;
    }

    /** Stores freshly saved (already encrypted) state and slides the expiry forward by ttlDays. */
    void storeState(String encrypted, int bytes, int entries, Instant now) {
        this.stateEncrypted = encrypted;
        this.stateBytes = bytes;
        this.stateEntries = entries;
        this.lastSavedAt = now;
        this.expiresAt = ttlDays > 0 ? now.plus(Duration.ofDays(ttlDays)) : null;
        this.updatedAt = now;
    }

    /** Forgets the saved login but keeps the profile (name, scope, domains, attachments). */
    void clearState(Instant now) {
        this.stateEncrypted = null;
        this.stateBytes = 0;
        this.stateEntries = 0;
        this.expiresAt = null;
        this.updatedAt = now;
    }
}
