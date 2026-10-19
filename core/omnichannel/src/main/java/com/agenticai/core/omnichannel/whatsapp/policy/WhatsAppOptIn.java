package com.agenticai.core.omnichannel.whatsapp.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Proof that a recipient gave the business their number; one row per phone (digits only). */
@Entity
@Table(name = "whatsapp_opt_in")
public class WhatsAppOptIn {

    @Id private UUID id;

    @Column(name = "phone_digits", nullable = false, length = 32)
    private String phoneDigits;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(columnDefinition = "text")
    private String evidence;

    @Column(name = "consented_at", nullable = false)
    private Instant consentedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "recorded_by")
    private String recordedBy;

    protected WhatsAppOptIn() {
        // JPA
    }

    public WhatsAppOptIn(
            String phoneDigits, String source, String evidence, Instant consentedAt, String by) {
        this.id = UUID.randomUUID();
        this.phoneDigits = phoneDigits;
        record(source, evidence, consentedAt, by);
    }

    public void record(String source, String evidence, Instant consentedAt, String by) {
        this.source = source;
        this.evidence = evidence;
        this.consentedAt = consentedAt;
        this.recordedBy = by;
        this.revokedAt = null;
    }

    public void revoke(Instant at) {
        this.revokedAt = at;
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public UUID getId() {
        return id;
    }

    public String getPhoneDigits() {
        return phoneDigits;
    }

    public String getSource() {
        return source;
    }

    public Instant getConsentedAt() {
        return consentedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
