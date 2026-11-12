package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The conversation taint flag is the session taint marker: one marker, one clear. */
class SessionBackedConversationTaintStoreTest {

    @Test
    void aConversationIsTaintedExactlyWhenItsSessionMarkerExists() {
        SessionTaintStore sessions = mock(SessionTaintStore.class);
        when(sessions.load("c1"))
                .thenReturn(
                        Optional.of(
                                new SessionTaintStore.Marker(
                                        List.of("web_fetch"), Instant.EPOCH, Instant.EPOCH)));
        when(sessions.load("c2")).thenReturn(Optional.empty());
        SessionBackedConversationTaintStore store =
                new SessionBackedConversationTaintStore(sessions);

        assertTrue(store.isTainted("t1", "c1"));
        assertFalse(store.isTainted("t1", "c2"));
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
