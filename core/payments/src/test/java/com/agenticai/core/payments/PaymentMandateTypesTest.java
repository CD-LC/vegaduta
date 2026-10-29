package com.agenticai.core.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The mandate's small value types and its entity lifecycle: {@link
 * MandateScope}'s direction matrix, {@link MandateApprovalPolicy}'s
 * amount-aware decision, the outbox event-type constants, and {@link
 * PaymentMandate}'s revoke/expire transitions - a signed mandate is
 * immutable, so the only writes are those two status moves.
 */
class PaymentMandateTypesTest {

    @Test
    void scopeAllowsExactlyItsOwnDirection() {
        assertTrue(MandateScope.COLLECT.allowsCollection());
        assertFalse(MandateScope.COLLECT.allowsPayout());
        assertTrue(MandateScope.PAY.allowsPayout());
        assertFalse(MandateScope.PAY.allowsCollection());
        assertTrue(MandateScope.BOTH.allowsCollection());
        assertTrue(MandateScope.BOTH.allowsPayout());
        assertEquals(3, MandateScope.values().length);
    }

    @Test
    void approvalPolicyIsAmountAwareOnlyForAboveThreshold() {
        assertTrue(MandateApprovalPolicy.ALWAYS.requiresApproval(1, null));
        assertTrue(MandateApprovalPolicy.ALWAYS.requiresApproval(0, 0L));
        assertFalse(MandateApprovalPolicy.NEVER.requiresApproval(Long.MAX_VALUE, 0L));
        // ABOVE_THRESHOLD with no configured threshold can never trigger.
        assertFalse(MandateApprovalPolicy.ABOVE_THRESHOLD.requiresApproval(100, null));
        assertFalse(MandateApprovalPolicy.ABOVE_THRESHOLD.requiresApproval(100, 100L),
            "strictly greater-than - an amount equal to the threshold does not need approval");
        assertTrue(MandateApprovalPolicy.ABOVE_THRESHOLD.requiresApproval(101, 100L));
    }

    @Test
    void paymentEventTypeNamesAreTheStableOutboxContract() {
        assertEquals("payment.status.changed", PaymentEvents.STATUS_CHANGED);
        assertEquals("payment.mandate.granted", PaymentEvents.MANDATE_GRANTED);
        assertEquals("payment.mandate.revoked", PaymentEvents.MANDATE_REVOKED);
        assertEquals(4, MandateStatus.values().length);
    }

    @Test
    void aNewMandateStartsActiveWithDefaults() {
        PaymentMandate m = new PaymentMandate();
        assertEquals(MandateStatus.ACTIVE, m.getStatus());
        assertEquals(MandateApprovalPolicy.ALWAYS, m.getApprovalPolicy());
        assertNull(m.getRevokedAt());
        assertNull(m.getRevokedBy());
        assertNull(m.getRevokeReason());
    }

    @Test
    void markRevokedRecordsWhoWhenAndWhy() {
        PaymentMandate m = new PaymentMandate();
        Instant at = Instant.parse("2026-10-05T12:00:00Z");
        m.markRevoked("admin-1", "suspected misuse", at);
        assertEquals(MandateStatus.REVOKED, m.getStatus());
        assertEquals(at, m.getRevokedAt());
        assertEquals("admin-1", m.getRevokedBy());
        assertEquals("suspected misuse", m.getRevokeReason());
        assertEquals(at, m.getUpdatedAt());
    }

    @Test
    void markExpiredFlipsStatusWithoutTouchingRevocationFields() {
        PaymentMandate m = new PaymentMandate();
        Instant at = Instant.parse("2026-10-05T12:00:00Z");
        m.markExpired(at);
        assertEquals(MandateStatus.EXPIRED, m.getStatus());
        assertEquals(at, m.getUpdatedAt());
        assertNull(m.getRevokedAt());
    }

