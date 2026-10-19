package com.agenticai.core.audit.seal;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Response shapes of the audit-seal endpoints (list, verify, export). Verify returns {@link AuditSealVerifier.Result}. */
public final class AuditSealDtos {

    private AuditSealDtos() {
    }

    /** Export bundle format id; scripts/verify-audit-export.py checks it. */
    public static final String EXPORT_FORMAT = "vegaduta-audit-seal-export/v1";

    /** One seal in the listing endpoint (signature material is in the export). */
    public record SealView(
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
        String keyId,
        boolean signed,
        Instant sealedAt,
        Instant purgedAt
    ) {
        public static SealView from(AuditSeal s) {
            return new SealView(
                s.getId(), s.getSeq(), s.getTenantScope(), s.getFirstRowId(), s.getFirstRowAt(), s.getLastRowId(),
                s.getLastRowAt(), s.getRowCount(), s.getRangeHash(), s.getPrevSealHash(), s.getSealHash(),
                s.getAlgorithm(), s.getKeyId(), s.getSignature() != null && !s.getSignature().isBlank(),
                s.getSealedAt(), s.getPurgedAt()
            );
        }
    }

    public record SealPage(List<SealView> items, int page, int size, long total) {
    }

    /** A seal inside an export: exact microsecond boundaries (what the hash covers) plus ISO times for humans. */
    public record ExportSeal(
        long seq,
        String tenantScope,
        String firstRowId,
        Instant firstRowAt,
        long firstRowAtMicros,
        String lastRowId,
        Instant lastRowAt,
        long lastRowAtMicros,
        int rowCount,
        String rangeHash,
        String prevSealHash,
        String sealHash,
        String algorithm,
        String signature,
        String keyId,
        String publicKey,
        Instant sealedAt,
        boolean purged
    ) {
        public static ExportSeal from(AuditSeal s) {
            return new ExportSeal(
                s.getSeq(), s.getTenantScope(), s.getFirstRowId().toString(), s.getFirstRowAt(),
                AuditSealHasher.toMicros(s.getFirstRowAt()), s.getLastRowId().toString(), s.getLastRowAt(),
                AuditSealHasher.toMicros(s.getLastRowAt()), s.getRowCount(), s.getRangeHash(),
                s.getPrevSealHash(), s.getSealHash(), s.getAlgorithm(), s.getSignature(), s.getKeyId(),
                s.getPublicKey(), s.getSealedAt(), s.isPurged()
            );
        }
    }

    /** The hash-relevant fields of one audit row, nothing else. {@code createdAt} is informational only. */
    public record ExportRow(
        String id,
        String actorId,
        String actionType,
        String targetType,
        String targetId,
        String outcome,
        String detail,
        long createdAtMicros,
        Instant createdAt
    ) {
        public static ExportRow from(AuditSealHasher.Row r) {
            return new ExportRow(
                r.id(), r.actorId(), r.actionType(), r.targetType(), r.targetId(), r.outcome(), r.detail(),
                r.createdAtMicros(), AuditSealHasher.fromMicros(r.createdAtMicros())
            );
        }
    }

    /** A signer key referenced by the seals; pin the platform's published key rather than trusting this. */
    public record ExportKey(String kid, String alg, String publicKey) {
    }

    /** The seal immediately before the first exported one, when the window starts mid-chain. */
    public record ExportPredecessor(long seq, String sealHash) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExportBundle(
        String format,
        Instant generatedAt,
        Instant from,
        Instant to,
        boolean truncated,
        Map<String, String> algorithm,
        ExportPredecessor predecessor,
        List<ExportKey> keys,
        List<ExportSeal> seals,
        List<ExportRow> rows
    ) {
    }
}
