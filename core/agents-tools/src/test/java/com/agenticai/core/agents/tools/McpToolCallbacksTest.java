package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * Pins the semantics of the deprecated {@code new SyncMcpToolCallbackProvider(client)} that
 * McpToolCallbacks replaced.
 */
class McpToolCallbacksTest {

    @Test
    void discoversEveryToolWithItsOwnUnprefixedName() {
        McpSyncClient client = StaticMcpExposurePolicyTest.client("docs");
        McpSchema.JsonSchema schema =
                new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null);
        when(client.listTools())
                .thenReturn(
                        new McpSchema.ListToolsResult(
                                List.of(
                                        McpSchema.Tool.builder()
                                                .name("search_docs")
                                                .description("d1")
                                                .inputSchema(schema)
                                                .build(),
                                        McpSchema.Tool.builder()
                                                .name("get_page")
                                                .description("d2")
                                                .inputSchema(schema)
                                                .build()),
                                null));

        ToolCallback[] callbacks = McpToolCallbacks.discover(client);

        assertEquals(2, callbacks.length);
        assertEquals("search_docs", callbacks[0].getToolDefinition().name());
        assertEquals("get_page", callbacks[1].getToolDefinition().name());
    }
}
