package com.agenticai.core.quality.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link EvalRunner#parseTimeout} - the one piece of the LLM HTTP timeout
 * wiring with real branching. The builders themselves are exercised only by a
 * billed run; what matters offline is that no env value can resolve to
 * "no timeout" and that core/app's duration formats mean the same thing here.
 */
class EvalRunnerHttpTimeoutTest {

    private static final Duration FALLBACK = Duration.ofSeconds(42);

    @Test
    void unsetOrBlankFallsBack() {
        assertThat(EvalLlmHttp.parseTimeout(null, FALLBACK)).isEqualTo(FALLBACK);
        assertThat(EvalLlmHttp.parseTimeout("   ", FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    void acceptsTheSameFormatsAsSpringBootProperties() {
        assertThat(EvalLlmHttp.parseTimeout("10s", FALLBACK)).isEqualTo(Duration.ofSeconds(10));
        assertThat(EvalLlmHttp.parseTimeout("10m", FALLBACK)).isEqualTo(Duration.ofMinutes(10));
        assertThat(EvalLlmHttp.parseTimeout(" PT30S ", FALLBACK)).isEqualTo(Duration.ofSeconds(30));
        assertThat(EvalLlmHttp.parseTimeout("1500", FALLBACK)).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    void nonPositiveNeverMeansNoTimeout() {
        assertThat(EvalLlmHttp.parseTimeout("0s", FALLBACK)).isEqualTo(FALLBACK);
        assertThat(EvalLlmHttp.parseTimeout("-5s", FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    void garbageFallsBackInsteadOfThrowing() {
        assertThat(EvalLlmHttp.parseTimeout("ten minutes", FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    void defaultsMatchCoreAppLlmHttpDefaults() {
        // application.yml: app.llm.http.connect-timeout ${LLM_HTTP_CONNECT_TIMEOUT:10s},
        // read-timeout ${LLM_HTTP_READ_TIMEOUT:10m}.
        assertThat(EvalLlmHttp.DEFAULT_LLM_CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
        assertThat(EvalLlmHttp.DEFAULT_LLM_READ_TIMEOUT).isEqualTo(Duration.ofMinutes(10));
    }
}
