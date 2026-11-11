package com.agenticai.core.devices.sentinel;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.account.AccountDeletionRequested;
import org.junit.jupiter.api.Test;

/**
 * Right-to-erasure: an account deletion deletes that user's sentinel events and nothing else -
 * {@code mobile_device} rows are deliberately untouched (they belong to the workspace, not the
 * person).
 */
class MobileSentinelAccountDeletionListenerTest {

    @Test
    void deletesExactlyTheDeletedUsersEvents() {
        MobileSentinelEventRepository events = mock(MobileSentinelEventRepository.class);
        when(events.deleteAllOfUser("alice")).thenReturn(3);
        MobileSentinelAccountDeletionListener listener =
                new MobileSentinelAccountDeletionListener(events);

        listener.onAccountDeletion(new AccountDeletionRequested("tenant_acme", "alice"));

        verify(events).deleteAllOfUser("alice");
    }
}
