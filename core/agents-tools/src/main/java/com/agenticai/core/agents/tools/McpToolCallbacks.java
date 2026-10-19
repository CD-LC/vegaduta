package com.agenticai.core.agents.tools;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;

/**
 * The one place MCP tool callbacks are discovered from a client (ADR-0012 P1b). Replaces the
 * deprecated {@code new SyncMcpToolCallbackProvider(client)}: the builder is used with the
 * deprecated constructor's exact semantics - accept every tool and NO name prefix (the builder's
 * own default would be a prefix generator, which would rename every tool), because namespacing is
 * done by {@code NamespacedMcpToolCallback}.
 */
final class McpToolCallbacks {

    private McpToolCallbacks() {}

    static ToolCallback[] discover(McpSyncClient client) {
        return SyncMcpToolCallbackProvider.builder()
                .mcpClients(client)
                .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                .build()
                .getToolCallbacks();
    }
}
