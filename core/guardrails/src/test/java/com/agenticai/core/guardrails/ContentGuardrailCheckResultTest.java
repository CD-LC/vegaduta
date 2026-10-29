package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ContentGuardrailCheckResult} is the engine's answer after every guardrail ran: a bare
 * pass (legacy shorthand, nothing to hand back), a pass carrying the sanitized content the caller
 * should keep using, or a block naming the guardrail that stopped it.
 */
class ContentGuardrailCheckResultTest {

    @Test
    void theBarePassShorthandCarriesNoContent() {
        ContentGuardrailCheckResult result = ContentGuardrailCheckResult.pass();
        assertTrue(result.isPassed());
        assertNull(result.getBlockedByGuardrail());
        assertNull(result.getBlockReason());
        assertEquals(0, result.getConfidence());
        assertNull(result.getSanitizedContent());
        assertEquals("ContentGuardrailCheckResult(PASSED)", result.toString());
    }

    @Test
    void aPassHandsBackTheContentToUse() {
        ContentGuardrailCheckResult result = ContentGuardrailCheckResult.pass("rewritten text");
        assertTrue(result.isPassed());
        assertEquals("rewritten text", result.getSanitizedContent());
        assertEquals("ContentGuardrailCheckResult(PASSED)", result.toString());
    }

    @Test
    void blockedNamesTheGuardrailReasonAndConfidence() {
        ContentGuardrailCheckResult result =
            ContentGuardrailCheckResult.blocked(ContentGuardrailType.POLICY_VIOLATION, "self-harm phrasing", 95);
        assertFalse(result.isPassed());
        assertEquals(ContentGuardrailType.POLICY_VIOLATION, result.getBlockedByGuardrail());
        assertEquals("self-harm phrasing", result.getBlockReason());
        assertEquals(95, result.getConfidence());
        assertNull(result.getSanitizedContent(), "a blocked result hands back nothing to use");
        assertEquals(
            "ContentGuardrailCheckResult(BLOCKED by POLICY_VIOLATION, confidence=95, reason=self-harm phrasing)",
            result.toString());
    }
}
