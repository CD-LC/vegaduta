package com.agenticai.core.audit.seal;

import java.time.Instant;
import java.util.UUID;

/**
 * Plain-value view of one stored seal - what {@link AuditSealVerifier} and {@link AuditSealSignatures}
 * consume, so the verification logic stays free of JPA and is unit-testable with hand-built values.
 * {@code id} may be null in tests.
 */
public record AuditSealRecord(
    UUID id,
    long seq,
    String tenantScope,
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
    boolean purged
) {

    /** Recomputes the seal hash from this record's own fields (what {@code sealHash} must equal). */
    public String recomputeSealHash() {
        return AuditSealHasher.sealHash(
            tenantScope,
            seq,
            prevSealHash,
            rangeHash,
            rowCount,
            firstRowId.toString(),
            AuditSealHasher.toMicros(firstRowAt),
            lastRowId.toString(),
            AuditSealHasher.toMicros(lastRowAt)
        );
    }
}
