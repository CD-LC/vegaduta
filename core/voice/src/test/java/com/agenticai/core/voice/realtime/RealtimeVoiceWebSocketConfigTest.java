package com.agenticai.core.voice.realtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/** The relay is opened by a browser page, so its allowed origins come from configuration, never a hard-coded "*". */
class RealtimeVoiceWebSocketConfigTest {

    @Test
    void parseOriginsSplitsTrimsAndDropsBlanks() {
        assertArrayEquals(
            new String[] {"https://app.example.com", "http://localhost:3000"},
            RealtimeVoiceWebSocketConfig.parseOrigins(" https://app.example.com , ,http://localhost:3000 ,"));
    }

    @Test
    void emptyOrMissingMeansSameOriginOnly() {
        assertArrayEquals(new String[0], RealtimeVoiceWebSocketConfig.parseOrigins(""));
        assertArrayEquals(new String[0], RealtimeVoiceWebSocketConfig.parseOrigins("  "));
        assertArrayEquals(new String[0], RealtimeVoiceWebSocketConfig.parseOrigins(null));
    }

    @Test
    void anExplicitStarIsPreservedAsTheOperatorsEscapeHatch() {
        assertArrayEquals(new String[] {"*"}, RealtimeVoiceWebSocketConfig.parseOrigins("*"));
    }
}
