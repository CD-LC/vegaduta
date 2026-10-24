package com.agenticai.core.agents.lab;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptContext;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.ResolvedPrompt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden test of the migration of the Agent Lab judge prompts onto the prompt registry: the text
 * each key resolves to by default is, byte for byte, the literal that was hardcoded before. If
 * this fails, a default prompt changed.
 */
class AgentLabPromptGoldenTest {

    // lab.judge.score  <-  src/main/java/com/agenticai/core/agents/lab/AgentLabJudge.java SCORE_SYSTEM_PROMPT
    private static final String C_score =
"""
        You grade one answer from an AI assistant. Be strict and consistent. Score from 0.0 to 1.0. \
        If a REFERENCE ANSWER is given: how fully the ANSWER agrees with it on every material point \
        (1.0 = same substance, 0.0 = contradicts or misses it); ignore wording, length and formatting. \
        If no REFERENCE ANSWER is given: how correctly and completely the ANSWER addresses the USER MESSAGE. \
        Reply in exactly this format and nothing else:
        SCORE: <number from 0.0 to 1.0>
        FEEDBACK: <one short sentence>""";

    // lab.judge.same  <-  src/main/java/com/agenticai/core/agents/lab/AgentLabJudge.java SAME_SYSTEM_PROMPT
    private static final String C_same =
"""
        You compare two answers to the same question. Decide whether they mean the same thing: \
        the same substantive claims, decision or recommendation. Ignore wording, length, tone and formatting. \
        If one answer states something the other contradicts or materially omits, they are different. \
        Reply with exactly one word: SAME or DIFFERENT.""";

    static Stream<Arguments> literals() {
        return Stream.of(
            Arguments.of("lab.judge.score", C_score),
            Arguments.of("lab.judge.same", C_same)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("literals")
    void shippedDefaultIsTheFormerLiteral(String key, String literal) {
        PromptResolver resolver = PromptResolver.defaultsOnly();
        assertThat(resolver.catalog().defaultText(key)).isEqualTo(literal);
        ResolvedPrompt resolved = resolver.resolve(key, PromptContext.none());
        assertThat(resolved.textOrNull()).isEqualTo(literal);
    }

    @Test
    void catalogIsSound() {
        PromptCatalog catalog = PromptResolver.defaultsOnly().catalog();
        assertThat(catalog.problems()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("literals")
    void keyIsRegisteredAsSecondaryCallWithADescription(String key, String literal) {
        var def = PromptResolver.defaultsOnly().catalog().find(key).orElseThrow();
        assertThat(def.description()).isNotBlank();
        assertThat(def.title()).isNotBlank();
        assertThat(def.disableable()).isFalse();
    }
}
