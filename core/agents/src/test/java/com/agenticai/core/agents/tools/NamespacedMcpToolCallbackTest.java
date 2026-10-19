package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Pentest L4 - dynamic MCP tool namespacing. Confirms the {@code mcp__<server>__<tool>} prefix is
 * applied to the model-facing name only, that invocation is left completely alone (the whole point
 * - the real MCP call still needs the delegate's original, unprefixed name), and that a hostile or
 * merely careless operator-typed server label can't produce a broken/oversized/blank tool name.
 */
class NamespacedMcpToolCallbackTest {

    private static ToolCallback fakeDelegate(String name, String description, String inputSchema) {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition())
                .thenReturn(new DefaultToolDefinition(name, description, inputSchema));
        return delegate;
    }

    @Test
    void prefixesTheModelFacingNameWithServerAndDoubleUnderscoreSeparators() {
        ToolCallback delegate = fakeDelegate("search", "Searches things.", "{}");
        NamespacedMcpToolCallback wrapped =
                new NamespacedMcpToolCallback(delegate, "weatherServer");
        assertEquals("mcp__weatherServer__search", wrapped.getToolDefinition().name());
    }

    @Test
    void descriptionAndInputSchemaPassThroughUnchanged() {
        ToolCallback delegate = fakeDelegate("search", "Searches things.", "{\"type\":\"object\"}");
        NamespacedMcpToolCallback wrapped = new NamespacedMcpToolCallback(delegate, "srv");
        ToolDefinition def = wrapped.getToolDefinition();
        assertEquals("Searches things.", def.description());
        assertEquals("{\"type\":\"object\"}", def.inputSchema());
    }

    @Test
    void callDelegatesUnchangedToTheOriginalToolNotThePrefixedOne() {
        // This is the crux of the fix's safety: the wrapper must NEVER need the
        // MCP server to recognize the prefixed name - the real invocation
        // (inside `delegate`, e.g. Spring AI's SyncMcpToolCallback) still keys
        // off whatever original tool name it was built with.
        ToolCallback delegate = fakeDelegate("search", "d", "{}");
        when(delegate.call("{\"q\":\"x\"}")).thenReturn("result-a");
        when(delegate.call("{\"q\":\"y\"}", null)).thenReturn("result-b");
        NamespacedMcpToolCallback wrapped = new NamespacedMcpToolCallback(delegate, "srv");

        assertEquals("result-a", wrapped.call("{\"q\":\"x\"}"));
        assertEquals("result-b", wrapped.call("{\"q\":\"y\"}", null));
        verify(delegate).call("{\"q\":\"x\"}");
        verify(delegate).call("{\"q\":\"y\"}", null);
    }

    @Test
    void toolContextIsForwardedUnchanged() {
        ToolCallback delegate = fakeDelegate("search", "d", "{}");
        ToolContext context = new ToolContext(java.util.Map.of("k", "v"));
        when(delegate.call("in", context)).thenReturn("out");
        NamespacedMcpToolCallback wrapped = new NamespacedMcpToolCallback(delegate, "srv");

        assertEquals("out", wrapped.call("in", context));
    }

    @Test
    void freeformServerLabelsWithSpacesAndPunctuationAreSanitizedToASafeIdentifier() {
        ToolCallback delegate = fakeDelegate("search", "d", "{}");
        NamespacedMcpToolCallback wrapped =
                new NamespacedMcpToolCallback(delegate, "My Cool Server! (v2)");
        assertEquals("mcp__My_Cool_Server_v2__search", wrapped.getToolDefinition().name());
    }

    @Test
    void controlAndAnsiCharactersInTheServerLabelAreStripped() {
        String esc = String.valueOf((char) 0x1B);
        ToolCallback delegate = fakeDelegate("search", "d", "{}");
        NamespacedMcpToolCallback wrapped =
                new NamespacedMcpToolCallback(delegate, "evil" + esc + "[31mred" + esc + "[0m");
        String name = wrapped.getToolDefinition().name();
        assertTrue(name.startsWith("mcp__evilred"), "expected ansi-stripped segment, got: " + name);
        assertTrue(name.endsWith("__search"));
    }

    @Test
    void blankOrAllInvisibleServerLabelFallsBackToServer() {
        ToolCallback delegate = fakeDelegate("search", "d", "{}");
        assertEquals(
                "mcp__server__search",
                new NamespacedMcpToolCallback(delegate, "").getToolDefinition().name());
        assertEquals(
                "mcp__server__search",
                new NamespacedMcpToolCallback(delegate, "   ").getToolDefinition().name());
        assertEquals(
                "mcp__server__search",
                new NamespacedMcpToolCallback(delegate, null).getToolDefinition().name());
    }

    @Test
    void veryLongServerLabelIsClampedSoItCannotEatTheWholeNameBudget() {
        ToolCallback delegate = fakeDelegate("search", "d", "{}");
        String longLabel = "s".repeat(500);
        NamespacedMcpToolCallback wrapped = new NamespacedMcpToolCallback(delegate, longLabel);
        String name = wrapped.getToolDefinition().name();
        assertTrue(name.length() <= McpToolOutputSanitizer.MAX_NAME_CHARS);
        assertTrue(
                name.endsWith("__search"),
                "tool name segment must survive even with a very long server label: " + name);
    }

    @Test
    void combinedNameNeverExceedsTheOverallNameCap() {
        // An already-clamped (by SanitizedMcpToolCallback) tool name can still be
        // close to MAX_NAME_CHARS on its own; adding the mcp__<server>__ prefix
        // must never push the final name over the cap silently truncating in an
        // undefined way - it truncates the COMBINED string deterministically.
        ToolCallback delegate =
                fakeDelegate("t".repeat(McpToolOutputSanitizer.MAX_NAME_CHARS), "d", "{}");
        NamespacedMcpToolCallback wrapped = new NamespacedMcpToolCallback(delegate, "someserver");
        assertEquals(
                McpToolOutputSanitizer.MAX_NAME_CHARS, wrapped.getToolDefinition().name().length());
    }

    @Test
    void toolMetadataDelegatesToTheOriginal() {
        ToolCallback delegate = fakeDelegate("search", "d", "{}");
        org.springframework.ai.tool.metadata.ToolMetadata metadata =
                org.springframework.ai.tool.metadata.ToolMetadata.builder()
                        .returnDirect(true)
                        .build();
        when(delegate.getToolMetadata()).thenReturn(metadata);
        NamespacedMcpToolCallback wrapped = new NamespacedMcpToolCallback(delegate, "srv");
        assertEquals(metadata, wrapped.getToolMetadata());
    }
}
