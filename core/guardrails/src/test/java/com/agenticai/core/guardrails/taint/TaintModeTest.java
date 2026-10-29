package com.agenticai.core.guardrails.taint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link TaintMode#parse} is deliberately lenient: a deployment typo must never turn the taint
 * layer into something unrecognised, so blank and unknown values resolve to {@link
 * TaintMode#OBSERVE} - the mode that changes nothing but still records what enforcement would do.
 */
class TaintModeTest {

    @Test
    void knownModesParseCaseInsensitively() {
        assertEquals(TaintMode.OFF, TaintMode.parse("off"));
        assertEquals(TaintMode.OFF, TaintMode.parse("OFF"));
        assertEquals(TaintMode.OBSERVE, TaintMode.parse("Observe"));
        assertEquals(TaintMode.ENFORCE, TaintMode.parse("enforce"));
        assertEquals(TaintMode.ENFORCE, TaintMode.parse("  ENFORCE  "), "surrounding whitespace is trimmed");
    }

    @Test
    void blankAndMissingValuesDefaultToObserve() {
        assertEquals(TaintMode.OBSERVE, TaintMode.parse(null));
        assertEquals(TaintMode.OBSERVE, TaintMode.parse(""));
        assertEquals(TaintMode.OBSERVE, TaintMode.parse("   "));
    }

    @Test
    void unrecognisedValuesDefaultToObserve() {
        assertEquals(TaintMode.OBSERVE, TaintMode.parse("strict"));
        assertEquals(TaintMode.OBSERVE, TaintMode.parse("enabled"));
        assertEquals(TaintMode.OBSERVE, TaintMode.parse("OBSERVER"), "near-misses do not match");
    }
}
