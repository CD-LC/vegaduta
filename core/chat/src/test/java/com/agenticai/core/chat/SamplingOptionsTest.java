package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

class SamplingOptionsTest {

    private static final SamplingOptions FULL = new SamplingOptions(40, List.of("END", "STOP"), 0.5, -0.25, 42);

    @Test
    void absentOrWrongTypeResolvesToNone() {
        assertThat(SamplingOptions.from(null).isEmpty()).isTrue();
        assertThat(SamplingOptions.from(Map.of()).isEmpty()).isTrue();
        assertThat(SamplingOptions.from(Map.of(SamplingOptions.OPTION_KEY, "junk")).isEmpty()).isTrue();
        assertThat(SamplingOptions.from(Map.of(SamplingOptions.OPTION_KEY, FULL))).isSameAs(FULL);
    }

    @Test
    void openAiCompatibleGetsStopPenaltiesAndSeedButNotTopK() {
        OpenAiChatOptions.Builder b = OpenAiChatOptions.builder().model("m");
        FULL.applyTo(b);
        OpenAiChatOptions o = b.build();
        assertThat(o.getStop()).containsExactly("END", "STOP");
        assertThat(o.getFrequencyPenalty()).isEqualTo(0.5);
        assertThat(o.getPresencePenalty()).isEqualTo(-0.25);
        assertThat(o.getSeed()).isEqualTo(42);
    }

    @Test
    void anthropicGetsOnlyTopKAndStopSequences() {
        AnthropicChatOptions.Builder b = AnthropicChatOptions.builder().model("m");
        FULL.applyTo(b);
        AnthropicChatOptions o = b.build();
        assertThat(o.getTopK()).isEqualTo(40);
        assertThat(o.getStopSequences()).containsExactly("END", "STOP");
    }

    @Test
    void geminiGetsTopKStopAndPenalties() {
        GoogleGenAiChatOptions.Builder b = GoogleGenAiChatOptions.builder().model("m");
        FULL.applyTo(b);
        GoogleGenAiChatOptions o = b.build();
        assertThat(o.getTopK()).isEqualTo(40);
        assertThat(o.getStopSequences()).containsExactly("END", "STOP");
        assertThat(o.getFrequencyPenalty()).isEqualTo(0.5);
        assertThat(o.getPresencePenalty()).isEqualTo(-0.25);
    }

    @Test
    void emptyOptionsLeaveBuildersUntouched() {
        OpenAiChatOptions.Builder b = OpenAiChatOptions.builder().model("m");
        SamplingOptions.NONE.applyTo(b);
        OpenAiChatOptions o = b.build();
        assertThat(o.getStop()).isNull();
        assertThat(o.getSeed()).isNull();
        assertThat(o.getFrequencyPenalty()).isNull();
    }
}
