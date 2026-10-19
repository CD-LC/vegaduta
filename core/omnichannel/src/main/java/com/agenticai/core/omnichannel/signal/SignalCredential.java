package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
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
 * A tenant's connection to a signal-cli REST API the operator runs (bbernhard/signal-cli-rest-api):
 * its base URL and the registered number the agent speaks as. One active row per tenant schema.
 *
 * <p>Signal has no platform-held secret to store. The only secret is an OPTIONAL bearer token for
 * a reverse proxy in front of that REST API, kept as ciphertext like every channel credential.
 */
@Entity
@Table(name = "signal_credential")
public class SignalCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    @Column(name = "account_number", nullable = false, length = 32)
    private String accountNumber;

    @Column(name = "encrypted_auth_token", columnDefinition = "TEXT")
    private String encryptedAuthToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "unknown_senders", nullable = false, length = 16)
    private UnknownSenderPolicy unknownSenders;

    @Enumerated(EnumType.STRING)
    @Column(name = "group_mode", nullable = false, length = 16)
    private SignalGroupMode groupMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SignalCredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    protected SignalCredential() {
        // JPA
    }

    public SignalCredential(
            String baseUrl,
            String accountNumber,
            String encryptedAuthToken,
            UnknownSenderPolicy unknownSenders,
            SignalGroupMode groupMode) {
        this.baseUrl = baseUrl;
        this.accountNumber = accountNumber;
        this.encryptedAuthToken = encryptedAuthToken;
        this.unknownSenders = unknownSenders;
        this.groupMode = groupMode;
        this.status = SignalCredentialStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.lastValidatedAt = now;
    }

    public void reactivate(
            String baseUrl,
            String accountNumber,
            String encryptedAuthToken,
            UnknownSenderPolicy unknownSenders,
            SignalGroupMode groupMode) {
        this.baseUrl = baseUrl;
        this.accountNumber = accountNumber;
        this.encryptedAuthToken = encryptedAuthToken;
        this.unknownSenders = unknownSenders;
        this.groupMode = groupMode;
        this.status = SignalCredentialStatus.ACTIVE;
        this.updatedAt = Instant.now();
        this.lastValidatedAt = this.updatedAt;
    }

    /** Changes who the agent answers without re-entering the connection details. */
    public void updatePolicy(UnknownSenderPolicy unknownSenders, SignalGroupMode groupMode) {
        if (unknownSenders != null) {
            this.unknownSenders = unknownSenders;
        }
        if (groupMode != null) {
            this.groupMode = groupMode;
        }
        this.updatedAt = Instant.now();
    }

    /** Tenant-initiated disconnect - keeps the row so the console can still show what was connected; every consumer resolves ACTIVE-only. */
    public void disconnect() {
        this.status = SignalCredentialStatus.DISCONNECTED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getEncryptedAuthToken() {
        return encryptedAuthToken;
    }

    public UnknownSenderPolicy getUnknownSenders() {
        return unknownSenders;
    }

    public SignalGroupMode getGroupMode() {
        return groupMode;
    }

    public SignalCredentialStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastValidatedAt() {
        return lastValidatedAt;
    }
}
