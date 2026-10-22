package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.classification.McpAnnotationClassifier;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * With {@code app.tools.mcp-annotations.enabled} the static discovery goes through the provider
 * builder. The builder's default name generator rewrites '-' to '_' and strips other characters;
 * the legacy path ({@code new SyncMcpToolCallbackProvider(client)}) keeps the raw name. These tests
 * pin that the flag-on path names tools exactly like the flag-off path, so the captured hint key
 * and the callback name agree.
 */
class AgentMcpToolResolverStaticAnnotationNamesTest {

    private static McpSyncClient clientWithTool(
            String toolName, McpSchema.ToolAnnotations annotations) {
        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.Tool tool =
                new McpSchema.Tool(
                        toolName,
                        null,
                        "a tool",
                        Map.of("type", "object"),
                        null,
                        annotations,
                        null);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));
        // Spring AI 2's provider reads the connection details off the client.
        when(client.getClientCapabilities())
                .thenReturn(McpSchema.ClientCapabilities.builder().build());
        when(client.getClientInfo()).thenReturn(new McpSchema.Implementation("test", "1.0"));
        when(client.getCurrentInitializationResult())
                .thenReturn(mock(McpSchema.InitializeResult.class));
        return client;
    }

    @Test
    void flagOnKeepsRawHyphenatedAndDottedNamesAndCapturesHintsUnderTheMatchingKey() {
        McpAnnotationClassifier classifier = new McpAnnotationClassifier(true);
        McpSchema.ToolAnnotations destructive =
                new McpSchema.ToolAnnotations(null, false, true, null, null, null);
        McpSyncClient client = clientWithTool("delete-item.v2", destructive);

        ToolCallback[] on = AgentMcpToolResolver.fetchStaticCallbacks(client, "crm", classifier);
        ToolCallback[] off =
                AgentMcpToolResolver.fetchStaticCallbacks(
                        client, "crm", new McpAnnotationClassifier(false));

        assertEquals(1, on.length);
        assertEquals("delete-item.v2", on[0].getToolDefinition().name());
        assertEquals(
                off[0].getToolDefinition().name(),
                on[0].getToolDefinition().name(),
                "flag on must not rename tools relative to flag off");

        String finalName =
                NamespacedMcpToolCallback.finalToolName("crm", on[0].getToolDefinition().name());
        assertTrue(
                classifier.classify("any-tenant", finalName).isPresent(),
                "the hint must be found under the name the gate will ask about");
    }
}
