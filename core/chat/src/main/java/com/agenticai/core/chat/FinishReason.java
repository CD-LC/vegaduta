package com.agenticai.core.chat;

import java.util.Locale;
import java.util.Map;

/**
 * Why a model stopped generating, in one vocabulary across providers. Spring AI hands the app the
 * provider's own string on {@code ChatGenerationMetadata#getFinishReason()}, and every provider
 * spells it differently: Anthropic {@code stop_reason} ({@code end_turn}, {@code max_tokens},
 * {@code tool_use}, {@code refusal}), OpenAI-compatible {@code finish_reason} ({@code stop},
 * {@code length}, {@code content_filter}, {@code tool_calls}), Gemini {@code finishReason}
 * ({@code STOP}, {@code MAX_TOKENS}, {@code SAFETY}, {@code MALFORMED_FUNCTION_CALL}, ...) and
 * Ollama {@code done_reason} ({@code stop}, {@code length}).
 *
 * <p>The table below is protocol vocabulary of those APIs, not a tuning knob: a value that is not
 * listed maps to {@link #OTHER} (present but unrecognised) and is never treated as a failure.
 */
public enum FinishReason {

    /** The model finished its reply (end of turn, stop sequence). */
    STOP,

    /** The reply was cut off by the output-token limit or the context window. */
    LENGTH,

    /** The provider's safety / content system, or the model itself, declined or stopped the reply. */
    SAFETY,

    /** The model tried to call a tool but produced a call the provider could not use. */
    MALFORMED_TOOL_CALL,

    /** The model stopped to call tools (an intermediate step, not a final answer). */
    TOOL_CALLS,

    /** A reason was reported but is not one of the above. */
    OTHER,

    /** No reason was reported. */
    UNKNOWN;

    private static final Map<String, FinishReason> BY_NAME = Map.ofEntries(
        // Anthropic stop_reason, OpenAI/Ollama/Groq/Mistral finish_reason, Gemini FinishReason.STOP
        Map.entry("stop", STOP),
        Map.entry("end_turn", STOP),
        Map.entry("stop_sequence", STOP),
        Map.entry("eos", STOP),
        Map.entry("completed", STOP),
        // output limit
        Map.entry("length", LENGTH),
        Map.entry("max_tokens", LENGTH),
        Map.entry("max_output_tokens", LENGTH),
        Map.entry("model_context_window_exceeded", LENGTH),
        Map.entry("context_length_exceeded", LENGTH),
        // safety / refusal
        Map.entry("content_filter", SAFETY),
        Map.entry("content_filtered", SAFETY),
        Map.entry("refusal", SAFETY),
        Map.entry("safety", SAFETY),
        Map.entry("recitation", SAFETY),
        Map.entry("blocklist", SAFETY),
        Map.entry("prohibited_content", SAFETY),
        Map.entry("spii", SAFETY),
        Map.entry("image_safety", SAFETY),
        Map.entry("image_prohibited_content", SAFETY),
        Map.entry("image_recitation", SAFETY),
        Map.entry("guardrail_intervened", SAFETY),
        // tool calls the provider could not use
        Map.entry("malformed_function_call", MALFORMED_TOOL_CALL),
        Map.entry("unexpected_tool_call", MALFORMED_TOOL_CALL),
        Map.entry("too_many_tool_calls", MALFORMED_TOOL_CALL),
        Map.entry("tool_use_failed", MALFORMED_TOOL_CALL),
        Map.entry("invalid_tool_call", MALFORMED_TOOL_CALL),
        // a normal intermediate stop
        Map.entry("tool_calls", TOOL_CALLS),
        Map.entry("tool_use", TOOL_CALLS),
        Map.entry("tool_call", TOOL_CALLS),
        Map.entry("function_call", TOOL_CALLS),
        // present but meaningless: Gemini's unset value
        Map.entry("finish_reason_unspecified", UNKNOWN));

    /**
     * Maps a provider's raw finish reason to this vocabulary. Case, {@code -} and spaces do not
     * matter ({@code END_TURN}, {@code end-turn} and {@code end_turn} are the same); a blank or
     * {@code null} value is {@link #UNKNOWN}.
     */
    public static FinishReason of(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String key = raw.strip().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return BY_NAME.getOrDefault(key, OTHER);
    }

    /** True for a reason that means the reply is not a finished answer ({@link #LENGTH} and kin). */
    public boolean isAbnormal() {
        return this == LENGTH || this == SAFETY || this == MALFORMED_TOOL_CALL;
    }
}
