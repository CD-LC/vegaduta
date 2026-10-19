package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.tools.mcpapps.McpAppUiMeta;
import com.agenticai.core.agents.tools.mcpapps.McpAppsService;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands a freshly discovered MCP tool list to {@link McpAppsService} (MCP Apps, {@code
 * app.mcp.apps.enabled}), keyed by the final namespaced tool name exactly like {@link
 * McpAnnotationCapture}. Called from the discovery paths that already hold the {@code
 * McpSchema.Tool} records - no extra round trip. A null service (flag off, the default) makes every
 * method a no-op; none of them ever throws.
 */
final class McpAppsCapture {

    private static final Logger log = LoggerFactory.getLogger(McpAppsCapture.class);

    private McpAppsCapture() {}

    static void capture(
            McpAppsService apps,
            String tenant,
            UUID registrationId,
            String serverName,
            List<McpSchema.Tool> tools) {
        if (apps == null || tenant == null || registrationId == null || tools == null) {
            return;
        }
        try {
            List<McpAppsService.ToolEntry> entries = new ArrayList<>();
            for (McpSchema.Tool tool : tools) {
                if (tool == null || tool.name() == null) {
                    continue;
                }
                entries.add(
                        new McpAppsService.ToolEntry(
                                tool.name(),
                                NamespacedMcpToolCallback.finalToolName(serverName, tool.name()),
                                McpAppUiMeta.declarationOf(tool.meta()).orElse(null)));
            }
            apps.recordServer(tenant, registrationId, serverName, entries);
        } catch (RuntimeException e) {
            log.debug("Could not record MCP app declarations for server '{}'", serverName, e);
        }
    }

    static void forget(McpAppsService apps, String tenant, UUID registrationId) {
        if (apps == null) {
            return;
        }
        try {
            apps.forgetServer(tenant, registrationId);
        } catch (RuntimeException e) {
            log.debug("Could not drop MCP app state for registration {}", registrationId, e);
        }
    }
}
