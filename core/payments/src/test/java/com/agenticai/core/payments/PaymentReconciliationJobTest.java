package com.agenticai.core.payments;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import org.junit.jupiter.api.Test;

/** JAVA-002: the reconciliation sweep runs on one replica per tick (advisory job lock). */
class PaymentReconciliationJobTest {

    private final TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
    private final PaymentReconciliationService service = mock(PaymentReconciliationService.class);
    private final PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);
    private final PaymentReconciliationJob job = new PaymentReconciliationJob(iterator, service, lock);

    @Test
    void sweepRunsInsideTheJobLock() {
        when(lock.tryRunExclusive(eq("payment-reconciliation"), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return true;
        });

        job.sweep();

        verify(iterator).forEachActiveTenantSchema(any());
    }

    @Test
    void sweepIsSkippedWhenAnotherReplicaHoldsTheLock() {
        when(lock.tryRunExclusive(eq("payment-reconciliation"), any())).thenReturn(false);

        job.sweep();

        verify(lock).tryRunExclusive(eq("payment-reconciliation"), any(Runnable.class));
        // A replica that lost the lock must not poll.
        verify(iterator, never()).forEachActiveTenantSchema(any());
    }

    @Test
    void sweepStillRunsWhenNoLockIsWired() {
        PaymentReconciliationJob unlocked = new PaymentReconciliationJob(iterator, service, null);

        unlocked.sweep();

        verify(iterator).forEachActiveTenantSchema(any());
    }
}
