package com.agenticai.core.agents.tools.mcpregistry;

import java.util.UUID;

/**
 * Invokes one tool on this JVM's live client for a registration (the owner side of a routed call).
 */
@FunctionalInterface
public interface McpToolCaller {

    /**
     * @throws IllegalStateException if this JVM holds no live client or no such tool.
     */
    String call(String tenant, UUID registrationId, String toolName, String argsJson);
}