    @Test
    void mandateFieldsRoundTripThroughAccessors() {
        PaymentMandate m = new PaymentMandate();
        UUID id = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant until = Instant.parse("2027-10-01T00:00:00Z");
        m.setId(id);
        m.setAgentId(agent);
        m.setGrantedByUserId("user-9");
        m.setScope(MandateScope.COLLECT);
        m.setMaxPerTxnPaise(50_000);
        m.setMaxDailyPaise(200_000);
        m.setMaxMonthlyPaise(1_000_000);
        m.setAllowedVpas("[\"a@upi\"]");
        m.setAllowedMerchantCategories(null);
        m.setApprovalPolicy(MandateApprovalPolicy.ABOVE_THRESHOLD);
        m.setApprovalThresholdPaise(10_000L);
        m.setValidFrom(from);
        m.setValidUntil(until);
        m.setCanonicalJson("{\"version\":\"v\"}");
        m.setSignature(new byte[] {1, 2, 3});
        m.setKeyId("key-1");

        assertEquals(id, m.getId());
        assertEquals(agent, m.getAgentId());
        assertEquals("user-9", m.getGrantedByUserId());
        assertEquals(MandateScope.COLLECT, m.getScope());
        assertEquals(50_000, m.getMaxPerTxnPaise());
        assertEquals(200_000, m.getMaxDailyPaise());
        assertEquals(1_000_000, m.getMaxMonthlyPaise());
        assertEquals("[\"a@upi\"]", m.getAllowedVpas());
        assertNull(m.getAllowedMerchantCategories());
        assertEquals(MandateApprovalPolicy.ABOVE_THRESHOLD, m.getApprovalPolicy());
        assertEquals(10_000L, m.getApprovalThresholdPaise());
        assertEquals(from, m.getValidFrom());
        assertEquals(until, m.getValidUntil());
        assertEquals("{\"version\":\"v\"}", m.getCanonicalJson());
        assertEquals(3, m.getSignature().length);
        assertEquals("key-1", m.getKeyId());
        assertNotNull(m.getCreatedAt());
        assertNotNull(m.getUpdatedAt());
    }

    @Test
    void ledgerEntryKindsAreStableStringsAndFieldsRoundTrip() {
        assertEquals("CHARGE", MandateLedgerEntry.KIND_CHARGE);
        assertEquals("REVERSAL", MandateLedgerEntry.KIND_REVERSAL);
        UUID mandateId = UUID.randomUUID();
        UUID txnId = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 10, 5);
        MandateLedgerEntry e = new MandateLedgerEntry(
            mandateId, txnId, MandateLedgerEntry.KIND_CHARGE, 12_500, day, 45_000, "2026-10", 90_000);
        assertEquals(mandateId, e.getMandateId());
        assertEquals(txnId, e.getTransactionId());
        assertEquals("CHARGE", e.getEntryKind());
        assertEquals(12_500, e.getAmountPaise());
        assertEquals(day, e.getDayKey());
        assertEquals(45_000, e.getDailySpendPaise());
        assertEquals("2026-10", e.getMonthKey());
        assertEquals(90_000, e.getMonthlySpendPaise());
    }

    @Test
    void signingKeyStartsActiveAndRetires() {
        PaymentSigningKey key = new PaymentSigningKey("key-1", new byte[] {9}, "ciphertext");
        assertNotNull(key.getId());
        assertEquals("key-1", key.getKeyId());
        assertEquals(1, key.getPublicKey().length);
        assertEquals("ciphertext", key.getPrivateKeyEncrypted());
        assertEquals("ACTIVE", key.getStatus());
        key.retire();
        assertEquals("RETIRED", key.getStatus());
    }

    @Test
    void mandateViolationCarriesItsStableCode() {
        MandateViolationException e = new MandateViolationException("OVER_DAILY_CAP", "too much");
        assertEquals("OVER_DAILY_CAP", e.getCode());
        assertEquals("too much", e.getMessage());
    }
}
