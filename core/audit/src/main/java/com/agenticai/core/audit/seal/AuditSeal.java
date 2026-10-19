package com.agenticai.core.audit.seal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One tamper-evidence seal over a contiguous range of {@code audit_log_entry} rows (V422). Lives in the
 * tenant schema next to the rows it covers. Append-only like the log itself: a database trigger refuses
 * DELETE and refuses UPDATE of everything except {@code purged_at}, the retention marker (it is
 * deliberately NOT part of the seal hash - see {@link AuditSealService#markPurgedBefore}).
 *
 * <p>Hash/signature semantics are documented on {@link AuditSealHasher}; the signature column holds a
 * compact JWS (EdDSA) whose payload binds {@code seal_hash}, {@code seq} and the tenant scope.
 */
@Entity
@Table(name = "audit_seal")
public class AuditSeal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "seq", nullable = false, updatable = false)
    private long seq;

    @Column(name = "tenant_scope", nullable = false, updatable = false, length = 255)
    private String tenantScope;

    @Column(name = "first_row_id", nullable = false, updatable = false)
    private UUID firstRowId;

    @Column(name = "first_row_at", nullable = false, updatable = false)
    private Instant firstRowAt;

    @Column(name = "last_row_id", nullable = false, updatable = false)
    private UUID lastRowId;

    @Column(name = "last_row_at", nullable = false, updatable = false)
    private Instant lastRowAt;

    @Column(name = "row_count", nullable = false, updatable = false)
    private int rowCount;

    @Column(name = "range_hash", nullable = false, updatable = false, length = 64)
    private String rangeHash;

    @Column(name = "prev_seal_hash", nullable = false, updatable = false, length = 64)
    private String prevSealHash;

    @Column(name = "seal_hash", nullable = false, updatable = false, length = 64)
    private String sealHash;

    @Column(name = "algorithm", nullable = false, updatable = false, length = 32)
    private String algorithm;

    @Column(name = "signature", updatable = false, columnDefinition = "TEXT")
    private String signature;

    @Column(name = "key_id", updatable = false, length = 128)
    private String keyId;

    @Column(name = "public_key", updatable = false, length = 128)
    private String publicKey;

    @Column(name = "sealed_at", nullable = false, updatable = false)
    private Instant sealedAt;

    @Column(name = "purged_at")
    private Instant purgedAt;

    protected AuditSeal() {
        // JPA
    }

    public AuditSeal(
        String tenantScope,
        long seq,
        UUID firstRowId,
        Instant firstRowAt,
        UUID lastRowId,
        Instant lastRowAt,
        int rowCount,
        String rangeHash,
        String prevSealHash,
        String sealHash,
        String algorithm,
        String signature,
        String keyId,
        String publicKey,
        Instant sealedAt
    ) {
        this.tenantScope = tenantScope;
        this.seq = seq;
        this.firstRowId = firstRowId;
        this.firstRowAt = firstRowAt;
        this.lastRowId = lastRowId;
        this.lastRowAt = lastRowAt;
        this.rowCount = rowCount;
        this.rangeHash = rangeHash;
        this.prevSealHash = prevSealHash;
        this.sealHash = sealHash;
        this.algorithm = algorithm;
        this.signature = signature;
        this.keyId = keyId;
        this.publicKey = publicKey;
        this.sealedAt = sealedAt;
    }

    public UUID getId() {
        return id;
    }

    public long getSeq() {
        return seq;
    }

    public String getTenantScope() {
        return tenantScope;
    }

    public UUID getFirstRowId() {
        return firstRowId;
    }

    public Instant getFirstRowAt() {
        return firstRowAt;
    }

    public UUID getLastRowId() {
        return lastRowId;
    }

    public Instant getLastRowAt() {
        return lastRowAt;
    }

    public int getRowCount() {
        return rowCount;
    }

    public String getRangeHash() {
        return rangeHash;
    }

    public String getPrevSealHash() {
        return prevSealHash;
    }

    public String getSealHash() {
        return sealHash;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String getSignature() {
        return signature;
    }

    public String getKeyId() {
        return keyId;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public Instant getSealedAt() {
        return sealedAt;
    }

    public Instant getPurgedAt() {
        return purgedAt;
    }

    public boolean isPurged() {
        return purgedAt != null;
    }

    public AuditSealRecord toRecord() {
        return new AuditSealRecord(
            id, seq, tenantScope, firstRowId, firstRowAt, lastRowId, lastRowAt, rowCount, rangeHash,
            prevSealHash, sealHash, algorithm, signature, keyId, publicKey, isPurged()
        );
    }
}
