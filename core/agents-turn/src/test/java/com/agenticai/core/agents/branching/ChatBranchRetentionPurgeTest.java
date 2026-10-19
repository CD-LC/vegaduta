package com.agenticai.core.agents.branching;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import com.agenticai.core.tenancy.account.AccountDeletionRequested;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Retention and erasure of the durable message text kept by the conversation tree. */
class ChatBranchRetentionPurgeTest {

    private ChatBranchStore store;
    private TenantSchemaIterator schemaIterator;
    private PgAdvisoryJobLock jobLock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = mock(ChatBranchStore.class);
        schemaIterator = mock(TenantSchemaIterator.class);
        doAnswer(inv -> {
            Consumer<String> action = inv.getArgument(0, Consumer.class);
            action.accept("tenant_a");
            action.accept("tenant_b");
            return null;
        }).when(schemaIterator).forEachActiveTenantSchema(any());
        jobLock = mock(PgAdvisoryJobLock.class);
        when(jobLock.tryRunExclusive(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return true;
        });
    }

    @Test
    void theSweepIsInertWhileTheFeatureIsOff() {
        new ChatBranchRetentionPurge(store, schemaIterator, jobLock, false, 30).purge();

        verifyNoInteractions(store);
        verifyNoInteractions(schemaIterator);
        verifyNoInteractions(jobLock);
    }

    @Test
    void whenOnItDeletesIdleSessionsInEveryTenantSchema() {
        when(store.deleteSessionsIdleBefore(any())).thenReturn(2);
        Instant before = Instant.now();

        new ChatBranchRetentionPurge(store, schemaIterator, jobLock, true, 30).purge();

        org.mockito.ArgumentCaptor<Instant> cutoff = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(store, times(2)).deleteSessionsIdleBefore(cutoff.capture());
        // Cutoff is about 30 days ago.
        Instant expected = before.minus(java.time.Duration.ofDays(30));
        for (Instant c : cutoff.getAllValues()) {
            org.junit.jupiter.api.Assertions.assertTrue(
                !c.isBefore(expected.minusSeconds(5)) && !c.isAfter(expected.plusSeconds(60)), "cutoff " + c);
        }
    }

    @Test
    void retentionIsFlooredAtOneDayAndOneFailingSchemaDoesNotStopTheOthers() {
        when(store.deleteSessionsIdleBefore(any())).thenThrow(new IllegalStateException("db")).thenReturn(1);
        Instant now = Instant.now();

        new ChatBranchRetentionPurge(store, schemaIterator, jobLock, true, 0).purgeOlderThan(now);

        verify(store, times(2)).deleteSessionsIdleBefore(now);
    }

    @Test
    void accountDeletionRemovesTheMembersTrees() {
        when(store.deleteSessionsOwnedBy("user-1")).thenReturn(3);

        new ChatBranchAccountDeletionListener(store).onAccountDeletion(new AccountDeletionRequested("acme", "user-1"));

        verify(store).deleteSessionsOwnedBy("user-1");
        verify(store, never()).deleteSessionsIdleBefore(any());
    }
}
