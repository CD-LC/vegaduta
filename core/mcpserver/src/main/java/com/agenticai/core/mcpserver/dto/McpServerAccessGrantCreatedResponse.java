package com.agenticai.core.mcpserver.dto;

/**
 * Returned ONLY from creation/rotation - {@code rawApiKey} is the one and only time this
 * platform ever hands back the plaintext key (mirrors this codebase's established "shown once"
 * secret posture, e.g. WorkflowService#enableWebhook). Never persisted, never retrievable again -
 * the tenant-admin must copy it now or rotate to get a new one.
 */
public record McpServerAccessGrantCreatedResponse(
    McpServerAccessGrantResponse grant,
    String rawApiKey
) {
}
