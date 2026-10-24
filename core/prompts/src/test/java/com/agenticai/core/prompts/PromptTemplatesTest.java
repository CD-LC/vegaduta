package com.agenticai.core.prompts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PromptTemplatesTest {

    private static PromptDefinition def(boolean strict, int min, int max, PromptPlaceholder... ph) {
        return new PromptDefinition("a.b", "T", "", PromptRiskClass.USER_VISIBLE, false, true, false, strict, List.of(ph), min, max);
    }

    @Test
    void onlyIdentifierTokensAreTokens() {
        assertThat(PromptTemplates.tokens("a {x} b {y_1} {\"k\":1} {{secret.NAME}} {} { z } {x}")).containsExactly("x", "y_1");
    }

    @Test
    void unknownPlaceholdersAreRejectedOnSave() {
        PromptDefinition d = def(true, 0, 100, new PromptPlaceholder("date", "", false));
        assertThat(PromptTemplates.validate(d, "today {date}")).isEmpty();
        assertThat(PromptTemplates.validate(d, "today {day}")).singleElement().asString().contains("Unknown placeholder {day}").contains("{date}");
        assertThat(PromptTemplates.validate(def(true, 0, 100), "x {y}")).singleElement().asString().contains("no placeholders");
    }

    @Test
    void missingRequiredPlaceholdersAreRejectedOnSave() {
        PromptDefinition d = def(true, 0, 100, new PromptPlaceholder("date", "", true));
        assertThat(PromptTemplates.validate(d, "no date here")).singleElement().asString().contains("Missing required placeholder {date}");
        assertThat(PromptTemplates.validate(d, "has {date}")).isEmpty();
    }

    @Test
    void lengthBoundsAreChecked() {
        PromptDefinition d = def(true, 5, 10);
        assertThat(PromptTemplates.validate(d, "abc")).singleElement().asString().contains("at least 5");
        assertThat(PromptTemplates.validate(d, "x".repeat(11))).singleElement().asString().contains("limited to 10");
        assertThat(PromptTemplates.validate(d, "x".repeat(10))).isEmpty();
    }

    @Test
    void freeTextAcceptsAnyBraces() {
        assertThat(PromptTemplates.validate(def(false, 0, 100), "answer in {language}")).isEmpty();
    }

    @Test
    void renderSubstitutesOnlyDeclaredTokensThatHaveAValue() {
        Set<String> declared = Set.of("date", "tz");
        assertThat(PromptTemplates.render("It is {date} {tz} {other} {date}", declared, Map.of("date", "D", "other", "O")))
                .isEqualTo("It is D {tz} {other} D");
        assertThat(PromptTemplates.render("plain", declared, Map.of("date", "D"))).isEqualTo("plain");
        assertThat(PromptTemplates.render("{date}", Set.of(), Map.of("date", "D"))).isEqualTo("{date}");
        assertThat(PromptTemplates.render("{date}", declared, Map.of("date", "$1 \\ x"))).isEqualTo("$1 \\ x");
    }
}
