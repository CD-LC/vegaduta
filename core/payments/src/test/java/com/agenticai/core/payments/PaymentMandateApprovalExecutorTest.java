package com.agenticai.core.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalRequest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link PaymentMandateApprovalExecutor}: approving an {@code
 * upi.mandate.execute} request runs the parked transaction through
 * {@link PaymentExecutionService#execute}; rejecting settles it (cap
 * released) without ever executing.
 */
class PaymentMandateApprovalExecutorTest {

    private PaymentExecutionService executionService;
    private PaymentMandateApprovalExecutor executor;

    @BeforeEach
    void setUp() {
        executionService = mock(PaymentExecutionService.class);
        executor = new PaymentMandateApprovalExecutor(executionService);
    }

    private ApprovalRequest request(UUID transactionId) {
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getId()).thenReturn(UUID.randomUUID());
        when(request.getPayloadJson())
            .thenReturn("{\"transactionId\":\"" + transactionId + "\",\"amountPaise\":1000}");
        return request;
    }

    @Test
    void approveExecutesTheTransaction() {
        UUID txnId = UUID.randomUUID();
        executor.execute(request(txnId));
        verify(executionService).execute(txnId);
    }

    @Test
    void rejectSettlesWithoutExecuting() {
        UUID txnId = UUID.randomUUID();
        executor.onRejected(request(txnId), "admin-1", "too big");
        verify(executionService).settleRejected(txnId, "admin-1", "too big");
        verify(executionService, never()).execute(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void malformedPayloadIsALoudFailureNotASilentNoop() {
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getId()).thenReturn(UUID.randomUUID());
        when(request.getPayloadJson()).thenReturn("{}");
        assertThrows(IllegalStateException.class, () -> executor.execute(request));
        verify(executionService, never()).execute(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void actionTypeIsTheRegisteredGateName() {
        assertEquals("upi.mandate.execute", executor.actionType());
    }
}
