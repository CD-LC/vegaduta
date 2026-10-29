package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ContentGuardrailResult} is what one guardrail answers for one piece of content: a plain
 * pass, a redacting pass (the content continues, rewritten - the only shape that carries {@code
 * redactedContent}), or a fail with a reason and confidence. Both {@code toString} branches are
 * part of the operator-facing log output.
 */
class ContentGuardrailResultTest {

    @Test
    void passCarriesNoReasonOrRewrite() {
        ContentGuardrailResult result = ContentGuardrailResult.pass(ContentGuardrailType.PROFANITY);
        assertTrue(result.isPassed());
        assertEquals(ContentGuardrailType.PROFANITY, result.getGuardrailType());
        assertNull(result.getReason());
        assertEquals(0, result.getConfidence());
        assertNull(result.getRedactedContent());
        assertEquals("PROFANITY: PASS", result.toString());
    }

    @Test
    void redactPassesWhileCarryingTheRewrittenContent() {
        ContentGuardrailResult result =
            ContentGuardrailResult.redact(ContentGuardrailType.PII, "reach me at [redacted]");
        assertTrue(result.isPassed());
        assertEquals("[redacted]".replace("[redacted]", "reach me at [redacted]"), result.getRedactedContent());
        assertEquals(ContentGuardrailType.PII, result.getGuardrailType());
        assertNull(result.getReason());
        assertEquals("PII: PASS", result.toString());
    }

    @Test
    void failCarriesReasonAndConfidence() {
        ContentGuardrailResult result =
            ContentGuardrailResult.fail(ContentGuardrailType.JAILBREAK, "injection phrasing", 90);
        assertFalse(result.isPassed());
        assertEquals("injection phrasing", result.getReason());
        assertEquals(90, result.getConfidence());
        assertNull(result.getRedactedContent(), "a block withholds the content, it does not rewrite it");
        assertEquals("JAILBREAK: FAIL (confidence=90, reason=injection phrasing)", result.toString());
    }
}
