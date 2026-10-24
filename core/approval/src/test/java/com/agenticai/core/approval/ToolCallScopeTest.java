package com.agenticai.core.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolCallScopeTest {

    @Test
    void isEmptyOutsideAToolCallAndRestoredWhenClosed() {
        assertTrue(ToolCallScope.current().isEmpty());
        UUID agent = UUID.randomUUID();
        try (ToolCallScope.Scope ignored = ToolCallScope.open("a", agent, "s1", null, null)) {
            assertEquals("a", ToolCallScope.current().orElseThrow().toolName());
        }
        assertTrue(ToolCallScope.current().isEmpty());
    }

    @Test
    void nestedScopesRestoreTheOuterOneAndAThrowStillCloses() {
        try (ToolCallScope.Scope outer = ToolCallScope.open("outer", UUID.randomUUID(), "s", null, null)) {
            try (ToolCallScope.Scope inner = ToolCallScope.open("inner", UUID.randomUUID(), "s", null, null)) {
                assertEquals("inner", ToolCallScope.current().orElseThrow().toolName());
            }
            assertEquals("outer", ToolCallScope.current().orElseThrow().toolName());
            try (ToolCallScope.Scope thrower = ToolCallScope.open("t", UUID.randomUUID(), "s", null, null)) {
                throw new IllegalStateException("tool failed");
            } catch (IllegalStateException expected) {
                assertEquals("outer", ToolCallScope.current().orElseThrow().toolName());
            }
        }
        assertTrue(ToolCallScope.current().isEmpty());
    }

    @Test
    void doesNotLeakToAnotherThread() throws Exception {
        try (ToolCallScope.Scope ignored = ToolCallScope.open("a", UUID.randomUUID(), "s", null, null)) {
            boolean[] other = new boolean[1];
            Thread t = new Thread(() -> other[0] = ToolCallScope.current().isPresent());
            t.start();
            t.join();
            assertEquals(false, other[0]);
        }
    }
}
