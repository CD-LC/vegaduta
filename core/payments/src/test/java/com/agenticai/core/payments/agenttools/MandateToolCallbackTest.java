package com.agenticai.core.payments.agenttools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.payments.MandateViolationException;
import com.agenticai.core.payments.PaymentToolService;
import com.agenticai.core.payments.PaymentTransactionStatus;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The mandate-aware tool callbacks' call contract. The point worth testing is that the
 * mandate/approval enforcement lives INSIDE {@link PaymentToolService} on every call - the
 * callbacks are thin arg-parsing shells with no channel or autonomy concept at all, so a
 * voice-relayed or FULLY_AUTONOMOUS invocation hits the identical gate (there is no voice-specific
 * bypass to regress).
 */
class MandateToolCallbackTest {

    private PaymentToolService service;
    private MandateCreatePaymentLinkToolCallback createLink;
    private MandateRequestUpiCollectToolCallback collect;
    private MandateCheckPaymentStatusToolCallback status;

    private static final UUID AGENT = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(PaymentToolService.class);
        createLink = new MandateCreatePaymentLinkToolCallback(service, AGENT, "voice-call-sess");
        collect = new MandateRequestUpiCollectToolCallback(service, AGENT, "voice-call-sess");
        status = new MandateCheckPaymentStatusToolCallback(service, AGENT);
    }

    @Test
    void approvalPendingOutcomeIsRelayedNotSwallowed() {
        UUID txnId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        when(service.createPaymentLink(any(), any(), anyLong(), anyString(), any(), any(), any()))
                .thenReturn(
                        new PaymentToolService.PaymentOutcome(
                                txnId,
                                PaymentTransactionStatus.PENDING_APPROVAL,
                                null,
                                approvalId,
                                false,
                                "waiting for approval"));

        String result = createLink.call("{\"amount_inr\": 250, \"description\": \"consultation\"}");

        assertTrue(result.contains("PENDING_APPROVAL"));
        assertTrue(result.contains(approvalId.toString()));
        assertTrue(result.contains(txnId.toString()));
        verify(service)
                .createPaymentLink(
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq(25_000L),
                        anyString(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void mandateRefusalComesBackAsAModelReadableError() {
        when(service.createPaymentLink(any(), any(), anyLong(), anyString(), any(), any(), any()))
                .thenThrow(new MandateViolationException("CAP_DAILY", "over daily cap"));

        String result = createLink.call("{\"amount_inr\": 9000, \"description\": \"x\"}");

        assertTrue(result.contains("CAP_DAILY"));
        assertTrue(result.contains("error"));
    }

    @Test
    void upiCollectRequiresAVpa() {
        String result = collect.call("{\"amount_inr\": 10, \"description\": \"x\"}");
        assertTrue(result.contains("vpa is required"));
    }

    @Test
    void upiCollectPassesTheVpaThrough() {
        UUID txnId = UUID.randomUUID();
        when(service.requestUpiCollect(any(), any(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(
                        new PaymentToolService.PaymentOutcome(
                                txnId,
                                PaymentTransactionStatus.AWAITING_PAYMENT,
                                "https://rzp.io/l/x",
                                null,
                                false,
                                "ok"));

        String result =
                collect.call(
                        "{\"amount_inr\": 10, \"vpa\": \"shop@okhdfcbank\", \"description\": \"restock\"}");

        assertTrue(result.contains("AWAITING_PAYMENT"));
        verify(service)
                .requestUpiCollect(
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq(1_000L),
                        org.mockito.ArgumentMatchers.eq("shop@okhdfcbank"),
                        anyString(),
                        any());
    }

    @Test
    void statusCheckRejectsMalformedIds() {
        String result = status.call("{\"transaction_id\": \"not-a-uuid\"}");
        assertTrue(result.contains("not a valid id"));
    }

    @Test
    void moreThanTwoDecimalAmountsAreRejectedNotRounded() {
        String result = createLink.call("{\"amount_inr\": 10.005, \"description\": \"x\"}");
        assertTrue(result.contains("two decimal places"));
    }
}
