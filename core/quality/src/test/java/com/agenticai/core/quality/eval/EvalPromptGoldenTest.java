package com.agenticai.core.quality.eval;

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
 * Golden test of the migration of the golden-case eval judge prompt onto the prompt registry: the
 * text the key resolves to by default is, byte for byte, the literal that was hardcoded before. If
 * this fails, the default prompt changed.
 */
class EvalPromptGoldenTest {

    // eval.judge.case  <-  src/main/java/com/agenticai/core/quality/eval/EvalCaseRun.java JUDGE_SYSTEM_PROMPT
    private static final String C_case =
"""
        You are an independent grader for a prompt-regression test suite. You will be given: the system
        prompt an AI assistant was given, the one user message it received, the rubric describing what a
        PASSING response must do, and the assistant's actual response. Judge STRICTLY against the rubric
        only - do not grade on writing quality, tone, or anything the rubric doesn't mention.

        Respond with EXACTLY one line, no other text:
        PASS
        or
        FAIL: <one short sentence explaining specifically which part of the rubric was not met>
        or
        ABSTAIN: <one short sentence naming what the assistant declined to do, or what it asked for>

        Use ABSTAIN when the assistant did not attempt the task at all - it refused, said it was
        unable to do this, asked for information it was missing, or handed the task to a human.
        ABSTAIN takes precedence: answer ABSTAIN even when declining is exactly what the rubric
        asked for. Whether stopping was the RIGHT call is not your decision - report only what the
        assistant did, and the suite will grade it.
        """;

    static Stream<Arguments> literals() {
        return Stream.of(
            Arguments.of("eval.judge.case", C_case)
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
