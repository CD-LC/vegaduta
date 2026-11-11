package com.agenticai.core.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The signed canonical document is verified verbatim at execution time, so the rules that make it
 * deterministic ARE the security contract: lists are sorted before serialization (input order can
 * never change the bytes), the threshold only appears when it applies, and {@code canonicalHash} is
 * the SHA-256 hex the grant wizard shows for review.
 */
class MandateCanonicalizerTest {

    private final MandateCanonicalizer c = new MandateCanonicalizer();

    private static final UUID MANDATE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AGENT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2027-10-01T00:00:00Z");

    private String canonicalize(
            List<String> vpas,
            List<String> categories,
            MandateApprovalPolicy policy,
            Long threshold) {
        return c.canonicalize(
                MANDATE,
                "tenant-1",
                AGENT,
                "user-1",
                MandateScope.COLLECT,
                50_000L,
                200_000L,
                1_000_000L,
                vpas,
                categories,
                policy,
                threshold,
                FROM,
                UNTIL);
    }

    @Test
    void theDocumentIsDeterministicRegardlessOfListOrder() {
        String a =
                canonicalize(
                        List.of("b@upi", "a@upi"),
                        List.of("z-cat", "a-cat"),
                        MandateApprovalPolicy.ALWAYS,
                        null);
        String b =
                canonicalize(
                        List.of("a@upi", "b@upi"),
                        List.of("a-cat", "z-cat"),
                        MandateApprovalPolicy.ALWAYS,
                        null);
        assertEquals(a, b);
        assertTrue(a.contains("\"version\":\"" + MandateCanonicalizer.MANDATE_VERSION + "\""));
        assertEquals("vegaduta-upi-mandate/1", MandateCanonicalizer.MANDATE_VERSION);
        assertTrue(a.contains("\"allowed_vpas\":[\"a@upi\",\"b@upi\"]"), a);
        assertTrue(a.contains("\"allowed_merchant_categories\":[\"a-cat\",\"z-cat\"]"), a);
        assertTrue(a.contains("\"max_per_txn_paise\":50000"), a);
        assertTrue(a.contains("\"policy\":\"ALWAYS\""), a);
        assertFalse(a.contains("threshold_paise"), "no threshold for ALWAYS");
    }

    @Test
    void nullListsSerializeAsEmptyArraysNotMissingKeys() {
        String doc = canonicalize(null, null, MandateApprovalPolicy.NEVER, null);
        assertTrue(doc.contains("\"allowed_vpas\":[]"), doc);
        assertTrue(doc.contains("\"allowed_merchant_categories\":[]"), doc);
    }

    @Test
    void theThresholdIsWrittenOnlyForAboveThresholdWithAValue() {
        String with = canonicalize(null, null, MandateApprovalPolicy.ABOVE_THRESHOLD, 7_500L);
        assertTrue(with.contains("\"threshold_paise\":7500"), with);
        String without = canonicalize(null, null, MandateApprovalPolicy.ABOVE_THRESHOLD, null);
        assertFalse(
                without.contains("threshold_paise"),
                "a null threshold stays absent rather than serializing as null");
    }

    @Test
    void anyFieldDifferenceChangesTheSignedBytes() {
        String a = canonicalize(List.of("a@upi"), null, MandateApprovalPolicy.ALWAYS, null);
        String b =
                c.canonicalize(
                        MANDATE,
                        "tenant-2",
                        AGENT,
                        "user-1",
                        MandateScope.COLLECT,
                        50_000L,
                        200_000L,
                        1_000_000L,
                        List.of("a@upi"),
                        null,
                        MandateApprovalPolicy.ALWAYS,
                        null,
                        FROM,
                        UNTIL);
        assertNotEquals(a, b, "a different tenant can never share a canonical document");
    }

    @Test
    void canonicalHashIsSha256HexOfTheDocument() {
        String doc = canonicalize(null, null, MandateApprovalPolicy.ALWAYS, null);
        String hash = c.canonicalHash(doc);
        assertEquals(64, hash.length());
        assertTrue(hash.chars().allMatch(ch -> "0123456789abcdef".indexOf(ch) >= 0), hash);
        assertEquals(hash, c.canonicalHash(doc));
        assertNotEquals(hash, c.canonicalHash(doc + " "));
    }
}
