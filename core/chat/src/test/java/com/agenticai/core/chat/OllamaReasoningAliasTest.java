package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OllamaReasoningAliasTest {

    @Test
    void rewritesTheTopLevelReasoningField() {
        String sse =
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"reasoning\":\"let me\",\"content\":\"\"}}]}\n\n";
        String out = OllamaReasoningAlias.alias(sse);
        assertThat(out).contains("\"reasoning_content\":\"let me\"");
        assertThat(out).doesNotContain("\"reasoning\":\"let me\"");
    }

    @Test
    void doesNotTouchEscapedOrStringContent() {
        // The model writing about a "reasoning" key inside its own JSON output
        // produces escaped quotes - they must never be rewritten.
        String sse =
                "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"reasoning\\\": 5}\"}}]}\n\n";
        assertThat(OllamaReasoningAlias.alias(sse)).isEqualTo(sse);
    }

    @Test
    void leavesLookalikeFieldsAlone() {
        // "reasoning_effort" (a request field) and "reason" must not be
        // rewritten - only an exact "reasoning" key matches.
        String sse = "data: {\"choices\":[{\"delta\":{\"reasoning_effort\":\"high\",\"reason\":\"x\"}}]}\n\n";
        assertThat(OllamaReasoningAlias.alias(sse)).isEqualTo(sse);
    }
}
