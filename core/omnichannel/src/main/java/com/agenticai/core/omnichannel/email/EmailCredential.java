package com.agenticai.core.omnichannel.email;

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
 * A tenant's own IMAP mailbox connection - the PRD's "Mail-Checking Agent"
 * (Section 3.2) made real and per-tenant, BYOK, same shape as every other
 * channel credential here. Unlike {@code EmailOutputService} (a deliberately
 * shared, platform-wide SMTP relay for one-off workflow-output emails), this
 * is a genuine per-tenant mailbox: only a tenant's own inbox lets {@link
 * EmailInboundPollingJob} know which tenant an inbound email belongs to at
 * all (there's no shared-mailbox routing scheme that wouldn't require
 * asking every customer to email a tenant-specific alias).
 *
 * <p>Outbound replies reuse the platform's existing {@code JavaMailSender}
 * bean (see {@code EmailChannelAdapter}) rather than opening a new SMTP
 * connection per tenant - this entity only holds the *inbound* IMAP
 * connection details.
 */
@Entity
@Table(name = "email_credential")
public class EmailCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "imap_host", nullable = false, length = 255)
    private String imapHost;

    @Column(name = "imap_port", nullable = false)
    private int imapPort;

    @Column(name = "imap_username", nullable = false, length = 255)
    private String imapUsername;

    @Column(name = "encrypted_imap_password", nullable = false, columnDefinition = "TEXT")
    private String encryptedImapPassword;

    @Column(name = "use_ssl", nullable = false)
    private boolean useSsl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EmailCredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    protected EmailCredential() {
        // JPA
    }

    public EmailCredential(String imapHost, int imapPort, String imapUsername, String encryptedImapPassword, boolean useSsl) {
        this.imapHost = imapHost;
        this.imapPort = imapPort;
        this.imapUsername = imapUsername;
        this.encryptedImapPassword = encryptedImapPassword;
        this.useSsl = useSsl;
        this.status = EmailCredentialStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.lastValidatedAt = now;
    }

    public void reactivate(String imapHost, int imapPort, String imapUsername, String encryptedImapPassword, boolean useSsl) {
        this.imapHost = imapHost;
        this.imapPort = imapPort;
        this.imapUsername = imapUsername;
        this.encryptedImapPassword = encryptedImapPassword;
        this.useSsl = useSsl;
        this.status = EmailCredentialStatus.ACTIVE;
        this.updatedAt = Instant.now();
        this.lastValidatedAt = Instant.now();
    }

    public void markPolled() {
        this.lastPolledAt = Instant.now();
    }

    /** Tenant-initiated disconnect - keeps the row so the console can still show what was connected; every consumer resolves ACTIVE-only, so this alone stops all use. */
    public void disconnect() {
        this.status = EmailCredentialStatus.DISCONNECTED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getImapHost() {
        return imapHost;
    }

    public int getImapPort() {
        return imapPort;
    }

    public String getImapUsername() {
        return imapUsername;
    }

    public String getEncryptedImapPassword() {
        return encryptedImapPassword;
    }

    public boolean isUseSsl() {
        return useSsl;
    }

    public EmailCredentialStatus getStatus() {
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

    public Instant getLastPolledAt() {
        return lastPolledAt;
    }
}
