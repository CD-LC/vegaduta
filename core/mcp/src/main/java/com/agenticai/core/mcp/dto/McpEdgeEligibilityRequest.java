package com.agenticai.core.mcp.dto;

/** Body for {@code PATCH /api/mcp/registry/{id}/edge-eligible} - see {@code McpServerRegistration#edgeEligible}'s javadoc. */
public record McpEdgeEligibilityRequest(boolean edgeEligible) {
}
