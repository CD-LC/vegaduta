package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Pins the reliability-wave defaults so they cannot rot: the circuit-breaker switch is ON, the
 * bulkhead switch is OFF, and the values that mirror what Spring/Lettuce/JavaMail did before are
 * the ones they did. Parses application.yml directly (no Spring context), like {@link
 * SwitchedOnControlsDefaultsTest}.
 */
class ResilienceDefaultsTest {

    @SuppressWarnings("unchecked")
    private static String get(String resource, String... path) throws Exception {
        try (InputStream in = ResilienceDefaultsTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the test classpath").isNotNull();
            Object current = new Yaml().load(in);
            for (String key : path) {
                assertThat(current).as("path segment " + key).isInstanceOf(Map.class);
                current = ((Map<String, Object>) current).get(key);
            }
            return String.valueOf(current);
        }
    }

    private static String app(String... path) throws Exception {
        return get("/application.yml", path);
    }

    @Test
    void circuitBreakersAreOnAndBulkheadsAreOffByDefault() throws Exception {
        // Breakers ship ON: every call site already excludes client 4xx / input faults from failure
        // counting.
        assertThat(app("app", "resilience", "circuit-breaker", "enabled"))
                .isEqualTo("${RESILIENCE_CB_ENABLED:true}");
        // Bulkheads stay OFF until hosted-LLM calls are actually routed through a bulkhead guard
        // (today they only take a breaker permit, so a limit would be a silent no-op).
        assertThat(app("app", "resilience", "bulkhead", "enabled"))
                .isEqualTo("${RESILIENCE_BULKHEAD_ENABLED:false}");
    }

    @Test
    void breakerTuningDefaultsMatchTheDocumentedOnes() throws Exception {
        assertThat(app("app", "resilience", "circuit-breaker", "consecutive-failure-threshold"))
                .isEqualTo("${RESILIENCE_CB_CONSECUTIVE_FAILURES:5}");
        assertThat(app("app", "resilience", "circuit-breaker", "failure-rate-percent"))
                .isEqualTo("${RESILIENCE_CB_FAILURE_RATE_PERCENT:50}");
        assertThat(app("app", "resilience", "circuit-breaker", "open-duration"))
                .isEqualTo("${RESILIENCE_CB_OPEN_DURATION:30s}");
        assertThat(app("app", "resilience", "circuit-breaker", "half-open-permitted-calls"))
                .isEqualTo("${RESILIENCE_CB_HALF_OPEN_CALLS:2}");
    }

    @Test
    void hostedLlmProbeTimeoutOutlastsAFirstTokenWait() throws Exception {
        // A hosted-LLM probe settles only at the first token; the global 30s would reclaim the slot
        // of
        // a slow reasoning model and drop its real outcome as stale. Keep the family override >=
        // 120s.
        assertThat(
                        app(
                                "app",
                                "resilience",
                                "circuit-breaker",
                                "instances",
                                "hosted-llm",
                                "half-open-probe-timeout"))
                .isEqualTo("${RESILIENCE_CB_HOSTED_LLM_HALF_OPEN_PROBE_TIMEOUT:180s}");
    }

    @Test
    void gracefulShutdownDefaultsKeepTheDocumentedDrainBudget() throws Exception {
        // Graceful by default with a 45s per-phase timeout (Helm core: 10s preStop + 45s < 90s
        // grace).
        // drain-seconds feeds the phase timeout (and ShutdownDrainAdvisor); SHUTDOWN_PHASE_TIMEOUT
        // still
        // overrides it directly for deployments that already set it.
        assertThat(app("server", "shutdown")).isEqualTo("${APP_SHUTDOWN_MODE:graceful}");
        assertThat(app("app", "shutdown", "drain-seconds"))
                .isEqualTo("${APP_SHUTDOWN_DRAIN_SECONDS:45}");
        assertThat(app("spring", "lifecycle", "timeout-per-shutdown-phase"))
                .isEqualTo("${SHUTDOWN_PHASE_TIMEOUT:${app.shutdown.drain-seconds}s}");
        assertThat(app("app", "shutdown", "container-grace-seconds"))
                .isEqualTo("${APP_SHUTDOWN_CONTAINER_GRACE_SECONDS:0}");
    }

    @Test
    void redisCommandTimeoutIsBoundedWellBelowLettucesOwn60Seconds() throws Exception {
        // Core issues no blocking Redis commands, so a 5s bound is safe; a black-holed Redis must
        // not
        // park request threads for Lettuce's 60s default.
        assertThat(app("spring", "data", "redis", "timeout")).isEqualTo("${REDIS_TIMEOUT:5s}");
    }

    @Test
    void llmStreamingReadTimeoutsAreStillPerChunkIdleNotTotal() throws Exception {
        // The LLM clients must never get a short TOTAL read timeout (a reasoning model legitimately
        // streams for minutes); the bound that matters mid-stream is the idle gap between tokens.
        assertThat(app("app", "llm", "http", "read-timeout"))
                .isEqualTo("${LLM_HTTP_READ_TIMEOUT:10m}");
        assertThat(app("app", "llm", "http", "stream-idle-timeout"))
                .isEqualTo("${LLM_HTTP_STREAM_IDLE_TIMEOUT:90s}");
    }

    @Test
    void hcaptchaNoLongerHasAnUnboundedClient() throws Exception {
        assertThat(app("app", "http", "hcaptcha", "connect-timeout"))
                .isEqualTo("${HCAPTCHA_CONNECT_TIMEOUT:3s}");
        assertThat(app("app", "http", "hcaptcha", "read-timeout"))
                .isEqualTo("${HCAPTCHA_READ_TIMEOUT:5s}");
    }

    @Test
    void smtpHasBoundedTimeoutsInsteadOfJavaMailsInfiniteDefaults() throws Exception {
        assertThat(
                        get(
                                "/application-workflow.yml",
                                "spring",
                                "mail",
                                "properties",
                                "mail",
                                "smtp",
                                "connectiontimeout"))
                .isEqualTo("${SMTP_CONNECT_TIMEOUT_MS:15000}");
        assertThat(
                        get(
                                "/application-workflow.yml",
                                "spring",
                                "mail",
                                "properties",
                                "mail",
                                "smtp",
                                "timeout"))
                .isEqualTo("${SMTP_READ_TIMEOUT_MS:60000}");
        assertThat(
                        get(
                                "/application-workflow.yml",
                                "spring",
                                "mail",
                                "properties",
                                "mail",
                                "smtp",
                                "writetimeout"))
                .isEqualTo("${SMTP_WRITE_TIMEOUT_MS:60000}");
    }
}
