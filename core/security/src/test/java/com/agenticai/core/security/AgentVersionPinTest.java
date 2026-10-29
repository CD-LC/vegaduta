package com.agenticai.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentVersionPin}'s ThreadLocal contract: set/current/clear, {@code set(null)} as a
 * remove, and no cross-thread leakage (writers must stamp {@code null} rather than a wrong
 * version id).
 */
class AgentVersionPinTest {

    @AfterEach
    void clear() {
        AgentVersionPin.clear();
    }

    @Test
    void currentIsNullUntilAPinIsSet() {
        assertThat(AgentVersionPin.current()).isNull();
    }

    @Test
    void setThenCurrentReturnsThePinnedVersion() {
        UUID pin = UUID.randomUUID();

        AgentVersionPin.set(pin);

        assertThat(AgentVersionPin.current()).isEqualTo(pin);
    }

    @Test
    void setNullRemovesAnExistingPin() {
        AgentVersionPin.set(UUID.randomUUID());

        AgentVersionPin.set(null);

        assertThat(AgentVersionPin.current()).isNull();
    }

    @Test
    void clearRemovesThePin() {
        AgentVersionPin.set(UUID.randomUUID());

        AgentVersionPin.clear();

        assertThat(AgentVersionPin.current()).isNull();
    }

    @Test
    void thePinDoesNotLeakOntoOtherThreads() throws Exception {
        AgentVersionPin.set(UUID.randomUUID());

        AtomicReference<Object> seen = new AtomicReference<>("unset");
        Thread other = new Thread(() -> seen.set(AgentVersionPin.current()));
        other.start();
        other.join();

        assertThat(seen.get()).isNull();
        assertThat(AgentVersionPin.current()).isNotNull();
    }
}
