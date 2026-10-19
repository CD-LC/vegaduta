package com.agenticai.core.agents.tools.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The money conversion is the one place a rounding slip would mint a wrong charge - pinned
 * exhaustively.
 */
class CommerceToolSupportTest {

    @Test
    void rupeesToPaiseIsExactForEveryInputShapeTheModelProduces() {
        assertEquals(49_900L, CommerceToolSupport.inrToPaise(499));
        assertEquals(49_900L, CommerceToolSupport.inrToPaise(499.0));
        assertEquals(49_950L, CommerceToolSupport.inrToPaise(499.5));
        assertEquals(49_950L, CommerceToolSupport.inrToPaise("499.50"));
        assertEquals(125_000L, CommerceToolSupport.inrToPaise("1,250"));
        assertEquals(125_000L, CommerceToolSupport.inrToPaise("₹1,250.00"));
        assertEquals(125_000L, CommerceToolSupport.inrToPaise("Rs. 1250"));
        assertEquals(1L, CommerceToolSupport.inrToPaise("0.01"));
        // Large but legal: the per-payment ceiling is the service's job, not this converter's.
        assertEquals(10_000_000L, CommerceToolSupport.inrToPaise(100000));
    }

    @Test
    void rejectsRatherThanRoundsAnythingFinerThanAPaisa() {
        assertThrows(IllegalArgumentException.class, () -> CommerceToolSupport.inrToPaise(10.005));
        assertThrows(IllegalArgumentException.class, () -> CommerceToolSupport.inrToPaise("0.001"));
    }

    @Test
    void rejectsZeroNegativeBlankAndNonNumeric() {
        assertThrows(IllegalArgumentException.class, () -> CommerceToolSupport.inrToPaise(0));
        assertThrows(IllegalArgumentException.class, () -> CommerceToolSupport.inrToPaise(-5));
        assertThrows(IllegalArgumentException.class, () -> CommerceToolSupport.inrToPaise(""));
        assertThrows(IllegalArgumentException.class, () -> CommerceToolSupport.inrToPaise(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommerceToolSupport.inrToPaise("five hundred"));
    }

    @Test
    void phoneNormalisationIsLenientOnFormatStrictOnShape() {
        assertNull(CommerceToolSupport.normalisePhone(null));
        assertNull(CommerceToolSupport.normalisePhone("  "));
        assertEquals("+919876543210", CommerceToolSupport.normalisePhone("9876543210"));
        assertEquals("+919876543210", CommerceToolSupport.normalisePhone("98765 43210"));
        assertEquals("+919876543210", CommerceToolSupport.normalisePhone("+91 98765-43210"));
        assertEquals("+919876543210", CommerceToolSupport.normalisePhone("919876543210"));
        assertEquals("+14155552671", CommerceToolSupport.normalisePhone("+1 (415) 555-2671"));
        assertThrows(
                IllegalArgumentException.class, () -> CommerceToolSupport.normalisePhone("12"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommerceToolSupport.normalisePhone("call me"));
    }
}
