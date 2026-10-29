package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@code app.llm.request-snapshot.*}: the display-only caps on one stored request copy for the
 * LLM request inspector. Defaults ship in {@code /chat/request-snapshot.properties}; every
 * numeric setter clamps at zero and a null truncation marker becomes empty.
 */
class LlmRequestSnapshotPropertiesTest {

    @Test
    void theShippedDefaultsAreTheOnesInThePropertiesFile() {
        LlmRequestSnapshotProperties p = new LlmRequestSnapshotProperties();

        assertEquals(40_000, p.getMaxMessageChars());
        assertEquals(300_000, p.getMaxTotalChars());
        assertEquals(4_000, p.getMaxSchemaChars());
        assertEquals(200, p.getMaxTools());
        assertEquals(600, p.getMaxCallChars());
        assertEquals("\n… [truncated for display]", p.getTruncationMarker());
    }

    @Test
    void everyCapClampsAtZero() {
        LlmRequestSnapshotProperties p = new LlmRequestSnapshotProperties();
        p.setMaxMessageChars(-1);
        p.setMaxTotalChars(-1);
        p.setMaxSchemaChars(-1);
        p.setMaxTools(-1);
        p.setMaxCallChars(-1);

        assertEquals(0, p.getMaxMessageChars());
        assertEquals(0, p.getMaxTotalChars());
        assertEquals(0, p.getMaxSchemaChars());
        assertEquals(0, p.getMaxTools());
        assertEquals(0, p.getMaxCallChars());
    }

    @Test
    void overridesRoundTrip() {
        LlmRequestSnapshotProperties p = new LlmRequestSnapshotProperties();
        p.setMaxMessageChars(100);
        p.setMaxTotalChars(1000);
        p.setMaxSchemaChars(50);
        p.setMaxTools(10);
        p.setMaxCallChars(20);
        p.setTruncationMarker(" [cut]");

        assertEquals(100, p.getMaxMessageChars());
        assertEquals(1000, p.getMaxTotalChars());
        assertEquals(50, p.getMaxSchemaChars());
        assertEquals(10, p.getMaxTools());
        assertEquals(20, p.getMaxCallChars());
        assertEquals(" [cut]", p.getTruncationMarker());
    }

    @Test
    void aNullTruncationMarkerBecomesEmpty() {
        LlmRequestSnapshotProperties p = new LlmRequestSnapshotProperties();
        p.setTruncationMarker(null);

        assertEquals("", p.getTruncationMarker());
    }
}
