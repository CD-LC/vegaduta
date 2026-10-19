package com.agenticai.core.mcpserver;

import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.mcpserver.dto.McpServerAccessGrantCreateRequest;
import com.agenticai.core.mcpserver.dto.McpServerAccessGrantCreatedResponse;
import com.agenticai.core.mcpserver.dto.McpServerAccessGrantResponse;
import com.agenticai.core.mcpserver.dto.McpServerAccessGrantToggleRequest;
import com.agenticai.core.mcpserver.dto.McpServerAccessGrantUpdateRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-admin-facing surface to generate/rotate/revoke inbound MCP API keys and pick which agents
 * (see {@link McpServerAccessGrant}'s javadoc for the workflow/knowledge-collection honest-scope
 * note) are exposed to whoever holds one. Every mutating endpoint is locked to {@code
 * ROLE_tenant-admin} in {@code SecurityConfig} - same "generating a credential that can trigger
 * real, consequential agent behavior is an admin action" posture that module already applies to
 * {@code /api/mcp/registry}, {@code /api/providers}, {@code /api/channels/*}, etc.
 */
@RestController
@RequestMapping("/api/mcpserver/grants")
public class McpServerAccessController {

    private final McpServerAccessGrantService service;
    private final ToolCallLogService toolCallLogService;

    public McpServerAccessController(
            McpServerAccessGrantService service, ToolCallLogService toolCallLogService) {
        this.service = service;
        this.toolCallLogService = toolCallLogService;
    }

    @PostMapping
    public ResponseEntity<McpServerAccessGrantCreatedResponse> create(
            @Valid @RequestBody McpServerAccessGrantCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping
    public List<McpServerAccessGrantResponse> list() {
        return service.listAll();
    }

    @PutMapping("/{id}")
    public McpServerAccessGrantResponse update(
            @PathVariable UUID id, @Valid @RequestBody McpServerAccessGrantUpdateRequest request) {
        return service.updateExposedIds(id, request);
    }

    @PatchMapping("/{id}")
    public McpServerAccessGrantResponse toggle(
            @PathVariable UUID id, @Valid @RequestBody McpServerAccessGrantToggleRequest request) {
        return service.setEnabled(id, request.enabled());
    }

    /**
     * Mints a fresh key for this same grant (same exposed-agent config) and immediately invalidates
     * the old one - see McpServerAccessGrantService#rotate.
     */
    @PostMapping("/{id}/rotate")
    public McpServerAccessGrantCreatedResponse rotate(@PathVariable UUID id) {
        return service.rotate(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        service.revoke(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Data-governance audit fix (2026-07-20) - the real, queryable answer to "show me every action
     * this platform has taken as a result of MCP grant X" for a real incident investigation or
     * compliance audit. Backed by {@code tool_call_log.mcp_grant_id}, tagged at the source by
     * {@code AgentToolProvider#guardAndLog}/{@code LoggingToolCallback} from {@code
     * TenantContext#getExternalTriggerId()} - see {@code McpApiKeyAuthFilter}'s javadoc for where
     * that value first gets set. Most-recent first. Explicitly gated to {@code ROLE_tenant-admin}
     * in {@code SecurityConfig} (unlike this controller's plain GET list() above, which falls
     * through to any authenticated tenant member) - tool call input/output can contain the same
     * sensitive content {@code /api/audit-log} is already gated for, so this endpoint gets the same
     * explicit gate rather than the looser default.
     */
    @GetMapping("/{id}/tool-calls")
    public List<McpGrantToolCallView> toolCalls(@PathVariable UUID id) {
        return toolCallLogService.findByMcpGrantId(id).stream()
                .map(McpGrantToolCallView::from)
                .toList();
    }

    public record McpGrantToolCallView(
            UUID id,
            UUID agentId,
            String sessionId,
            String toolName,
            String toolInput,
            String toolOutput,
            String errorMessage,
            Instant startedAt,
            Instant completedAt) {
        static McpGrantToolCallView from(ToolCallLog log) {
            return new McpGrantToolCallView(
                    log.getId(),
                    log.getAgentId(),
                    log.getSessionId(),
                    log.getToolName(),
                    log.getToolInput(),
                    log.getToolOutput(),
                    log.getErrorMessage(),
                    log.getStartedAt(),
                    log.getCompletedAt());
        }
    }
}
