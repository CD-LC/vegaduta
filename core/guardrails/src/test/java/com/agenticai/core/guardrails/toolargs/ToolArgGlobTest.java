package com.agenticai.core.guardrails.toolargs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolArgGlobTest {

    @Test
    void exactNamesAndWildcards() {
        assertTrue(ToolArgGlob.matches("fetch_web_page", "fetch_web_page"));
        assertFalse(ToolArgGlob.matches("fetch_web_page", "fetch_web_pages"));
        assertTrue(ToolArgGlob.matches("*", "anything"));
        assertTrue(ToolArgGlob.matches("*", ""));
        assertTrue(ToolArgGlob.matches("mcp__files__*", "mcp__files__read"));
        assertTrue(ToolArgGlob.matches("mcp__files__*", "mcp__files__"));
        assertFalse(ToolArgGlob.matches("mcp__files__*", "mcp__other__read"));
        assertTrue(ToolArgGlob.matches("*_send", "email_send"));
        assertFalse(ToolArgGlob.matches("*_send", "email_send_all"));
        assertTrue(ToolArgGlob.matches("a*b*c", "aXXbYYc"));
        assertFalse(ToolArgGlob.matches("a*b*c", "aXXbYY"));
        assertTrue(ToolArgGlob.matches("t?", "t1"));
        assertFalse(ToolArgGlob.matches("t?", "t"));
        assertFalse(ToolArgGlob.matches("t?", "t12"));
    }

    @Test
    void isCaseSensitiveAndNullSafe() {
        assertFalse(ToolArgGlob.matches("Tool", "tool"));
        assertFalse(ToolArgGlob.matches(null, "x"));
        assertFalse(ToolArgGlob.matches("x", null));
    }

    @Test
    void aHostilePatternCannotBlowUp() {
        String pattern = "*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*b";
        String name = "a".repeat(5_000);
        long start = System.nanoTime();
        assertFalse(ToolArgGlob.matches(pattern, name));
        assertTrue((System.nanoTime() - start) / 1_000_000L < 5_000);
    }
}
