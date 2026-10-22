package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Characterisation of {@link NumberCanon}: strict vs lenient numbers, number text/node forms, ISO
 * dates.
 */
class NumberCanonTest {

    @Test
    void canonicalRejectsLeadingZerosAndKeepsPlainNumbers() {
        assertNull(NumberCanon.canonicalNumber("01234"));
        assertNull(NumberCanon.canonicalNumber("+5"));
        assertEquals(0, new BigDecimal("12.50").compareTo(NumberCanon.canonicalNumber(" 12.5 ")));
        assertEquals(0, new BigDecimal("-3").compareTo(NumberCanon.canonicalNumber("-3")));
        assertNull(NumberCanon.canonicalNumber("abc"));
        assertNull(NumberCanon.canonicalNumber((String) null));
    }

    @Test
    void lenientAcceptsLeadingZerosSignAndBareDecimals() {
        assertEquals(0, new BigDecimal("1234").compareTo(NumberCanon.lenientNumber("01234")));
        assertEquals(0, new BigDecimal("5").compareTo(NumberCanon.lenientNumber("+5")));
        assertEquals(0, new BigDecimal("0.5").compareTo(NumberCanon.lenientNumber(".5")));
        assertNull(NumberCanon.lenientNumber("1,000"));
        assertNull(NumberCanon.lenientNumber("x".repeat(70)));
    }

    @Test
    void numberTextStripsTrailingZeros() {
        assertEquals("12.5", NumberCanon.numberText(new BigDecimal("12.500")));
        assertEquals("0", NumberCanon.numberText(new BigDecimal("0.00")));
        assertEquals("100", NumberCanon.numberText(new BigDecimal("1E+2")));
    }

    @Test
    void numberNodeIsIntegerWhenIntegralAndDoubleOtherwise() {
        assertTrue(NumberCanon.numberNode(new BigDecimal("7.0")).isIntegralNumber());
        assertTrue(NumberCanon.numberNode(new BigDecimal("7.25")).isDouble());
        assertTrue(NumberCanon.numberNode(new BigDecimal("1E+30")).isDouble());
    }

    @Test
    void parseDateReadsIsoShapesAsUtc() {
        assertEquals(0L, NumberCanon.parseDate("1970-01-01"));
        assertEquals(0L, NumberCanon.parseDate("1970-01-01T00:00:00"));
        assertEquals(0L, NumberCanon.parseDate("1970-01-01T01:00:00+01:00"));
        assertEquals(0L, NumberCanon.parseDate("1970-01-01T00:00:00Z"));
        assertNull(NumberCanon.parseDate("not a date"));
        assertNull(NumberCanon.parseDate("1970-13-45"));
        assertNull(NumberCanon.parseDate(null));
    }
}
