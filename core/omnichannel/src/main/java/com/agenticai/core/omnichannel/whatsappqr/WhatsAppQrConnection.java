package com.agenticai.core.omnichannel.whatsappqr;

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
 * A tenant's linked-device WhatsApp session on the operator's whatsapp-qr bridge (migration V27's
 * {@code whatsapp_qr_connection} table - the bridge services that originally consumed it were not
 * part of the open-source import; this package rebuilds them).
 *
 * <p>The WhatsApp credential itself never lives here: the linked-device key material stays in the
 * bridge's own data dir. This row is only the join between the tenant and one bridge session:
 * {@code sessionKey} (a random UUID generated at connect time, linked to this tenant schema in
 * control_plane via {@code ExternalAccountProvider.WHATSAPP_QR}) and the phone number the scan
 * revealed. One active row per tenant schema.
 */
@Entity
@Table(name = "whatsapp_qr_connection")
public class WhatsAppQrConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_key", nullable = false, unique = true, length = 64)
    private String sessionKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WhatsAppQrStatus status;

    @Column(name = "phone_number", length = 32)
    private String phoneNumber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WhatsAppQrConnection() {
        // JPA
    }

    public WhatsAppQrConnection(String sessionKey) {
        this.sessionKey = sessionKey;
        this.status = WhatsAppQrStatus.PAIRING;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** A QR scan completed on the bridge; the linked device is live. */
    public void markActive(String phoneNumber) {
        this.status = WhatsAppQrStatus.ACTIVE;
        if (phoneNumber != null && !phoneNumber.isBlank()) {
            this.phoneNumber = phoneNumber;
        }
        this.updatedAt = Instant.now();
    }

    /** Re-pairing on the same row (operator chose "link a different number"). */
    public void markPairing(String sessionKey) {
        this.sessionKey = sessionKey;
        this.status = WhatsAppQrStatus.PAIRING;
        this.phoneNumber = null;
        this.updatedAt = Instant.now();
    }

    /** Tenant-initiated unlink or a phone-side logout - the bridge session is gone either way. */
    public void markDisconnected() {
        this.status = WhatsAppQrStatus.DISCONNECTED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public WhatsAppQrStatus getStatus() {
        return status;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
