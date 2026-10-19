package com.agenticai.core.omnichannel.whatsapp.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Template sends to this recipient are stopped after a Meta marketing-limit rejection. */
@Entity
@Table(name = "whatsapp_recipient_backoff")
public class WhatsAppRecipientBackoff {

    @Id
    @Column(name = "phone_digits", length = 32)
    private String phoneDigits;

    @Column(name = "blocked_until")
    private Instant blockedUntil;

    @Column(nullable = false)
    private boolean permanent;

    @Column(name = "meta_code", nullable = false)
    private int metaCode;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WhatsAppRecipientBackoff() {
        // JPA
    }

    public WhatsAppRecipientBackoff(String phoneDigits) {
        this.phoneDigits = phoneDigits;
    }

    public void blockUntil(Instant until, int metaCode, Instant now) {
        this.blockedUntil = until;
        this.permanent = false;
        this.metaCode = metaCode;
        this.updatedAt = now;
    }

    public void blockPermanently(int metaCode, Instant now) {
        this.blockedUntil = null;
        this.permanent = true;
        this.metaCode = metaCode;
        this.updatedAt = now;
    }

    public boolean blocksAt(Instant now) {
        return permanent || (blockedUntil != null && now.isBefore(blockedUntil));
    }

    public String getPhoneDigits() {
        return phoneDigits;
    }

    public Instant getBlockedUntil() {
        return blockedUntil;
    }

    public boolean isPermanent() {
        return permanent;
    }

    public int getMetaCode() {
        return metaCode;
    }
}
