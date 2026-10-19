package com.agenticai.core.mcp;

import com.agenticai.core.mcp.dto.McpConnectionTestResult;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP Connection Doctor endpoints (2026-07-25). Separate controller (and
 * path root, {@code /api/mcp/registrations} - the path shape frozen with the
 * web agent) from {@link McpRegistryController}'s {@code /api/mcp/registry},
 * but the same tenant-scoped, tenant-admin authz posture as that
 * controller's mutation endpoints - see SecurityConfig's
 * {@code /api/mcp/registrations/*} matchers.
 *
 * <p>{@code POST /{id}/test} - runs a cheap HTTP pre-probe (remotes) plus a
 * real bounded connect+initialize+close and returns the frozen contract
 * {@code { ok, phase, detail, hint, toolCount, suggestedFix }} (see {@link
 * McpConnectionTestResult}'s own javadoc for the 2026-07-26 {@code
 * suggestedFix} addition - a STDIO failure's stderr can yield a validated,
 * one-click-appliable correction); the outcome (usageStatus READY/FAILED +
 * structured diagnosis) is also persisted so the Tools UI's existing status
 * area shows the enriched result on its next poll.
 *
 * <p>{@code POST /{id}/acknowledge} - the readiness gate's explicit escape
 * hatch: lets an admin declare a FAILED/UNTESTED registration usable by
 * agents anyway (see {@link McpUsageStatus#ACKNOWLEDGED}).
 */
@RestController
@RequestMapping("/api/mcp/registrations")
public class McpRegistrationTestController {

    private final McpRegistryService service;
    // Same ObjectProvider cross-module seam as McpRegistryController - see
    // McpDynamicConnector's javadoc.
    private final ObjectProvider<McpDynamicConnector> connector;

    public McpRegistrationTestController(McpRegistryService service, ObjectProvider<McpDynamicConnector> connector) {
        this.service = service;
        this.connector = connector;
    }

    @PostMapping("/{id}/test")
    public McpConnectionTestResult test(@PathVariable UUID id) {
        McpDynamicConnector c = connector.getIfAvailable();
        if (c == null) {
            // Slim/degraded context without the live-connection half - honest
            // failure in the frozen contract shape rather than a 500.
            return McpConnectionTestResult.failure(
                McpConnectionPhase.HANDSHAKE,
                "Connection testing is unavailable on this deployment (no dynamic MCP connector).",
                null);
        }
        return c.test(id);
    }

    @PostMapping("/{id}/acknowledge")
    public McpServerRegistrationResponse acknowledge(@PathVariable UUID id) {
        return service.acknowledge(id);
    }
}
