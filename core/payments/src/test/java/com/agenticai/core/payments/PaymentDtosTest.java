package com.agenticai.core.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.payments.PaymentDtos.MandateResponse;
import com.agenticai.core.payments.PaymentDtos.TransactionResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The entity-to-DTO mapping: {@code allowed_vpas}/{@code allowed_merchant_categories} are stored as
 * JSON-array strings (or NULL = unrestricted) and must come back as real lists, the canonical hash
 * is computed on the response, and a transaction response is a flat copy.
 */
class PaymentDtosTest {

    @Test
    void readListAndWriteListRoundTrip() {
        assertNull(PaymentMandateService.writeList(null));
        assertNull(PaymentMandateService.writeList(List.of()));
        assertEquals("[\"a\",\"b\"]", PaymentMandateService.writeList(List.of("a", "b")));
        assertEquals(List.of("a", "b"), PaymentMandateService.readList("[\"a\",\"b\"]"));
        assertEquals(List.of(), PaymentMandateService.readList(null));
        assertEquals(List.of(), PaymentMandateService.readList("  "));
        assertEquals(
                List.of(),
                PaymentMandateService.readList("not-json"),
                "a corrupt column degrades to unrestricted, never throws");
    }

    private static PaymentMandate mandate() {
        PaymentMandate m = new PaymentMandate();
        m.setId(UUID.randomUUID());
        m.setAgentId(UUID.randomUUID());
        m.setGrantedByUserId("user-1");
        m.setScope(MandateScope.BOTH);
        m.setMaxPerTxnPaise(1);
        m.setMaxDailyPaise(2);
        m.setMaxMonthlyPaise(3);
        m.setAllowedVpas("[\"x@upi\",\"y@upi\"]");
        m.setApprovalPolicy(MandateApprovalPolicy.ABOVE_THRESHOLD);
        m.setApprovalThresholdPaise(5_000L);
        m.setValidFrom(Instant.parse("2026-10-01T00:00:00Z"));
        m.setValidUntil(Instant.parse("2027-10-01T00:00:00Z"));
        m.setCanonicalJson("{\"version\":\"v\"}");
        return m;
    }

    @Test
    void mandateResponseDecodesTheStoredListsAndHashesTheCanonicalJson() {
        PaymentMandate m = mandate();
        MandateResponse r = PaymentDtos.toMandateResponse(m, new MandateCanonicalizer());
        assertEquals(m.getId(), r.id());
        assertEquals(m.getAgentId(), r.agentId());
        assertEquals("user-1", r.grantedByUserId());
        assertEquals(MandateScope.BOTH, r.scope());
        assertEquals(List.of("x@upi", "y@upi"), r.allowedVpas());
        assertEquals(
                List.of(),
                r.allowedMerchantCategories(),
                "NULL column = unrestricted = empty list");
        assertEquals(MandateApprovalPolicy.ABOVE_THRESHOLD, r.approvalPolicy());
        assertEquals(5_000L, r.approvalThresholdPaise());
        assertEquals(MandateStatus.ACTIVE, r.status());
        assertEquals(64, r.canonicalHashSha256().length());
        assertNull(r.revokedAt());
        assertNull(r.revokedBy());
    }

    @Test
    void transactionResponseIsAFlatCopy() {
        PaymentTransaction t =
                new PaymentTransaction(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "s-1",
                        PaymentTransactionType.PAYMENT_LINK,
                        999,
                        "v@upi",
                        "desc",
                        "cat",
                        "idem",
                        Instant.parse("2026-10-06T00:00:00Z"));
        t.markAwaitingPayment("plink_1", "https://rzp.io/x", Instant.now());
        TransactionResponse r = PaymentDtos.toTransactionResponse(t);
        assertEquals(t.getId(), r.id());
        assertEquals(t.getMandateId(), r.mandateId());
        assertEquals(t.getAgentId(), r.agentId());
        assertEquals("s-1", r.sessionId());
        assertEquals(PaymentTransactionType.PAYMENT_LINK, r.type());
        assertEquals(999, r.amountPaise());
        assertEquals("INR", r.currency());
        assertEquals("v@upi", r.vpa());
        assertEquals("cat", r.merchantCategory());
        assertEquals(PaymentTransactionStatus.AWAITING_PAYMENT, r.status());
        assertEquals("plink_1", t.getRazorpayEntityId());
        assertEquals("https://rzp.io/x", r.shortUrl());
        assertNull(r.gatewayPaymentId());
        assertNull(r.failureReason());
        assertNull(r.paidAt());
        assertTrue(r.createdAt() != null && r.updatedAt() != null);
    }

    @Test
    void theRequestRecordsExposeTheirComponents() {
        UUID agent = UUID.randomUUID();
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant until = Instant.parse("2027-10-01T00:00:00Z");
        PaymentDtos.CreateMandateRequest req =
                new PaymentDtos.CreateMandateRequest(
                        agent,
                        MandateScope.COLLECT,
                        1,
                        2,
                        3,
                        List.of("v@upi"),
                        null,
                        MandateApprovalPolicy.NEVER,
                        null,
                        from,
                        until);
        assertEquals(agent, req.agentId());
        assertEquals(MandateScope.COLLECT, req.scope());
        assertEquals(List.of("v@upi"), req.allowedVpas());
        assertNull(req.allowedMerchantCategories());
        assertEquals(MandateApprovalPolicy.NEVER, req.approvalPolicy());

        assertEquals("h", new PaymentDtos.MandatePreviewResponse("c", "h").canonicalHashSha256());
        assertEquals("why", new PaymentDtos.RevokeMandateRequest("why").reason());
        assertEquals(
                MandateStatus.REVOKED,
                new PaymentDtos.RevokeResponse(agent, MandateStatus.REVOKED).status());
        assertEquals(
                7L,
                new PaymentDtos.MandateSpendResponse(agent, 1, 2, 3, 4, 7L).monthlySpendPaise());
    }
}
