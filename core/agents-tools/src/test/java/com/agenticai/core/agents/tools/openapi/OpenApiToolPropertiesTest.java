package com.agenticai.core.agents.tools.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@code app.tools.openapi.*}: classpath defaults, clamping and Duration views. */
class OpenApiToolPropertiesTest {

    @Test
    void defaultsComeFromTheBundledPropertiesFile() {
        OpenApiToolProperties p = new OpenApiToolProperties();
        assertEquals(10, p.getConnectTimeoutSeconds());
        assertEquals(30, p.getRequestTimeoutSeconds());
        assertEquals(1_048_576, p.getMaxResponseBytes());
        assertEquals(16_000, p.getMaxResultChars());
        assertEquals(3, p.getMaxSpecRedirects());
    }

    @Test
    void settersClampToTheirFloor() {
        OpenApiToolProperties p = new OpenApiToolProperties();
        p.setConnectTimeoutSeconds(0);
        p.setRequestTimeoutSeconds(-5);
        p.setMaxResponseBytes(0);
        p.setMaxResultChars(-1);
        p.setMaxSpecRedirects(-1);

        assertEquals(1, p.getConnectTimeoutSeconds());
        assertEquals(1, p.getRequestTimeoutSeconds());
        assertEquals(1, p.getMaxResponseBytes());
        assertEquals(1, p.getMaxResultChars());
        assertEquals(0, p.getMaxSpecRedirects());
    }

    @Test
    void durationViewsMirrorTheSeconds() {
        OpenApiToolProperties p = new OpenApiToolProperties();
        p.setConnectTimeoutSeconds(7);
        p.setRequestTimeoutSeconds(9);
        assertEquals(Duration.ofSeconds(7), p.connectTimeout());
        assertEquals(Duration.ofSeconds(9), p.requestTimeout());
        assertTrue(p.connectTimeout().compareTo(p.requestTimeout()) < 0);
    }
}
