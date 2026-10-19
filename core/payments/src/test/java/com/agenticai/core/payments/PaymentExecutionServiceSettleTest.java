package com.agenticai.core.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.payments.adapter.UpiAgentProtocolAdapter;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * JAVA-002: the settle transitions are check-then-act, so they must read the row under a lock
 * ({@code findByIdForUpdate}) and perform side effects only when THIS caller moved the row out of a
 * non-terminal state. The webhook and the reconciliation poller settle the same transaction
 * concurrently; the loser blocks on the row lock and then sees the winner's terminal status. These
 * tests model that loser's view: the locked read returns the already-committed terminal row.
 */
class PaymentExecutionServiceSettleTest {

    private PaymentTransactionRepository transactions;
    private MandateEnforcer enforcer;
    private WorkflowEventPublisher events;
    private AuditLogService audit;
    private PaymentExecutionService service;

    @BeforeEach
    void setUp() {
        transactions = mock(PaymentTransactionRepository.class);
        enforcer = mock(MandateEnforcer.class);
        events = mock(WorkflowEventPublisher.class);
        audit = mock(AuditLogService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<UpiAgentProtocolAdapter> adapters = mock(ObjectProvider.class);
        service =
                new PaymentExecutionService(
                        transactions,
                        mock(PaymentMandateRepository.class),
                        enforcer,
                        adapters,
                        events,
                        audit,
                        mock(org.springframework.transaction.PlatformTransactionManager.class));
    }

    private static PaymentTransaction awaiting() {
        PaymentTransaction txn =
                new PaymentTransaction(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "s",
                        PaymentTransactionType.PAYMENT_LINK,
                        10_000,
                        null,
                        "d",
                        null,
                        "k-" + UUID.randomUUID(),
                        Instant.now().plusSeconds(600));
        txn.markAwaitingPayment("plink_x", "https://rzp.io/l/x", Instant.now());
        return txn;
    }

    @Test
    void settleCapturedReadsTheRowUnderALock() {
        PaymentTransaction txn = awaiting();
        when(transactions.findByIdForUpdate(txn.getId())).thenReturn(Optional.of(txn));

        assertThat(service.settleCaptured(txn.getId(), "pay_1", 10_000L)).isTrue();

        verify(transactions).findByIdForUpdate(txn.getId());
        verify(transactions, never()).findById(any());
        assertThat(txn.getStatus()).isEqualTo(PaymentTransactionStatus.CAPTURED);
        verify(enforcer, never()).releaseCaps(any(), any(), any());
    }

    /** The poller loses to the webhook: it must not flip CAPTURED to EXPIRED nor release caps. */
    @Test
    void expiryAfterCaptureIsANoOp() {
        PaymentTransaction txn = awaiting();
        txn.markCaptured("pay_1", null, Instant.now());
        when(transactions.findByIdForUpdate(txn.getId())).thenReturn(Optional.of(txn));

        service.settleExpired(txn.getId());
        service.settleFailed(txn.getId(), "gateway reports link EXPIRED");

        assertThat(txn.getStatus()).isEqualTo(PaymentTransactionStatus.CAPTURED);
        verify(transactions, never()).save(any());
        verify(enforcer, never()).releaseCaps(any(), any(), any());
        verify(events, never()).publish(anyString(), anyString(), any());
    }

    /** The webhook loses to the poller's expiry: it must not resurrect the row as CAPTURED. */
    @Test
    void captureAfterExpiryIsANoOp() {
        PaymentTransaction txn = awaiting();
        txn.markExpired();
        when(transactions.findByIdForUpdate(txn.getId())).thenReturn(Optional.of(txn));

        assertThat(service.settleCaptured(txn.getId(), "pay_1", 10_000L)).isFalse();

        assertThat(txn.getStatus()).isEqualTo(PaymentTransactionStatus.EXPIRED);
        verify(transactions, never()).save(any());
    }

    /** Sequential double settle (webhook retry + poller): side effects exactly once. */
    @Test
    void secondExpiryReleasesCapsOnlyOnce() {
        PaymentTransaction txn = awaiting();
        when(transactions.findByIdForUpdate(txn.getId())).thenReturn(Optional.of(txn));

        service.settleExpired(txn.getId());
        service.settleExpired(txn.getId());

        verify(enforcer, times(1)).releaseCaps(any(), any(), any());
        verify(transactions, times(1)).save(txn);
    }

    @Test
    void recordInitiatedNeverResurrectsATerminalRow() {
        PaymentTransaction txn =
                new PaymentTransaction(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "s",
                        PaymentTransactionType.PAYMENT_LINK,
                        10_000,
                        null,
                        "d",
                        null,
                        "k-" + UUID.randomUUID(),
                        Instant.now().plusSeconds(600));
        txn.markFailed("approval rejected");
        when(transactions.findByIdForUpdate(txn.getId())).thenReturn(Optional.of(txn));

        service.recordInitiated(
                txn.getId(),
                new UpiAgentProtocolAdapter.AdapterInitiation(
                        "plink_y", "https://rzp.io/l/y", "created"));

        assertThat(txn.getStatus()).isEqualTo(PaymentTransactionStatus.FAILED);
        verify(transactions, never()).save(any());
    }
}
