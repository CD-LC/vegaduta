package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Locks the 2026-10-02 "switch on the controls" defaults (gap-analysis items 1, 3, 5, 7) so a
 * later edit cannot silently turn a safety control back off. Parses application.yml directly
 * (no Spring context), same approach as {@link TracingSamplingDefaultTest}.
 */
class SwitchedOnControlsDefaultsTest {

    @SuppressWarnings("unchecked")
    private static String get(String... path) throws Exception {
        try (InputStream in = SwitchedOnControlsDefaultsTest.class.getResourceAsStream("/application.yml")) {
            assertThat(in).as("application.yml on the test classpath").isNotNull();
            Object current = new Yaml().load(in);
            for (String key : path) {
                assertThat(current).as("path segment " + key).isInstanceOf(Map.class);
                current = ((Map<String, Object>) current).get(key);
            }
            return String.valueOf(current);
        }
    }

    @Test
    void confirmationGateIsOnByDefault() throws Exception {
        assertThat(get("app", "tools", "classification", "confirmation-gate"))
            .isEqualTo("${TOOL_CONFIRMATION_GATE:true}");
    }

    @Test
    void effectJournalIsOnByDefault() throws Exception {
        assertThat(get("app", "tools", "effect-journal", "enabled"))
            .isEqualTo("${TOOL_EFFECT_JOURNAL_ENABLED:true}");
    }

    @Test
    void perRunUsdBudgetHasANonZeroDefault() throws Exception {
        assertThat(get("app", "guardrails", "run-budget", "usd")).isEqualTo("${AGENT_RUN_BUDGET_USD:2.0}");
    }

    @Test
    void evalRunsDefaultToThreeRepeatsAndGateChecksPassHatK() throws Exception {
        assertThat(get("app", "quality", "eval", "default-repeats")).isEqualTo("${EVAL_DEFAULT_REPEATS:3}");
        assertThat(get("app", "quality", "gate", "pass-hat-k", "enabled"))
            .isEqualTo("${EVAL_GATE_PASS_HAT_K_ENABLED:true}");
    }
}
