package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The bounded stderr tail the MCP Connection Doctor reads after a failed spawn/handshake. */
class StderrTailBufferTest {

    @Test
    void aFreshBufferIsEmpty() {
        StderrTailBuffer buffer = new StderrTailBuffer();
        assertTrue(buffer.isEmpty());
        assertEquals(List.of(), buffer.snapshot());
        assertEquals("", buffer.joined());
    }

    @Test
    void nullAndBlankLinesAreDropped() {
        StderrTailBuffer buffer = new StderrTailBuffer();
        buffer.add(null);
        buffer.add("");
        buffer.add("   ");
        buffer.add("\n");
        assertTrue(buffer.isEmpty());
    }

    @Test
    void keepsOnlyTheLastMaxLines() {
        StderrTailBuffer buffer = new StderrTailBuffer();
        for (int i = 1; i <= StderrTailBuffer.MAX_LINES + 5; i++) {
            buffer.add("line " + i);
        }
        List<String> lines = buffer.snapshot();
        assertEquals(StderrTailBuffer.MAX_LINES, lines.size());
        assertEquals("line 6", lines.get(0), "the oldest lines are evicted first");
        assertEquals("line " + (StderrTailBuffer.MAX_LINES + 5), lines.get(lines.size() - 1));
    }

    @Test
    void longLinesAreClippedWithAnEllipsis() {
        StderrTailBuffer buffer = new StderrTailBuffer();
        buffer.add("x".repeat(StderrTailBuffer.MAX_LINE_CHARS + 10));
        String kept = buffer.snapshot().get(0);
        assertEquals(StderrTailBuffer.MAX_LINE_CHARS + 3, kept.length());
        assertTrue(kept.endsWith("..."));
        assertEquals("x".repeat(StderrTailBuffer.MAX_LINE_CHARS), kept.substring(0, kept.length() - 3));
    }

    @Test
    void joinedJoinsTheKeptLinesInOrder() {
        StderrTailBuffer buffer = new StderrTailBuffer();
        buffer.add("first");
        buffer.add("second");
        assertFalse(buffer.isEmpty());
        assertEquals("first\nsecond", buffer.joined());
    }
}
