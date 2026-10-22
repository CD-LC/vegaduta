package com.agenticai.core.agents.tools.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.context.ToolResultRestoreIds;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code restore_tool_result} reads the run record back - and only THIS run's: the agent and
 * session are bound at construction and are the sole scope of every lookup.
 */
class RestoreToolResultToolCallbackTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final String SESSION = "session-1";

    private final ToolCallLogService logs = mock(ToolCallLogService.class);
    private final RestoreToolResultToolCallback tool =
            new RestoreToolResultToolCallback(logs, AGENT, SESSION);

    private static ToolCallLog row(
            UUID agent,
            String session,
            String toolName,
            String input,
            String output,
            String error) {
        Instant now = Instant.now();
        return new ToolCallLog(agent, session, toolName, input, output, error, now, now);
    }

    private static String stepId(String toolName, String input) {
        return ToolResultRestoreIds.stepId(toolName, input);
    }

    private static String args(String stepId) {
        return "{\"stepId\":\"" + stepId + "\"}";
    }

    @Test
    void restoresTheNewestCallOfTheToolWhoseArgumentsMatchTheDigest() {
        ToolCallLog other = row(AGENT, SESSION, "search", "{\"q\":\"a\"}", "RESULT-A", null);
        ToolCallLog wanted = row(AGENT, SESSION, "search", "{\"q\":\"b\"}", "RESULT-B", null);
        when(logs.findRecentByAgentIdAndSessionIdAndToolName(AGENT, SESSION, "search"))
                .thenReturn(List.of(wanted, other));

        String restored = tool.call(args(stepId("search", "{\"q\":\"a\"}")));

        assertEquals("RESULT-A", restored);
    }

    @Test
    void theDigestSurvivesWhitespaceAndKeyOrderChangesBetweenTheCallAndTheLog() {
        ToolCallLog logged =
                row(AGENT, SESSION, "search", "{ \"b\": 2,  \"a\": 1 }", "RESULT", null);
        when(logs.findRecentByAgentIdAndSessionIdAndToolName(AGENT, SESSION, "search"))
                .thenReturn(List.of(logged));

        String restored = tool.call(args(stepId("search", "{\"a\":1,\"b\":2}")));

        assertEquals("RESULT", restored);
    }

    @Test
    void whenTheSameCallRanTwiceTheNewestRowWins() {
        ToolCallLog newest = row(AGENT, SESSION, "search", "{}", "NEW", null);
        ToolCallLog older = row(AGENT, SESSION, "search", "{}", "OLD", null);
        when(logs.findRecentByAgentIdAndSessionIdAndToolName(AGENT, SESSION, "search"))
                .thenReturn(List.of(newest, older));

        assertEquals("NEW", tool.call(args(stepId("search", "{}"))));
    }

    @Test
    void aLogIdIsResolvedWithinThisAgentAndSessionOnly() {
        UUID id = UUID.randomUUID();
        when(logs.findByIdAndAgentIdAndSessionId(id, AGENT, SESSION))
                .thenReturn(Optional.of(row(AGENT, SESSION, "search", "{}", "BY-ID", null)));

        assertEquals("BY-ID", tool.call(args(id.toString())));

        verify(logs).findByIdAndAgentIdAndSessionId(id, AGENT, SESSION);
        verifyNoMoreInteractions(logs);
    }

    @Test
    void aRowOfAnotherSessionIsNeverReachedBecauseTheQueryIsBoundToThisRun() {
        UUID id = UUID.randomUUID();
        // The repository answers for the bound (agent, session) only; for any other run it finds
        // nothing.
        when(logs.findByIdAndAgentIdAndSessionId(any(), any(), any())).thenReturn(Optional.empty());

        String reply = tool.call(args(id.toString()));

        assertTrue(reply.contains("No stored result"), reply);
        verify(logs).findByIdAndAgentIdAndSessionId(eq(id), eq(AGENT), eq(SESSION));
        verifyNoMoreInteractions(logs);
    }

    @Test
    void aForeignRowThatSlipsThroughIsRefusedByTheDefenceInDepthCheck() {
        UUID otherAgent = UUID.randomUUID();
        ToolCallLog foreignAgent =
                row(otherAgent, SESSION, "search", "{}", "SECRET-OF-ANOTHER-AGENT", null);
        ToolCallLog foreignSession =
                row(AGENT, "someone-elses-session", "search", "{}", "SECRET-OF-ANOTHER-RUN", null);
        when(logs.findRecentByAgentIdAndSessionIdAndToolName(AGENT, SESSION, "search"))
                .thenReturn(List.of(foreignAgent, foreignSession));
        UUID id = UUID.randomUUID();
        when(logs.findByIdAndAgentIdAndSessionId(id, AGENT, SESSION))
                .thenReturn(Optional.of(foreignAgent));

        String byDigest = tool.call(args(stepId("search", "{}")));
        String byId = tool.call(args(id.toString()));

        assertFalse(byDigest.contains("SECRET"), byDigest);
        assertFalse(byId.contains("SECRET"), byId);
        assertTrue(byDigest.contains("No stored result"));
        assertTrue(byId.contains("No stored result"));
    }

    @Test
    void aRowOfADifferentToolIsNotReturnedForThisStepId() {
        ToolCallLog differentTool = row(AGENT, SESSION, "other_tool", "{}", "NOT-THIS", null);
        when(logs.findRecentByAgentIdAndSessionIdAndToolName(AGENT, SESSION, "search"))
                .thenReturn(List.of(differentTool));

        assertTrue(tool.call(args(stepId("search", "{}"))).contains("No stored result"));
    }

    @Test
    void aFailedCallIsRestoredAsItsError() {
        ToolCallLog failed = row(AGENT, SESSION, "search", "{}", null, "upstream 503");
        when(logs.findRecentByAgentIdAndSessionIdAndToolName(AGENT, SESSION, "search"))
                .thenReturn(List.of(failed));

        String restored = tool.call(args(stepId("search", "{}")));

        assertTrue(restored.contains("failed"), restored);
        assertTrue(restored.contains("upstream 503"), restored);
    }

    @Test
    void aHugeStoredOutputIsCappedWithATruncationMarker() {
        String big = "z".repeat(RestoreToolResultToolCallback.MAX_RESULT_CHARS + 500);
        ToolCallLog logged = row(AGENT, SESSION, "search", "{}", big, null);
        when(logs.findRecentByAgentIdAndSessionIdAndToolName(AGENT, SESSION, "search"))
                .thenReturn(List.of(logged));

        String restored = tool.call(args(stepId("search", "{}")));

        assertTrue(restored.length() < big.length());
        assertTrue(
                restored.contains("[truncated 500 chars]"),
                restored.substring(restored.length() - 60));
    }

    @Test
    void aMalformedStepIdGetsAHelpfulSentenceAndNoLookup() {
        String reply = tool.call(args("not-a-pointer"));

        assertTrue(reply.contains("toolName#digest"), reply);
        verifyNoInteractions(logs);
    }

    @Test
    void invalidJsonInputNeverThrows() {
        assertDoesNotThrow(() -> tool.call("{not json"));
        assertTrue(tool.call("{not json").contains("not valid JSON"));
        assertDoesNotThrow(() -> tool.call(null));
        verify(logs, never()).findRecentByAgentIdAndSessionIdAndToolName(any(), any(), any());
    }

    @Test
    void aFailingLookupDegradesToAnActionableSentence() {
        when(logs.findRecentByAgentIdAndSessionIdAndToolName(any(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        String reply = assertDoesNotThrow(() -> tool.call(args(stepId("search", "{}"))));

        assertTrue(reply.contains("Run the tool again"), reply);
    }

    @Test
    void theToolDefinitionIsNamedAndHasAValidInputSchema() throws Exception {
        assertEquals("restore_tool_result", tool.getToolDefinition().name());
        assertEquals(
                "object",
                com.agenticai.core.json.JsonMappers.plain()
                        .readTree(tool.getToolDefinition().inputSchema())
                        .path("type")
                        .asString());
    }
}
