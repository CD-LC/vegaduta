package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.tools.mcpregistry.McpOwnershipGate;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Sender-side hook for routed stdio MCP calls (app.mcp.registry-route-tool-calls). Outermost
 * wrapper of a dynamic registration's callbacks: before running locally it asks the ownership gate
 * whether a peer replica owns the registration's client. The gate answers empty (run locally,
 * unchanged behaviour) unless the flag is on, this JVM holds no live client and a different replica
 * owns it, so with the flag off this class is a pure pass-through. A routed failure propagates and
 * is never retried locally (the tool may already have run).
 */
final class RoutedMcpToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final String tenant;
    private final UUID registrationId;
    private final Supplier<McpOwnershipGate> gate;

    RoutedMcpToolCallback(
            ToolCallback delegate,
            String tenant,
            UUID registrationId,
            Supplier<McpOwnershipGate> gate) {
        this.delegate = delegate;
        this.tenant = tenant;
        this.registrationId = registrationId;
        this.gate = gate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return gate.get()
                .routeToolCall(
                        tenant, registrationId, delegate.getToolDefinition().name(), toolInput)
                .orElseGet(() -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return gate.get()
                .routeToolCall(
                        tenant, registrationId, delegate.getToolDefinition().name(), toolInput)
                .orElseGet(() -> delegate.call(toolInput, toolContext));
    }
}
