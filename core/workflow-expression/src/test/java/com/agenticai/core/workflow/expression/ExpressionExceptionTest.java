package com.agenticai.core.workflow.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link ExpressionException}: positions, message clipping and the shift used by templates. */
class ExpressionExceptionTest {

    @Test
    void positionsAreExposed() {
        ExpressionException e = new ExpressionException("bad", 3, 9);
        assertEquals("bad", e.getMessage());
        assertEquals(3, e.start());
        assertEquals(9, e.end());
    }

    @Test
    void ofMarksThePositionAsUnknown() {
        ExpressionException e = ExpressionException.of("x");
        assertEquals(-1, e.start());
        assertEquals(-1, e.end());
    }

    @Test
    void overlongMessagesAreClippedWithAnEllipsis() {
        String longMessage = "x".repeat(ExpressionException.MAX_MESSAGE_CHARS + 50);
        ExpressionException e = new ExpressionException(longMessage, 0, 1);
        assertEquals(ExpressionException.MAX_MESSAGE_CHARS + 3, e.getMessage().length());
        assertTrue(e.getMessage().endsWith("..."));
    }

    @Test
    void nullMessagesPassThrough() {
        assertNull(new ExpressionException(null, 0, 0).getMessage());
    }

    @Test
    void shiftedMovesTheSpanIntoTemplateSpace() {
        ExpressionException e = new ExpressionException("bad", 3, 5).shifted(10);
        assertEquals(13, e.start());
        assertEquals(15, e.end());
    }

    @Test
    void shiftedKeepsPositionlessErrorsAsTheyAre() {
        ExpressionException e = ExpressionException.of("no position");
        assertSame(e, e.shifted(40));
    }

    @Test
    void exceptionsCarryNoStackTrace() {
        ExpressionException e = new ExpressionException("bulk user error", 0, 1);
        assertEquals(0, e.getStackTrace().length);
    }
}
