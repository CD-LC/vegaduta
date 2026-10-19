package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Item 12 (config half, Tech-Stack-Audit-2026.md P3.1): tracing sampling used to default to
 * 0.0 in every shipped config, which is idiomatic Micrometer "fully off" - no spans are ever
 * sampled regardless of whether the OTLP endpoint is reachable. Fixed to a low-overhead 0.1
 * default so tracing is on out of the box; still fully overridable via
 * TRACING_SAMPLING_PROBABILITY. This test parses application.yml directly (no Spring context,
 * no DB) so it stays fast and catches any regression back to the old 0.0 default.
 */
class TracingSamplingDefaultTest {

    @Test
    void tracingSamplingProbabilityDefaultsToNonZero() throws Exception {
        Yaml yaml = new Yaml();
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            assertThat(in).as("application.yml should be on the test classpath").isNotNull();
            Map<String, Object> root = yaml.load(in);
            String probabilityPlaceholder = navigate(root, "management", "tracing", "sampling", "probability");
            assertThat(probabilityPlaceholder)
                .as("management.tracing.sampling.probability")
                .isEqualTo("${TRACING_SAMPLING_PROBABILITY:0.1}");
        }
    }

    @SuppressWarnings("unchecked")
    private static String navigate(Map<String, Object> root, String... path) {
        Object current = root;
        for (String key : path) {
            assertThat(current).isInstanceOf(Map.class);
            current = ((Map<String, Object>) current).get(key);
        }
        return String.valueOf(current);
    }
}
