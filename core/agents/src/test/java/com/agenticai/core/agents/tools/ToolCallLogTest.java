package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link ToolCallLog} is the audit record {@code LoggingToolCallback} writes around every tool
 * call. These pin the three public constructor shapes - the short form defaults the
 * validation-failure flag and MCP grant attribution, and {@code agentVersionId} is set once after
 * construction from the turn's resolved pin.
 */
class ToolCallLogTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant COMPLETED = Instant.parse("2026-01-01T00:00:01Z");

    @Test
    void theShortConstructorDefaultsFlagAndGrant() {
        ToolCallLog log =
                new ToolCallLog(
                        AGENT,
                        "session-1",
                        "web_search",
                        "{\"q\":\"x\"}",
                        "result",
                        null,
                        STARTED,
                        COMPLETED);

        assertEquals(AGENT, log.getAgentId());
        assertEquals("session-1", log.getSessionId());
        assertEquals("web_search", log.getToolName());
        assertEquals("{\"q\":\"x\"}", log.getToolInput());
        assertEquals("result", log.getToolOutput());
        assertNull(log.getErrorMessage());
        assertEquals(STARTED, log.getStartedAt());
        assertEquals(COMPLETED, log.getCompletedAt());
        assertNull(log.getMcpGrantId());
        assertNull(log.getAgentVersionId());
    }

    @Test
    void theValidationFlagConstructorRecordsAFailedOutputCheck() {
        ToolCallLog log =
                new ToolCallLog(
                        AGENT,
                        "session-1",
                        "create_file",
                        "{}",
                        "rejected",
                        "schema error",
                        STARTED,
                        COMPLETED,
                        true);

        assertEquals("rejected", log.getToolOutput());
        assertEquals("schema error", log.getErrorMessage());
        assertNull(log.getMcpGrantId());
    }

    @Test
    void theFullConstructorCarriesTheMcpGrantAttribution() {
        UUID grant = UUID.randomUUID();

        ToolCallLog log =
                new ToolCallLog(
                        AGENT,
                        "session-1",
                        "send_email",
                        "{}",
                        null,
                        "boom",
                        STARTED,
                        COMPLETED,
                        false,
                        grant);

        assertEquals(grant, log.getMcpGrantId());
        assertNull(log.getToolOutput());
        assertEquals("boom", log.getErrorMessage());
    }

    @Test
    void agentVersionIdIsSetOnceFromTheTurnsPin() {
        ToolCallLog log =
                new ToolCallLog(AGENT, "session-1", "t", null, null, null, STARTED, COMPLETED);
        UUID version = UUID.randomUUID();

        log.setAgentVersionId(version);

        assertEquals(version, log.getAgentVersionId());
    }
}
