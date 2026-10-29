package com.agenticai.core.agents.tools.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link TrifectaEnforcementMode} parses the {@code app.tools.classification.trifecta-enforcement}
 * value. The contract matters because it never throws: an unrecognised operator typo resolves to
 * {@link TrifectaEnforcementMode#WARN} (count and surface the advisory) rather than crash the boot,
 * while still being caught as "not recognised" for a validation warning of its own.
 */
class TrifectaEnforcementModeTest {

    @Test
    void wireNamesAreLowerCaseHyphenated() {
        assertEquals("off", TrifectaEnforcementMode.OFF.wireName());
        assertEquals("warn", TrifectaEnforcementMode.WARN.wireName());
        assertEquals("require-approval", TrifectaEnforcementMode.REQUIRE_APPROVAL.wireName());
        assertEquals("block", TrifectaEnforcementMode.BLOCK.wireName());
    }

    @Test
    void parseAcceptsBothWireSpellingsAndEnums() {
        assertEquals(TrifectaEnforcementMode.OFF, TrifectaEnforcementMode.parse("off"));
        assertEquals(TrifectaEnforcementMode.REQUIRE_APPROVAL,
            TrifectaEnforcementMode.parse("require-approval"));
        assertEquals(TrifectaEnforcementMode.REQUIRE_APPROVAL,
            TrifectaEnforcementMode.parse("require_approval"));
        assertEquals(TrifectaEnforcementMode.BLOCK, TrifectaEnforcementMode.parse("  BLOCK  "));
        assertEquals(TrifectaEnforcementMode.WARN, TrifectaEnforcementMode.parse("Warn"));
    }

    @Test
    void parseFallsBackToWarnForAbsentOrUnknownValues() {
        assertEquals(TrifectaEnforcementMode.WARN, TrifectaEnforcementMode.parse(null));
        assertEquals(TrifectaEnforcementMode.WARN, TrifectaEnforcementMode.parse(""));
        assertEquals(TrifectaEnforcementMode.WARN, TrifectaEnforcementMode.parse("   "));
        assertEquals(TrifectaEnforcementMode.WARN, TrifectaEnforcementMode.parse("aggressive"));
    }

    @Test
    void isRecognisedSeparatesATypoFromARealValue() {
        assertTrue(TrifectaEnforcementMode.isRecognised(null));
        assertTrue(TrifectaEnforcementMode.isRecognised("  "));
        assertTrue(TrifectaEnforcementMode.isRecognised("require-approval"));
        assertTrue(TrifectaEnforcementMode.isRecognised("BLOCK"));
        assertFalse(TrifectaEnforcementMode.isRecognised("aggressive"));
    }
}
