package com.agenticai.core.agents.tools.mcpreview.dto;

import com.agenticai.core.mcp.McpServerRegistration;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the platform-staff MCP review queue ({@code GET /api/platform/mcp-review/pending}) - a
 * self-service custom/registry- browsed STDIO registration awaiting sign-off before {@code
 * DynamicMcpConnectionManager} will ever spawn it (see {@code McpReviewStatus}'s javadoc).
 *
 * <p>{@code env} is deliberately absent, same "never echo a secret back" posture {@link
 * com.agenticai.core.mcp.dto.McpServerRegistrationResponse}'s own javadoc documents - a reviewer
 * needs to see WHAT will run (command, args) and WHO/WHEN, not the tenant's decrypted credentials,
 * to make an approve/reject call. {@code args} is the raw JSON-array string, same shape {@code
 * McpServerRegistrationResponse#args} already uses (this platform's existing convention - the
 * frontend already knows how to render it).
 *
 * @param tenantId the tenant SCHEMA this row lives in - required to target {@code POST
 *     .../approve}/{@code .../reject} at the right row, since a registration's UUID is only unique
 *     within its OWN tenant schema (a separate table per tenant), not globally.
 * @param requestedBy the subject who registered this server, or null for a row written with no
 *     ambient TenantContext / before this column existed - see {@link
 *     McpServerRegistration#getRequestedBy()}.
 */
public record McpPendingReviewResponse(
        String tenantId,
        UUID id,
        String name,
        String command,
        String args,
        String requestedBy,
        Instant createdAt) {
    public static McpPendingReviewResponse from(String tenantId, McpServerRegistration entity) {
        return new McpPendingReviewResponse(
                tenantId,
                entity.getId(),
                entity.getName(),
                entity.getCommand(),
                entity.getArgs(),
                entity.getRequestedBy(),
                entity.getCreatedAt());
    }
}
