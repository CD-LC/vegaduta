package com.agenticai.core.agents.tools.mcpreview;

import com.agenticai.core.agents.tools.mcpreview.dto.McpPendingReviewResponse;
import com.agenticai.core.agents.tools.mcpreview.dto.McpReviewDecisionRequest;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-staff MCP review queue - {@code /api/platform/mcp-review/**}. Gated by {@code
 * SecurityConfig}'s existing blanket {@code
 * .requestMatchers("/api/platform/**").hasAuthority("ROLE_platform-admin")} rule (same posture
 * {@link com.agenticai.core.platform.PlanLimitsController} documents for itself - no separate
 * authorization check needed inside this class). {@code ROLE_platform-admin} is a PLATFORM-level
 * role, distinct from {@code ROLE_tenant-admin} (the role that can self-service-register the STDIO
 * servers this queue reviews) - see {@code McpReviewStatus}'s javadoc for the threat this
 * separation exists to close.
 *
 * <p>Lives in {@code core/agents} (not {@code core/mcp}, where the rest of the MCP registry
 * controllers live) because this is the one MCP admin surface that is genuinely CROSS-tenant - see
 * {@link McpRegistryReviewService}'s own javadoc for why that requires {@code
 * TenantSchemaIterator}, a dependency {@code core/mcp} deliberately does not have.
 */
@RestController
@RequestMapping("/api/platform/mcp-review")
public class McpRegistryReviewController {

    private final McpRegistryReviewService service;

    public McpRegistryReviewController(McpRegistryReviewService service) {
        this.service = service;
    }

    /** Every PENDING self-service STDIO registration across every tenant - the reviewer's inbox. */
    @GetMapping("/pending")
    public List<McpPendingReviewResponse> pending() {
        return service.listPending();
    }

    /**
     * Approves {@code id} (which lives in tenant {@code tenantId}'s schema) and immediately
     * attempts a real connect.
     */
    @PostMapping("/{tenantId}/{id}/approve")
    public McpServerRegistrationResponse approve(
            @PathVariable String tenantId, @PathVariable UUID id) {
        return service.approve(tenantId, id);
    }

    /**
     * Rejects {@code id} (which lives in tenant {@code tenantId}'s schema) - body's {@code reason}
     * is optional.
     */
    @PostMapping("/{tenantId}/{id}/reject")
    public McpServerRegistrationResponse reject(
            @PathVariable String tenantId,
            @PathVariable UUID id,
            @RequestBody(required = false) McpReviewDecisionRequest request) {
        String reason = request == null ? null : request.reason();
        return service.reject(tenantId, id, reason);
    }
}
