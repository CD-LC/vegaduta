package com.agenticai.core.quality.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/** The eval harness' model construction seam (ADR-0012 P1b): option values are exactly what EvalRunner always set. */
class EvalModelBuilderTest {

    @Test
    void openAiCompatSetsOnlyModelAndMaxTokens() {
        OpenAiChatModel model = EvalModelBuilder.openAiCompat(
            "http://localhost:11434", "k", "llama3", 1024,
            EvalLlmHttp.llmRestClientBuilder(), EvalLlmHttp.llmWebClientBuilder());
        OpenAiChatOptions options = (OpenAiChatOptions) model.getDefaultOptions();
        assertEquals("llama3", options.getModel());
        assertEquals(1024, options.getMaxTokens());
        assertNull(options.getTemperature());
        assertNull(options.getTopP());
        assertNotEquals(Boolean.TRUE, options.getStreamUsage());
    }

    @Test
    void anthropicSetsOnlyModelAndMaxTokens() {
        AnthropicChatModel model = EvalModelBuilder.anthropic(
            "k", "claude-sonnet-5", 1024, EvalLlmHttp.llmRestClientBuilder(), EvalLlmHttp.llmWebClientBuilder());
        AnthropicChatOptions options = (AnthropicChatOptions) model.getDefaultOptions();
        assertEquals("claude-sonnet-5", options.getModel());
        assertEquals(1024, options.getMaxTokens());
        assertNull(options.getTemperature());
        assertNull(options.getTopP());
    }
}
