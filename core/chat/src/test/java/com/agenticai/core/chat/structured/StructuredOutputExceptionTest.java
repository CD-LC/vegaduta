package com.agenticai.core.chat.structured;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Raised when a model reply cannot be turned into the JSON the caller asked for. {@code rawText}
 * keeps the reply exactly as it was handed to the parser (it may be null) while the message stays
 * paste-able into a repair prompt.
 */
class StructuredOutputExceptionTest {

    @Test
    void theTwoArgumentConstructorKeepsMessageAndRawText() {
        StructuredOutputException e =
                new StructuredOutputException("no JSON found", "not json at all");

        assertEquals("no JSON found", e.getMessage());
        assertEquals("not json at all", e.rawText());
        assertNull(e.getCause());
    }

    @Test
    void theThreeArgumentConstructorAlsoKeepsTheCause() {
        RuntimeException cause = new RuntimeException("bind failed");

        StructuredOutputException e = new StructuredOutputException("binding failed", "{}", cause);

        assertEquals("binding failed", e.getMessage());
        assertEquals("{}", e.rawText());
        assertSame(cause, e.getCause());
    }

    @Test
    void aNullRawTextStaysNull() {
        assertNull(new StructuredOutputException("empty reply", null).rawText());
    }
}
