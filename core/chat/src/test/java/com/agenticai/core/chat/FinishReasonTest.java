package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The provider spellings of "why the model stopped" all land in one vocabulary. The values are the
 * ones the four providers the platform runs on actually send: Anthropic {@code stop_reason}, OpenAI
 * {@code finish_reason} (Spring AI upper-cases it), Gemini {@code FinishReason} and Ollama {@code
 * done_reason}.
 */
class FinishReasonTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        // Anthropic
        "end_turn, STOP", "stop_sequence, STOP", "max_tokens, LENGTH", "tool_use, TOOL_CALLS", "refusal, SAFETY",
        "model_context_window_exceeded, LENGTH", "pause_turn, OTHER",
        // OpenAI and OpenAI-compatible
        "stop, STOP", "STOP, STOP", "length, LENGTH", "LENGTH, LENGTH", "content_filter, SAFETY",
        "CONTENT_FILTER, SAFETY", "tool_calls, TOOL_CALLS", "function_call, TOOL_CALLS", "tool_use_failed, MALFORMED_TOOL_CALL",
        // Gemini
        "MAX_TOKENS, LENGTH", "SAFETY, SAFETY", "RECITATION, SAFETY", "BLOCKLIST, SAFETY", "PROHIBITED_CONTENT, SAFETY",
        "SPII, SAFETY", "MALFORMED_FUNCTION_CALL, MALFORMED_TOOL_CALL", "UNEXPECTED_TOOL_CALL, MALFORMED_TOOL_CALL",
        "OTHER, OTHER", "FINISH_REASON_UNSPECIFIED, UNKNOWN",
        // Ollama (done_reason), spelled with spaces or dashes by some gateways
        "load, OTHER", "end-turn, STOP", "max tokens, LENGTH",
        // anything unseen is "present but unrecognised", never an error
        "something_new, OTHER"
    })
    void everyProviderSpellingMapsToTheSharedVocabulary(String raw, FinishReason expected) {
        assertEquals(expected, FinishReason.of(raw));
    }

    @Test
    void noReasonIsUnknownNotStop() {
        assertEquals(FinishReason.UNKNOWN, FinishReason.of(null));
        assertEquals(FinishReason.UNKNOWN, FinishReason.of(""));
        assertEquals(FinishReason.UNKNOWN, FinishReason.of("   "));
    }

    @Test
    void onlyTruncationRefusalAndMalformedCallsAreAbnormal() {
        for (FinishReason r : List.of(FinishReason.LENGTH, FinishReason.SAFETY, FinishReason.MALFORMED_TOOL_CALL)) {
            assertTrue(r.isAbnormal(), r.name());
        }
        for (FinishReason r : List.of(FinishReason.STOP, FinishReason.TOOL_CALLS, FinishReason.OTHER, FinishReason.UNKNOWN)) {
            assertFalse(r.isAbnormal(), r.name());
        }
    }

    @Test
    void generatedToolCallIdsAreNonBlankUniqueAndOnlyUsedWhenNeeded() {
        String a = ToolCallIds.ensure("");
        String b = ToolCallIds.ensure(null);
        assertTrue(a.startsWith("call_") && !a.isBlank());
        assertTrue(!a.equals(b), "two calls must not share an id");
        assertEquals("toolu_01", ToolCallIds.ensure("toolu_01"), "a provider's own id is kept");
    }
}
