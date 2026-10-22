package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import tools.jackson.databind.JsonNode;

/**
 * What a failed tool call hands back to the model: never a credential, never an unbounded stack of
 * text, always a JSON object (Gemini replays tool results as JSON objects and kills the turn
 * otherwise).
 */
class RedactingToolExecutionExceptionProcessorTest {

    private static final ToolDefinition DEFINITION =
            ToolDefinition.builder()
                    .name("crm_lookup")
                    .description("stub")
                    .inputSchema("{\"type\": \"object\", \"properties\": {}}")
                    .build();

    private final RedactingToolExecutionExceptionProcessor processor =
            new RedactingToolExecutionExceptionProcessor();

    @Test
    void credentialShapedTextIsScrubbedBeforeItReachesTheModel() {
        String leaky =
                "GET https://api.example.com/v1/items?api_key=abcdef1234567890 failed; "
                        + "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlMTIz; "
                        + "retry with sk-proj-ABCDEFGHIJKLMNOPQRSTUVWX and postgres://admin:hunter22@db:5432/app";

        String result =
                processor.process(
                        new ToolExecutionException(DEFINITION, new IllegalStateException(leaky)));

        assertFalse(result.contains("abcdef1234567890"), result);
        assertFalse(result.contains("eyJhbGciOiJIUzI1NiJ9"), result);
        assertFalse(result.contains("sk-proj-ABCDEFGHIJKLMNOPQRSTUVWX"), result);
        assertFalse(result.contains("hunter22"), result);
        assertTrue(
                result.contains("crm_lookup"),
                "the model should know which tool failed: " + result);
    }

    @Test
    void resultIsAJsonObjectAndLengthIsCapped() throws Exception {
        String huge = "x".repeat(10_000);

        String result =
                processor.process(
                        new ToolExecutionException(DEFINITION, new RuntimeException(huge)));

        JsonNode node = com.agenticai.core.json.JsonMappers.plain().readTree(result);
        assertTrue(node.isObject());
        assertTrue(
                result.length() < 1_000,
                "detail must be capped, got " + result.length() + " chars");
    }

    @Test
    void aMessagelessExceptionStillNamesItsType() {
        String result =
                processor.process(
                        new ToolExecutionException(DEFINITION, new NullPointerException()));

        assertTrue(result.contains("NullPointerException"), result);
    }

    @Test
    void anErrorIsNeverTurnedIntoAToolResult() {
        OutOfMemoryError oom = new OutOfMemoryError("heap");

        OutOfMemoryError thrown =
                assertThrows(
                        OutOfMemoryError.class,
                        () -> processor.process(new ToolExecutionException(DEFINITION, oom)));
        assertSame(oom, thrown);
    }

    @Test
    void ordinaryExceptionsAreNotPropagated() {
        assertFalse(
                RedactingToolExecutionExceptionProcessor.isPropagated(
                        new IllegalStateException("x")));
        assertFalse(RedactingToolExecutionExceptionProcessor.isPropagated(new OutOfMemoryError()));
    }

    @Test
    void redactLeavesOrdinaryTextAlone() {
        String plain = "Connection not found: SCRUM";
        assertEquals(plain, RedactingToolExecutionExceptionProcessor.redact(plain));
    }
}
