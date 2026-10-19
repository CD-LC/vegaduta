package com.agenticai.core.agents.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SEC-004: the per-turn memory binding never falls back to a raw client-chosen id. */
class SessionMemoryBindingTest {

    @Test
    void unboundTurnFailsClosed() {
        assertThatThrownBy(SessionMemoryService::boundMemorySessionId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rawClientIdCannotBeBound() {
        String previous = SessionMemoryService.bindMemorySessionId("victim-session-id");
        try {
            assertThatThrownBy(SessionMemoryService::boundMemorySessionId).isInstanceOf(IllegalStateException.class);
        } finally {
            SessionMemoryService.restoreMemorySessionId(previous);
        }
    }

    @Test
    void scopedIdIsBoundAndRestored() {
        String scoped = SessionMemoryService.scopedSessionId("tenant_a", UUID.randomUUID(), "user-1", "s1");
        String previous = SessionMemoryService.bindMemorySessionId(scoped);
        try {
            assertThat(SessionMemoryService.boundMemorySessionId()).isEqualTo(scoped);
        } finally {
            SessionMemoryService.restoreMemorySessionId(previous);
        }
        assertThatThrownBy(SessionMemoryService::boundMemorySessionId).isInstanceOf(IllegalStateException.class);
    }
}
