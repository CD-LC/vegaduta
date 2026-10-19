package com.agenticai.core.agents.job.output;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the one property of the first-turn instruction that matters for jobs that use tools: it asks for the JSON as
 * the FINAL answer, after the work, rather than as an immediate reply. "Respond with ONLY a JSON object" on its own
 * made models skip their tools and fill the schema with placeholders (a prod research run finished in 5s with
 * example.com sources and zero tool calls).
 */
class SchemaPromptRendererTest {

    private final SchemaPromptRenderer renderer = new SchemaPromptRenderer();
    private static final String SCHEMA = "{\"type\":\"object\"}";

    @Test
    void tellsTheModelToDoTheWorkAndUseItsToolsBeforeTheJsonAnswer() {
        String text = renderer.firstTurnInstruction(SCHEMA);
        assertTrue(text.contains("first"), text);
        assertTrue(text.contains("tools"), text);
        assertTrue(text.contains("FINAL"), text);
        assertTrue(text.indexOf("tools") < text.indexOf("JSON object"), "tools come before the JSON demand: " + text);
    }

    @Test
    void forbidsPlaceholderValuesAndStillDemandsABareObject() {
        String text = renderer.firstTurnInstruction(SCHEMA);
        assertTrue(text.contains("placeholder"), text);
        assertTrue(text.contains("no markdown code fence"), text);
        assertTrue(text.contains("raw object"), text);
    }

    @Test
    void endsWithTheSchemaItself() {
        assertTrue(renderer.firstTurnInstruction(SCHEMA).endsWith(SCHEMA));
        assertFalse(renderer.firstTurnInstruction(SCHEMA).startsWith("Respond with ONLY"));
    }
}
