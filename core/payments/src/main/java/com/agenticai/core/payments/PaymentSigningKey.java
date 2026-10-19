package com.agenticai.core.payments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-tenant Ed25519 keypair that signs every {@link PaymentMandate}
 * (migration V345). The private half is stored ONLY as
 * {@code EnvelopeEncryptionService} ciphertext - plaintext key material
 * never persists. Managed by {@link MandateSigningService}; one ACTIVE row
 * per tenant schema.
 */
@Entity
@Table(name = "payment_signing_key")
public class PaymentSigningKey {

    @Id
    private UUID id;

    @Column(name = "key_id", nullable = false, unique = true, length = 64)
    private String keyId;

    @Column(name = "public_key", nullable = false)
    private byte[] publicKey;

    @Column(name = "private_key_encrypted", nullable = false, columnDefinition = "TEXT")
    private String privateKeyEncrypted;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected PaymentSigningKey() {
    }

    public PaymentSigningKey(String keyId, byte[] publicKey, String privateKeyEncrypted) {
        this.id = UUID.randomUUID();
        this.keyId = keyId;
        this.publicKey = publicKey;
        this.privateKeyEncrypted = privateKeyEncrypted;
    }

    public UUID getId() {
        return id;
    }

    public String getKeyId() {
        return keyId;
    }

    public byte[] getPublicKey() {
        return publicKey;
    }

    public String getPrivateKeyEncrypted() {
        return privateKeyEncrypted;
    }

    public String getStatus() {
        return status;
    }

    public void retire() {
        this.status = "RETIRED";
    }
}
