package com.agenticai.core.audit.seal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.agenticai.core.audit.AuditLogEntry;
import com.agenticai.core.audit.AuditOutcome;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Canonicalisation must be byte-for-byte deterministic: the offline verifier (scripts/verify-audit-export.py)
 * re-implements it, and the golden values below were produced by THAT script, so a drift in either
 * implementation fails here.
 */
class AuditSealHasherTest {

    private static final AuditSealHasher.Row ROW1 = new AuditSealHasher.Row(
        "00000000-0000-0000-0000-000000000001", "alice", "agent.create", "agent", "a-1", "SUCCESS",
        "h\u00e9llo", 1700000000123456L);
    private static final AuditSealHasher.Row ROW2 = new AuditSealHasher.Row(
        "00000000-0000-0000-0000-000000000002", null, "login", null, null, "FAILURE", null, 1700000001000000L);

    @Test
    void rowDigestsMatchTheOfflineVerifierGoldenValues() {
        assertEquals("2478820bb21c2bd91c16ec574b6122fff7c3f402be1b40a4f690902ccd25d2b9",
            HexFormat.of().formatHex(AuditSealHasher.rowDigest(ROW1)));
        assertEquals("860268f648e0460802e6166638243930735340acc6a6f809c339bbe75251e227",
            HexFormat.of().formatHex(AuditSealHasher.rowDigest(ROW2)));
    }

    @Test
    void rangeAndSealHashesMatchTheOfflineVerifierGoldenValues() {
        assertEquals("d0f95babef7b28ee66b417398a4ffc79272df1557d219d742d73645004ef6422",
            AuditSealHasher.rangeHash(List.of(ROW1, ROW2)));
        assertEquals("9a897e1d5c759a2c8fd8c0fe5782b43831da4476be4731c963feb094ad9c33b2",
            AuditSealHasher.rangeHash(List.of(ROW1)));
        assertEquals("67c8a441475ed4eb3ad1a9b4566ebbc5d1f0b6cfa82603546fd584a8418ce475",
            AuditSealHasher.sealHash("t_acme", 1, AuditSealHasher.GENESIS_HASH,
                "d0f95babef7b28ee66b417398a4ffc79272df1557d219d742d73645004ef6422", 2,
                ROW1.id(), ROW1.createdAtMicros(), ROW2.id(), ROW2.createdAtMicros()));
    }

    @Test
    void canonicalisationIsDeterministic() {
        AuditSealHasher.Row copy = new AuditSealHasher.Row(ROW1.id(), ROW1.actorId(), ROW1.actionType(),
            ROW1.targetType(), ROW1.targetId(), ROW1.outcome(), ROW1.detail(), ROW1.createdAtMicros());
        assertArrayEquals(AuditSealHasher.canonicalRow(ROW1), AuditSealHasher.canonicalRow(copy));
        assertEquals(AuditSealHasher.rangeHash(List.of(ROW1, ROW2)), AuditSealHasher.rangeHash(List.of(copy, ROW2)));
    }

    @Test
    void nullIsDistinctFromEmptyAndFieldBoundariesAreUnambiguous() {
        AuditSealHasher.Row nullDetail = withDetail(null);
        AuditSealHasher.Row emptyDetail = withDetail("");
        assertNotEquals(HexFormat.of().formatHex(AuditSealHasher.rowDigest(nullDetail)),
            HexFormat.of().formatHex(AuditSealHasher.rowDigest(emptyDetail)));

        AuditSealHasher.Row ab = new AuditSealHasher.Row("i", "ab", "c", null, null, "SUCCESS", null, 1L);
        AuditSealHasher.Row bc = new AuditSealHasher.Row("i", "a", "bc", null, null, "SUCCESS", null, 1L);
        assertNotEquals(HexFormat.of().formatHex(AuditSealHasher.rowDigest(ab)),
            HexFormat.of().formatHex(AuditSealHasher.rowDigest(bc)));
    }

    @Test
    void rangeHashIsOrderSensitiveAndDetectsAnyChange() {
        String base = AuditSealHasher.rangeHash(List.of(ROW1, ROW2));
        assertNotEquals(base, AuditSealHasher.rangeHash(List.of(ROW2, ROW1)));
        assertNotEquals(base, AuditSealHasher.rangeHash(List.of(ROW1)));
        assertNotEquals(base, AuditSealHasher.rangeHash(List.of(ROW1, ROW2, ROW2)));
        AuditSealHasher.Row edited = new AuditSealHasher.Row(ROW2.id(), "mallory", ROW2.actionType(),
            ROW2.targetType(), ROW2.targetId(), ROW2.outcome(), ROW2.detail(), ROW2.createdAtMicros());
        assertNotEquals(base, AuditSealHasher.rangeHash(List.of(ROW1, edited)));
    }

    @Test
    void emptyRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AuditSealHasher.rangeHash(List.of()));
    }

    @Test
    void sealHashBindsEveryField() {
        String range = AuditSealHasher.rangeHash(List.of(ROW1));
        String base = AuditSealHasher.sealHash("t", 1, AuditSealHasher.GENESIS_HASH, range, 1,
            ROW1.id(), 10L, ROW1.id(), 10L);
        assertNotEquals(base, AuditSealHasher.sealHash("u", 1, AuditSealHasher.GENESIS_HASH, range, 1,
            ROW1.id(), 10L, ROW1.id(), 10L));
        assertNotEquals(base, AuditSealHasher.sealHash("t", 2, AuditSealHasher.GENESIS_HASH, range, 1,
            ROW1.id(), 10L, ROW1.id(), 10L));
        assertNotEquals(base, AuditSealHasher.sealHash("t", 1, "1".repeat(64), range, 1,
            ROW1.id(), 10L, ROW1.id(), 10L));
        assertNotEquals(base, AuditSealHasher.sealHash("t", 1, AuditSealHasher.GENESIS_HASH, range, 2,
            ROW1.id(), 10L, ROW1.id(), 10L));
        assertNotEquals(base, AuditSealHasher.sealHash("t", 1, AuditSealHasher.GENESIS_HASH, range, 1,
            ROW1.id(), 11L, ROW1.id(), 10L));
    }

    @Test
    void microsRoundTripAndTruncateSubMicrosecondPrecision() {
        Instant i = Instant.parse("2026-10-03T12:34:56.123456789Z");
        long micros = AuditSealHasher.toMicros(i);
        assertEquals(Instant.parse("2026-10-03T12:34:56.123456Z"), AuditSealHasher.fromMicros(micros));
        assertEquals(1700000000123456L, AuditSealHasher.toMicros(AuditSealHasher.fromMicros(1700000000123456L)));
    }

    @Test
    void rowOfMapsAnEntityToTheCanonicalRow() {
        AuditLogEntry entry = new AuditLogEntry("alice", "agent.create", "agent", "a-1", AuditOutcome.SUCCESS, "d");
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        ReflectionTestUtils.setField(entry, "id", id);
        ReflectionTestUtils.setField(entry, "createdAt", AuditSealHasher.fromMicros(42L));
        AuditSealHasher.Row row = AuditSealHasher.rowOf(entry);
        assertEquals(id.toString(), row.id());
        assertEquals("SUCCESS", row.outcome());
        assertEquals(42L, row.createdAtMicros());
        assertEquals("alice", row.actorId());
    }

    private static AuditSealHasher.Row withDetail(String detail) {
        return new AuditSealHasher.Row(ROW2.id(), ROW2.actorId(), ROW2.actionType(), ROW2.targetType(),
            ROW2.targetId(), ROW2.outcome(), detail, ROW2.createdAtMicros());
    }
}
