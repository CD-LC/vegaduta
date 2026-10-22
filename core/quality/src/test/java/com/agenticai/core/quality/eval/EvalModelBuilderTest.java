package com.agenticai.core.quality.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * The eval harness' model construction seam (ADR-0012 P1b): option values are exactly what
 * EvalRunner always set.
 */
class EvalModelBuilderTest {

    @Test
    void openAiCompatSetsOnlyModelAndMaxTokens() {
        OpenAiChatModel model =
                EvalModelBuilder.openAiCompat(
                        "http://localhost:11434",
                        "k",
                        "llama3",
                        1024,
                        java.time.Duration.ofSeconds(10),
                        java.time.Duration.ofMinutes(10));
        OpenAiChatOptions options = model.getOptions();
        assertEquals("llama3", options.getModel());
        assertEquals(1024, options.getMaxTokens());
        assertNull(options.getTemperature());
        assertNull(options.getTopP());
        assertEquals("http://localhost:11434/v1", options.getBaseUrl());
        assertNotEquals(
                Boolean.TRUE,
                options.getStreamOptions() != null
                        ? options.getStreamOptions().includeUsage()
                        : null);
    }

    @Test
    void anthropicSetsOnlyModelAndMaxTokens() {
        AnthropicChatModel model =
                EvalModelBuilder.anthropic(
                        "k",
                        "claude-sonnet-5",
                        1024,
                        java.time.Duration.ofSeconds(10),
                        java.time.Duration.ofMinutes(10));
        AnthropicChatOptions options = (AnthropicChatOptions) model.getOptions();
        assertEquals("claude-sonnet-5", options.getModel());
        assertEquals(1024, options.getMaxTokens());
        assertNull(options.getTemperature());
        assertNull(options.getTopP());
    }
}
