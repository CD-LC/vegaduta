package com.agenticai.core.mcpserver.expose;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The "Expose as MCP tool" switch on an agent's / workflow's settings - {@code
 * /api/mcpserver/exposure/agents/{id}} and {@code .../workflows/{id}}. Thin on purpose: access
 * (EDITOR to change, VIEWER to read; 404 for a resource the caller cannot see) is decided in
 * {@link McpToolExposureService}. Tenant-scoped; {@code SecurityConfig} additionally requires
 * {@code PERM_MANAGE_INTEGRATIONS} for the PUT (the same gate as minting MCP grant keys).
 *
 * <p>{@code featureEnabled} reports whether this deployment serves exposed tools at all ({@code
 * app.mcp-server.expose-agents.enabled}); the switch can still be stored while it is false, so an
 * admin can prepare, but nothing is advertised until the flag is on. {@code mcpPath} is the path of
 * the MCP endpoint on the API host.
 */
@ConditionalOnEditionFeature(EditionFeature.MCP_SERVER_EXPOSURE)
@RestController
@RequestMapping("/api/mcpserver/exposure")
public class McpToolExposureController {

    public record ExposureResponse(boolean exposed, String toolName, boolean featureEnabled, String mcpPath) {
    }

    public record ExposureUpdateRequest(boolean exposed) {
    }

    private static final String MCP_PATH = "/mcp";

    private final McpToolExposureService service;
    private final boolean featureEnabled;

    public McpToolExposureController(
        McpToolExposureService service,
        @Value("${app.mcp-server.expose-agents.enabled:false}") boolean featureEnabled
    ) {
        this.service = service;
        this.featureEnabled = featureEnabled;
    }

    @GetMapping("/{kind}/{resourceId}")
    public ExposureResponse get(@PathVariable String kind, @PathVariable UUID resourceId) {
        return toResponse(service.get(McpExposureKind.fromPathSegment(kind), resourceId));
    }

    @PutMapping("/{kind}/{resourceId}")
    public ExposureResponse update(
        @PathVariable String kind, @PathVariable UUID resourceId, @RequestBody ExposureUpdateRequest request
    ) {
        return toResponse(service.setExposed(McpExposureKind.fromPathSegment(kind), resourceId, request.exposed()));
    }

    private ExposureResponse toResponse(McpToolExposureService.State state) {
        return new ExposureResponse(state.exposed(), state.toolName(), featureEnabled, MCP_PATH);
    }
}
