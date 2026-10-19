package com.agenticai.core.billing.invoice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Audit D6 - {@link GstinValidator} structural + mod-36 checksum coverage.
 * Pure unit test, no Spring context (same style as
 * {@link com.agenticai.core.billing.gateway.RazorpayPlanCatalogTest}).
 * {@code 27AAPFU0939F1ZV} is the canonical published sample GSTIN whose
 * checksum character is genuinely valid.
 */
class GstinValidatorTest {

    private static final String VALID_SAMPLE = "27AAPFU0939F1ZV";

    @Test
    void acceptsCanonicalValidGstin() {
        assertTrue(GstinValidator.isValid(VALID_SAMPLE));
    }

    @Test
    void acceptsLowercaseAndSurroundingWhitespace() {
        assertTrue(GstinValidator.isValid("  27aapfu0939f1zv "));
    }

    @Test
    void rejectsWrongChecksumCharacter() {
        // Same structure as the valid sample, checksum char changed V -> W.
        assertFalse(GstinValidator.isValid("27AAPFU0939F1ZW"));
    }

    @Test
    void rejectsNullAndBlank() {
        assertFalse(GstinValidator.isValid(null));
        assertFalse(GstinValidator.isValid(""));
        assertFalse(GstinValidator.isValid("   "));
    }

    @Test
    void rejectsWrongLength() {
        assertFalse(GstinValidator.isValid("27AAPFU0939F1Z"));    // 14 chars
        assertFalse(GstinValidator.isValid("27AAPFU0939F1ZV5"));  // 16 chars
    }

    @Test
    void rejectsStructurallyInvalidStrings() {
        assertFalse(GstinValidator.isValid("A7AAPFU0939F1ZV"));   // state code must be digits
        assertFalse(GstinValidator.isValid("27AAPF10939F1ZV"));   // PAN letters block broken
        assertFalse(GstinValidator.isValid("27AAPFU0939F10V"));   // 14th char must be literal Z
        assertFalse(GstinValidator.isValid("27AAPFU0939F0ZV"));   // entity code cannot be 0
    }

    @Test
    void rejectsPanAloneOrRandomText() {
        assertFalse(GstinValidator.isValid("AAPFU0939F"));
        assertFalse(GstinValidator.isValid("not-a-gstin-here"));
    }
}
