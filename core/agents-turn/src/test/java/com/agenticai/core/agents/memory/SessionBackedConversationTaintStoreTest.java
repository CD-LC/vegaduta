package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The conversation taint flag is the session taint marker: one marker, one clear. */
class SessionBackedConversationTaintStoreTest {

    @Test
    void aConversationIsTaintedExactlyWhenItsSessionMarkerExists() {
        SessionTaintStore sessions = mock(SessionTaintStore.class);
        when(sessions.hasMarkerStrict("c1")).thenReturn(true);
        when(sessions.hasMarkerStrict("c2")).thenReturn(false);
        SessionBackedConversationTaintStore store =
                new SessionBackedConversationTaintStore(sessions);

        assertTrue(store.isTainted("t1", "c1"));
        assertFalse(store.isTainted("t1", "c2"));
    }

    @Test
    void anUnreadableMarkerIsNotReportedAsClean() {
        SessionTaintStore sessions = mock(SessionTaintStore.class);
        when(sessions.hasMarkerStrict("c1")).thenThrow(new IllegalStateException("redis down"));
        SessionBackedConversationTaintStore store =
                new SessionBackedConversationTaintStore(sessions);

        assertThrows(IllegalStateException.class, () -> store.isTainted("t1", "c1"));
    }

    @Test
    void markingWritesTheSessionMarkerAndAddsNoSourceOfItsOwn() {
        SessionTaintStore sessions = mock(SessionTaintStore.class);
        SessionBackedConversationTaintStore store =
                new SessionBackedConversationTaintStore(sessions);

        store.markTainted("t1", "c1");

        verify(sessions).record("c1", List.of());
    }
}
