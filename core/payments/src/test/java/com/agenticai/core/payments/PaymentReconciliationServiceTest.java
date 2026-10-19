package com.agenticai.core.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.payments.adapter.UpiAgentProtocolAdapter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link PaymentReconciliationService} - the 1m/5m/30m poll ladder and
 * TTL expiry. The adapter is mocked; no live gateway calls.
 */
class PaymentReconciliationServiceTest {

    private PaymentTransactionRepository transactionRepository;
    private PaymentExecutionService executionService;
    private UpiAgentProtocolAdapter adapter;
    private PaymentReconciliationService service;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(PaymentTransactionRepository.class);
        executionService = mock(PaymentExecutionService.class);
        adapter = mock(UpiAgentProtocolAdapter.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<UpiAgentProtocolAdapter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(adapter);
        service = new PaymentReconciliationService(transactionRepository, executionService, provider);
    }

    private PaymentTransaction awaiting(int pollCount, Instant expiresAt) {
        PaymentTransaction txn = new PaymentTransaction(
            UUID.randomUUID(), UUID.randomUUID(), "s", PaymentTransactionType.PAYMENT_LINK,
            10_000, null, "d", null, "k", expiresAt);
        txn.markAwaitingPayment("plink_x", "https://rzp.io/l/x", Instant.now());
        for (int i = 0; i < pollCount; i++) {
            txn.scheduleNextPoll(Instant.now());
        }
        return txn;
    }

    @Test
    void paidStatusCapturesTheTransaction() {
        PaymentTransaction txn = awaiting(0, Instant.now().plusSeconds(3600));
        when(transactionRepository.findByStatusAndNextPollAtLessThanEqual(any(), any()))
            .thenReturn(List.of(txn));
        when(adapter.fetchCollectionStatus("plink_x")).thenReturn(
            new UpiAgentProtocolAdapter.AdapterPaymentStatus(
                UpiAgentProtocolAdapter.AdapterStatus.PAID, "pay_123", 10_000L));

        service.pollDue();

        verify(executionService).settleCaptured(txn.getId(), "pay_123", 10_000L);
    }

    @Test
    void expiredTtlExpiresWithoutAGatewayCall() {
        PaymentTransaction txn = awaiting(2, Instant.now().minusSeconds(10));
        when(transactionRepository.findByStatusAndNextPollAtLessThanEqual(any(), any()))
            .thenReturn(List.of(txn));

        service.pollDue();

        verify(executionService).settleExpired(txn.getId());
        verify(adapter, never()).fetchCollectionStatus(anyString());
    }

    @Test
    void gatewayExpiredLinkFailsAndReleases() {
        PaymentTransaction txn = awaiting(1, Instant.now().plusSeconds(3600));
        when(transactionRepository.findByStatusAndNextPollAtLessThanEqual(any(), any()))
            .thenReturn(List.of(txn));
        when(adapter.fetchCollectionStatus("plink_x")).thenReturn(
            new UpiAgentProtocolAdapter.AdapterPaymentStatus(
                UpiAgentProtocolAdapter.AdapterStatus.EXPIRED, null, null));

        service.pollDue();

        verify(executionService).settleFailed(org.mockito.ArgumentMatchers.eq(txn.getId()), anyString());
    }

    @Test
    void pendingReschedulesOnTheLadder() {
        PaymentTransaction txn = awaiting(0, Instant.now().plusSeconds(3600));
        when(transactionRepository.findByStatusAndNextPollAtLessThanEqual(any(), any()))
            .thenReturn(List.of(txn));
        when(adapter.fetchCollectionStatus("plink_x")).thenReturn(
            new UpiAgentProtocolAdapter.AdapterPaymentStatus(
                UpiAgentProtocolAdapter.AdapterStatus.PENDING, null, null));

        service.pollDue();

        // poll 0 done -> next poll +5m (the ladder's second rung).
        assertEquals(1, txn.getPollCount());
        verify(executionService, never()).settleCaptured(any(), any(), any());
    }

    @Test
    void thirdAndLaterPollsRescheduleAtThirtyMinutes() {
        PaymentTransaction txn = awaiting(2, Instant.now().plusSeconds(3600));
        when(transactionRepository.findByStatusAndNextPollAtLessThanEqual(any(), any()))
            .thenReturn(List.of(txn));
        when(adapter.fetchCollectionStatus("plink_x")).thenReturn(
            new UpiAgentProtocolAdapter.AdapterPaymentStatus(
                UpiAgentProtocolAdapter.AdapterStatus.UNKNOWN, null, null));

        service.pollDue();

        assertEquals(3, txn.getPollCount());
    }
}
