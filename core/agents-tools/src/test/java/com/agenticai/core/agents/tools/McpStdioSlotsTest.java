package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The per-tenant fork-bomb bound on live STDIO subprocesses and in-flight connection tests. */
class McpStdioSlotsTest {

    private final McpStdioSlots slots = new McpStdioSlots(2);

    @Test
    void theCapCountsLiveStdioSubprocessesPerTenant() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        assertFalse(slots.stdioCapReached("t1", a));
        slots.markLive("t1", a);
        slots.markLive("t1", b);

        assertTrue(slots.stdioCapReached("t1", c), "a third live subprocess is refused");
        assertFalse(slots.stdioCapReached("t1", a), "an already-live id can re-check");
        assertFalse(slots.stdioCapReached("t2", c), "another tenant has its own cap");

        slots.release("t1", a);
        assertFalse(slots.stdioCapReached("t1", c));
    }

    @Test
    void releasingAnUnknownIdIsHarmless() {
        slots.release("t1", UUID.randomUUID());
        slots.release("never-seen", UUID.randomUUID());
    }

    @Test
    void testSlotsAreBoundedAndReleased() {
        assertTrue(slots.tryAcquireTestSlot("t1"));
        assertTrue(slots.tryAcquireTestSlot("t1"));
        assertFalse(slots.tryAcquireTestSlot("t1"), "the third in-flight test is refused");
        assertTrue(slots.tryAcquireTestSlot("t2"), "per tenant");

        slots.releaseTestSlot("t1");
        assertTrue(slots.tryAcquireTestSlot("t1"));
        slots.releaseTestSlot("t1");
        slots.releaseTestSlot("t1");
        slots.releaseTestSlot("t1"); // over-release is tolerated
        assertTrue(slots.tryAcquireTestSlot("t1"));
    }

    @Test
    void exposesTheConfiguredCap() {
        assertEquals(2, slots.maxPerTenant());
    }
}
