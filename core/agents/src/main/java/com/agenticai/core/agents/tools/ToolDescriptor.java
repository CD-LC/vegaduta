package com.agenticai.core.agents.tools;

/**
 * A tool's name plus its model-facing description, for the per-agent tool allow-list "available
 * tools" picker - see {@code AgentToolProvider#listAvailableToolsFor(java.util.UUID)}. The name
 * here is exactly what {@code AgentToolAccessUpdateRequest.allowedToolNames} expects (it's the same
 * string {@code ToolDefinition.name()} returns, the one the allow-list filter matches against in
 * {@code AgentToolProvider#resolveToolsFor}), so picking an entry from this list and saving it back
 * always round-trips correctly.
 */
public record ToolDescriptor(String name, String description) {}
