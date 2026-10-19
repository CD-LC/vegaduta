package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.tools.classification.McpAnnotationClassifier;
import com.agenticai.core.agents.tools.classification.McpToolHints;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

/**
 * Hands the {@code annotations} of a freshly discovered MCP tool list to {@link
 * McpAnnotationClassifier}, keyed by the final namespaced tool name (see {@link
 * NamespacedMcpToolCallback#finalToolName}). Called from the discovery paths that already hold the
 * {@code McpSchema.Tool} records - no extra round trip. Never throws: hint capture is best-effort
 * and a failure just leaves the tools unannotated, which is today's behaviour.
 */
final class McpAnnotationCapture {

    private static final Logger log = LoggerFactory.getLogger(McpAnnotationCapture.class);

    private McpAnnotationCapture() {}

    /**
     * @param classifier nullable; null or disabled makes this a no-op
     * @param scope the tenant id, or {@link McpAnnotationClassifier#GLOBAL_SCOPE} for a static
     *     server
     * @param serverName the registration / connection name the namespacing uses
     * @param tools the server's full current tool list; null leaves what is recorded untouched
     */
    static void capture(
            McpAnnotationClassifier classifier,
            String scope,
            String serverName,
            List<McpSchema.Tool> tools) {
        if (classifier == null || !classifier.isEnabled() || scope == null || tools == null) {
            return;
        }
        try {
            Map<String, McpToolHints> byName = new LinkedHashMap<>();
            for (McpSchema.Tool tool : tools) {
                if (tool == null || tool.name() == null) {
                    continue;
                }
                byName.put(
                        NamespacedMcpToolCallback.finalToolName(serverName, tool.name()),
                        McpToolHints.from(tool.annotations()));
            }
            classifier.replaceServer(
                    scope, NamespacedMcpToolCallback.serverPrefix(serverName), byName);
        } catch (RuntimeException e) {
            log.debug("Could not record MCP tool annotations for server '{}'", serverName, e);
        }
    }

    /**
     * Drops the hints of the tools a disconnecting registration had exposed, so a stale claim
     * cannot outlive its connection and attach to a later tool of the same final name. Never
     * throws.
     *
     * @param classifier nullable; null or disabled makes this a no-op
     * @param scope the tenant id
     * @param removed the registration's live callbacks (nullable); their definitions carry the
     *     final names
     */
    static void forget(
            McpAnnotationClassifier classifier, String scope, List<ToolCallback> removed) {
        if (classifier == null || !classifier.isEnabled() || scope == null || removed == null) {
            return;
        }
        try {
            List<String> names = new ArrayList<>();
            for (ToolCallback callback : removed) {
                if (callback != null && callback.getToolDefinition() != null) {
                    names.add(callback.getToolDefinition().name());
                }
            }
            classifier.forgetTools(scope, names);
        } catch (RuntimeException e) {
            log.debug("Could not drop MCP tool annotations for scope '{}'", scope, e);
        }
    }
}
