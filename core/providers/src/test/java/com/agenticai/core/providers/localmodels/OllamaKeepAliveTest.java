package com.agenticai.core.providers.localmodels;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Ollama takes keep_alive as a number of seconds or a duration with a unit; "-1" as text is a 400. */
class OllamaKeepAliveTest {

    @Test
    void numbersGoOutAsNumbers() {
        assertThat(OllamaKeepAlive.jsonValue("-1")).isEqualTo(-1L);
        assertThat(OllamaKeepAlive.jsonValue(" 0 ")).isEqualTo(0L);
        assertThat(OllamaKeepAlive.jsonValue("300")).isEqualTo(300L);
        assertThat(OllamaKeepAlive.jsonValue("1.5")).isEqualTo(1.5d);
    }

    @Test
    void durationsStayText() {
        assertThat(OllamaKeepAlive.jsonValue("30m")).isEqualTo("30m");
        assertThat(OllamaKeepAlive.jsonValue(" 24h ")).isEqualTo("24h");
    }

    @Test
    void blankMeansSendNothing() {
        assertThat(OllamaKeepAlive.jsonValue(null)).isNull();
        assertThat(OllamaKeepAlive.jsonValue("  ")).isNull();
    }
}
