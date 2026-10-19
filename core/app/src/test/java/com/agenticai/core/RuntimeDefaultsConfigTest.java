package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Runtime defaults made explicit by the standards audit (docs/engineering/STANDARDS-AUDIT.md,
 * "do now"): graceful shutdown, a bounded Redis command timeout and opt-in JSON logging. Parses
 * the shipped YAML directly (no Spring context) with duplicate keys rejected, the same as Spring
 * Boot's own loader, so a second {@code lifecycle:} or {@code timeout:} key fails here rather
 * than at boot.
 */
class RuntimeDefaultsConfigTest {

    @Test
    void baseConfigSetsShutdownRedisTimeoutAndLogFormatDefaults() throws Exception {
        Map<String, Object> root = load("/application.yml");
        assertThat(navigate(root, "server", "shutdown")).isEqualTo("${APP_SHUTDOWN_MODE:graceful}");
        assertThat(navigate(root, "spring", "lifecycle", "timeout-per-shutdown-phase"))
            .isEqualTo("${SHUTDOWN_PHASE_TIMEOUT:${app.shutdown.drain-seconds}s}");
        assertThat(navigate(root, "app", "shutdown", "drain-seconds")).isEqualTo("${APP_SHUTDOWN_DRAIN_SECONDS:45}");
        assertThat(navigate(root, "spring", "data", "redis", "timeout")).isEqualTo("${REDIS_TIMEOUT:5s}");
        // Empty default = the existing text pattern; Boot only switches encoders on a non-empty value.
        assertThat(navigate(root, "logging", "structured", "format", "console")).isEqualTo("${LOG_FORMAT:}");
        assertThat(navigate(root, "logging", "pattern", "console")).contains("%X{requestId:-}");
    }

    @Test
    void liteProfileKeepsItsTighterRedisTimeout() throws Exception {
        Map<String, Object> lite = load("/application-lite.yml");
        assertThat(navigate(lite, "spring", "data", "redis", "timeout")).isEqualTo("2s");
    }

    private Map<String, Object> load(String resource) throws Exception {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(10 * 1024 * 1024);
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as(resource + " should be on the test classpath").isNotNull();
            return new Yaml(options).load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static String navigate(Map<String, Object> root, String... path) {
        Object current = root;
        for (String key : path) {
            assertThat(current).as("parent of " + key).isInstanceOf(Map.class);
            current = ((Map<String, Object>) current).get(key);
        }
        return String.valueOf(current);
    }
}
