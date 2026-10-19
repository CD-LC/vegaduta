package com.agenticai.core.chat;

import java.util.List;
import java.util.Map;

/**
 * Optional per-agent sampling controls beyond max-tokens/temperature/top-p (migration V331),
 * carried to the provider factories through the {@code providerOptions} map under {@link
 * #OPTION_KEY}. Every field is optional: null / empty means "not set" and the factory sends
 * nothing. A factory applies only the fields its provider's API supports and silently omits
 * the rest - an unsupported field is never an error.
 */
public record SamplingOptions(
    Integer topK, List<String> stopSequences, Double frequencyPenalty, Double presencePenalty, Integer seed
) {

    /** {@code providerOptions} key on {@code GatewayCallSpec}. */
    public static final String OPTION_KEY = "sampling";

    public static final SamplingOptions NONE = new SamplingOptions(null, List.of(), null, null, null);

    public SamplingOptions {
        stopSequences = stopSequences == null ? List.of() : List.copyOf(stopSequences);
    }

    public boolean isEmpty() {
        return topK == null && stopSequences.isEmpty() && frequencyPenalty == null
            && presencePenalty == null && seed == null;
    }

    /** Reads {@link #OPTION_KEY} from a providerOptions map; absent or wrong type resolves to {@link #NONE}. */
    public static SamplingOptions from(Map<String, Object> providerOptions) {
        if (providerOptions != null && providerOptions.get(OPTION_KEY) instanceof SamplingOptions s) {
            return s;
        }
        return NONE;
    }

    /** OpenAI-compatible chat completions: stop, frequency_penalty, presence_penalty, seed (no top_k). */
    public void applyTo(org.springframework.ai.openai.OpenAiChatOptions.Builder b) {
        if (!stopSequences.isEmpty()) b.stop(stopSequences);
        if (frequencyPenalty != null) b.frequencyPenalty(frequencyPenalty);
        if (presencePenalty != null) b.presencePenalty(presencePenalty);
        if (seed != null) b.seed(seed);
    }

    /** Anthropic Messages API: top_k and stop_sequences only. */
    public void applyTo(org.springframework.ai.anthropic.AnthropicChatOptions.Builder b) {
        if (topK != null) b.topK(topK);
        if (!stopSequences.isEmpty()) b.stopSequences(stopSequences);
    }

    /** Gemini / Vertex: topK, stopSequences, frequencyPenalty, presencePenalty (seed is not exposed by the builder). */
    public void applyTo(org.springframework.ai.google.genai.GoogleGenAiChatOptions.Builder b) {
        if (topK != null) b.topK(topK);
        if (!stopSequences.isEmpty()) b.stopSequences(stopSequences);
        if (frequencyPenalty != null) b.frequencyPenalty(frequencyPenalty);
        if (presencePenalty != null) b.presencePenalty(presencePenalty);
    }
}
