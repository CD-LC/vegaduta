package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Spring Boot 4's OpenTelemetry starter brings the OTLP metrics registry, which is on by default
 * and pushes every meter once a minute to {@code http://localhost:4318/v1/metrics}. Boot 3.5 had no
 * such registry here (metrics are scraped from /actuator/prometheus), so the shipped default stays
 * off; an operator opts in with {@code OTLP_METRICS_EXPORT_ENABLED=true}. Parses application.yml
 * directly (no Spring context), like {@link TracingSamplingDefaultTest}.
 */
class OtlpMetricsExportDefaultTest {

    @Test
    void otlpMetricsExportIsOffUnlessAnOperatorTurnsItOn() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            assertThat(in).as("application.yml should be on the test classpath").isNotNull();
            Map<String, Object> root = new Yaml().load(in);
            Object current = root;
            for (String key : new String[] {"management", "otlp", "metrics", "export", "enabled"}) {
                assertThat(current).isInstanceOf(Map.class);
                current = ((Map<?, ?>) current).get(key);
            }
            assertThat(String.valueOf(current))
                    .as("management.otlp.metrics.export.enabled")
                    .isEqualTo("${OTLP_METRICS_EXPORT_ENABLED:false}");
        }
    }
}
