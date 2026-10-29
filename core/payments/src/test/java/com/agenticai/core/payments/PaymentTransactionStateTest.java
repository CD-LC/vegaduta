package com.agenticai.core.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link PaymentTransaction}'s state machine ({@code INITIATED ->
 * PENDING_APPROVAL -> AWAITING_PAYMENT -> CAPTURED}, with FAILED/EXPIRED off
 * to the side): each mark* method is the only legal way into its state, a
 * terminal state always clears the reconciliation {@code nextPollAt}, and
 * {@code scheduleNextPoll} counts attempts.
 */
class PaymentTransactionStateTest {

    private static PaymentTransaction transaction() {
        return new PaymentTransaction(
            UUID.randomUUID(), UUID.randomUUID(), "session-1",
            PaymentTransactionType.UPI_COLLECT, 12_500, "shop@upi", "test purchase",
            "books", "idem-1", Instant.parse("2026-10-06T00:00:00Z"));
    }

    @Test
    void aNewTransactionIsInitiatedWithSaneDefaults() {
        PaymentTransaction t = transaction();
        assertNotNull(t.getId(), "the id is assigned at construction");
        assertEquals(PaymentTransactionStatus.INITIATED, t.getStatus());
        assertEquals("INR", t.getCurrency());
        assertEquals("RAZORPAY", t.getGateway());
        assertEquals(0, t.getPollCount());
        assertNull(t.getVersion(), "null version marks a never-persisted entity");
        assertNull(t.getNextPollAt());
        assertNull(t.getPaidAt());
        assertEquals("idem-1", t.getIdempotencyKey());
        assertEquals("shop@upi", t.getVpa());
        assertEquals("books", t.getMerchantCategory());
        assertEquals("test purchase", t.getDescription());
        assertEquals(Instant.parse("2026-10-06T00:00:00Z"), t.getExpiresAt());
        assertEquals("session-1", t.getSessionId());
    }

    @Test
    void pendingApprovalRecordsTheApprovalRequest() {
        PaymentTransaction t = transaction();
        UUID approvalId = UUID.randomUUID();
        t.markPendingApproval(approvalId);
        assertEquals(PaymentTransactionStatus.PENDING_APPROVAL, t.getStatus());
        assertEquals(approvalId, t.getApprovalId());
    }

    @Test
    void awaitingPaymentStoresTheGatewayHandleAndStartsPolling() {
        PaymentTransaction t = transaction();
        Instant firstPoll = Instant.parse("2026-10-05T00:01:00Z");
        t.markAwaitingPayment("plink_123", "https://rzp.io/l/abc", firstPoll);
        assertEquals(PaymentTransactionStatus.AWAITING_PAYMENT, t.getStatus());
        assertEquals("plink_123", t.getRazorpayEntityId());
        assertEquals("https://rzp.io/l/abc", t.getShortUrl());
        assertEquals(firstPoll, t.getNextPollAt());
    }

    @Test
    void capturedClearsThePollScheduleAndRecordsPaidAt() {
        PaymentTransaction t = transaction();
        t.markAwaitingPayment("plink_123", "https://rzp.io/l/abc", Instant.now());
        Instant paidAt = Instant.parse("2026-10-05T01:00:00Z");
        t.markCaptured("pay_456", "https://receipt", paidAt);
        assertEquals(PaymentTransactionStatus.CAPTURED, t.getStatus());
        assertEquals("pay_456", t.getGatewayPaymentId());
        assertEquals("https://receipt", t.getReceiptUrl());
        assertEquals(paidAt, t.getPaidAt());
        assertNull(t.getNextPollAt(), "a terminal row is never polled again");
    }

    @Test
    void failureAndExpiryBothClearThePollSchedule() {
        PaymentTransaction failed = transaction();
        failed.markAwaitingPayment("e", "u", Instant.now());
        failed.markFailed("payer declined");
        assertEquals(PaymentTransactionStatus.FAILED, failed.getStatus());
        assertEquals("payer declined", failed.getFailureReason());
        assertNull(failed.getNextPollAt());

        PaymentTransaction expired = transaction();
        expired.markAwaitingPayment("e", "u", Instant.now());
        expired.markExpired();
        assertEquals(PaymentTransactionStatus.EXPIRED, expired.getStatus());
        assertNull(expired.getNextPollAt());
    }

    @Test
    void scheduleNextPollCountsAttempts() {
        PaymentTransaction t = transaction();
        t.scheduleNextPoll(Instant.parse("2026-10-05T00:05:00Z"));
        t.scheduleNextPoll(Instant.parse("2026-10-05T00:10:00Z"));
        assertEquals(2, t.getPollCount());
        assertEquals(Instant.parse("2026-10-05T00:10:00Z"), t.getNextPollAt());
    }

    @Test
    void theStatusEnumAgreesOnWhichStatesAreTerminal() {
        assertFalse(PaymentTransactionStatus.INITIATED.isTerminal());
        assertFalse(PaymentTransactionStatus.PENDING_APPROVAL.isTerminal());
        assertFalse(PaymentTransactionStatus.AWAITING_PAYMENT.isTerminal());
        assertTrue(PaymentTransactionStatus.CAPTURED.isTerminal());
        assertTrue(PaymentTransactionStatus.FAILED.isTerminal());
        assertTrue(PaymentTransactionStatus.EXPIRED.isTerminal());
        assertTrue(PaymentTransactionStatus.REFUNDED.isTerminal());
    }

    @Test
    void transactionTypesAreTheThreeInstrumentedMovements() {
        assertEquals(3, PaymentTransactionType.values().length);
        assertEquals(PaymentTransactionType.PAYMENT_LINK, PaymentTransactionType.valueOf("PAYMENT_LINK"));
        assertEquals(PaymentTransactionType.UPI_COLLECT, PaymentTransactionType.valueOf("UPI_COLLECT"));
        assertEquals(PaymentTransactionType.PAYOUT, PaymentTransactionType.valueOf("PAYOUT"));
    }
}
