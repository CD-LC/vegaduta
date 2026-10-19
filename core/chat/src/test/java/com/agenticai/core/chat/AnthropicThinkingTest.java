package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AnthropicThinkingTest {

    @Test
    void mapsEffortToBudget() {
        assertThat(AnthropicThinking.budgetFor("claude-sonnet-4-5", "low", 16000)).isEqualTo(1024);
        assertThat(AnthropicThinking.budgetFor("claude-sonnet-4-5", "MEDIUM", 32000)).isEqualTo(8192);
        assertThat(AnthropicThinking.budgetFor("claude-opus-4-1", "high", 64000)).isEqualTo(24576);
    }

    @Test
    void offWhenNullNoneUnknownOrUnsupportedModel() {
        assertThat(AnthropicThinking.budgetFor("claude-sonnet-4-5", null, 16000)).isNull();
        assertThat(AnthropicThinking.budgetFor("claude-sonnet-4-5", "none", 16000)).isNull();
        assertThat(AnthropicThinking.budgetFor("claude-sonnet-4-5", "extreme", 16000)).isNull();
        assertThat(AnthropicThinking.budgetFor("claude-3-5-haiku-latest", "high", 16000)).isNull();
        assertThat(AnthropicThinking.budgetFor(null, "high", 16000)).isNull();
    }

    @Test
    void clampsToHalfOfMaxTokensAndSkipsWhenTooSmall() {
        assertThat(AnthropicThinking.budgetFor("claude-sonnet-4-5", "high", 8000)).isEqualTo(4000);
        assertThat(AnthropicThinking.budgetFor("claude-sonnet-4-5", "low", 2000)).isNull();
    }

    @Test
    void newerUnverifiedModelsStayOff() {
        assertThat(AnthropicThinking.budgetFor("claude-sonnet-4-6", "high", 32000)).isNull();
        assertThat(AnthropicThinking.budgetFor("claude-opus-4-7", "high", 32000)).isNull();
        assertThat(AnthropicThinking.budgetFor("claude-sonnet-4-5-20250929", "low", 16000)).isEqualTo(1024);
        assertThat(AnthropicThinking.budgetFor("claude-sonnet-4-20250514", "low", 16000)).isEqualTo(1024);
    }

    @Test
    void thinkingOnSendsNoTemperatureTopPOrTopKButKeepsStopSequences() {
        SamplingOptions sampling = new SamplingOptions(40, java.util.List.of("END"), null, null, null);
        var options = AnthropicAgentChatClientFactory
            .samplingBuilder("claude-sonnet-4-5", 16000, 0.3, 0.9, sampling, 8192).build();
        assertThat(options.getThinking()).isNotNull();
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getTopP()).isNull();
        assertThat(options.getTopK()).isNull();
        assertThat(options.getStopSequences()).containsExactly("END");
    }

    @Test
    void thinkingOffKeepsExistingSamplingBehaviour() {
        SamplingOptions sampling = new SamplingOptions(40, java.util.List.of(), null, null, null);
        var temp = AnthropicAgentChatClientFactory
            .samplingBuilder("claude-sonnet-4-5", 16000, 0.3, 1.0, sampling, null).build();
        assertThat(temp.getThinking()).isNull();
        assertThat(temp.getTemperature()).isEqualTo(0.3);
        assertThat(temp.getTopP()).isNull();
        assertThat(temp.getTopK()).isEqualTo(40);
        var narrowed = AnthropicAgentChatClientFactory
            .samplingBuilder("claude-sonnet-4-5", 16000, 0.3, 0.8, SamplingOptions.NONE, null).build();
        assertThat(narrowed.getTopP()).isEqualTo(0.8);
        assertThat(narrowed.getTemperature()).isNull();
    }

    @Test
    void perCallOptionOverridesConfigured() {
        assertThat(AnthropicThinking.resolveEffort(Map.of("reasoningEffort", "high"), "low")).isEqualTo("high");
        assertThat(AnthropicThinking.resolveEffort(Map.of(), "low")).isEqualTo("low");
        assertThat(AnthropicThinking.resolveEffort(null, "")).isEmpty();
    }
}
