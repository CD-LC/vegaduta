package com.agenticai.core.agents.tools.mcpreview.dto;

/**
 * Optional body for {@code POST /api/platform/mcp-review/{tenantId}/{id}/reject} - {@code reason}
 * is shown back to the tenant in the connect refusal hint ({@code
 * DynamicMcpConnectionManager#rejectIfReviewBlocked}), so a reviewer can explain WHY ("this package
 * has no published source, reached out to the author for verification") rather than leaving a bare
 * "rejected". Null/blank is accepted - the refusal hint just omits the "why" clause.
 */
public record McpReviewDecisionRequest(String reason) {}
