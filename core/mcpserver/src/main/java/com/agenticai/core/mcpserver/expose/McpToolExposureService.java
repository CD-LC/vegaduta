package com.agenticai.core.mcpserver.expose;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.workflow.WorkflowService;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the per-agent / per-workflow "Expose as MCP tool" switch (table {@code mcp_tool_exposure},
 * V425). The switch is only HALF of what makes a tool visible to an external MCP client: the
 * caller's API-key grant ({@code McpServerAccessGrant}) must also list the agent / workflow, and
 * the whole feature is behind {@code app.mcp-server.expose-agents.enabled} (default off). Flipping
 * the switch therefore grants nothing by itself.
 *
 * <p>Who may flip it: anyone with EDITOR access to the resource, checked through the same
 * {@code core/sharing} funnels the console's own settings endpoints use ({@code
 * AgentService#requireAccess}, {@code WorkflowService#requireEditorAccess}); a caller who cannot
 * even see the resource gets the usual 404. Reading the state needs VIEWER access.
 *
 * <p>The tool name is allocated once (see {@link McpToolSlugs}) and kept when the switch is turned
 * off and on again, so external clients keep working across a temporary disable.
 */
@Service
public class McpToolExposureService {

    private static final Logger log = LoggerFactory.getLogger(McpToolExposureService.class);

    /** Current state of one resource; {@code toolName} is null until the resource was first exposed. */
    public record State(boolean exposed, String toolName) {
    }

    private final McpToolExposureRepository repository;
    private final AgentService agentService;
    private final WorkflowService workflowService;
    private final AuditLogService auditLogService;

    public McpToolExposureService(
        McpToolExposureRepository repository,
        AgentService agentService,
        WorkflowService workflowService,
        AuditLogService auditLogService
    ) {
        this.repository = repository;
        this.agentService = agentService;
        this.workflowService = workflowService;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public State get(McpExposureKind kind, UUID resourceId) {
        requireAccess(kind, resourceId, PermissionLevel.VIEWER);
        return repository.findByKindAndResourceId(kind, resourceId)
            .map(row -> new State(row.isExposed(), row.getToolName()))
            .orElse(new State(false, null));
    }

    @Transactional
    public State setExposed(McpExposureKind kind, UUID resourceId, boolean exposed) {
        String resourceName = requireAccess(kind, resourceId, PermissionLevel.EDITOR);
        Optional<McpToolExposure> existing = repository.findByKindAndResourceId(kind, resourceId);
        McpToolExposure row;
        if (existing.isPresent()) {
            row = existing.get();
            if (row.isExposed() == exposed) {
                return new State(row.isExposed(), row.getToolName());
            }
            row.setExposed(exposed);
            row = repository.save(row);
        } else if (!exposed) {
            // Never exposed and asked to stay that way: no row needed.
            return new State(false, null);
        } else {
            Set<String> taken = new HashSet<>();
            for (McpToolExposure other : repository.findAll()) {
                taken.add(other.getToolName());
            }
            String toolName = McpToolSlugs.allocate(kind, resourceName, resourceId, taken);
            row = repository.save(new McpToolExposure(kind, resourceId, toolName, true, currentActor()));
        }
        recordAudit(kind, resourceId, row.getToolName(), exposed);
        return new State(row.isExposed(), row.getToolName());
    }

    /** Every enabled exposure row of the current tenant (the caller's own grant filter is applied by {@link McpExposedToolCatalog}). */
    @Transactional(readOnly = true)
    public List<McpToolExposure> listExposed() {
        return repository.findByExposedTrue();
    }

    /** Returns the resource's display name once the access check passed (404 via ResourceAccessDeniedException otherwise). */
    private String requireAccess(McpExposureKind kind, UUID resourceId, PermissionLevel level) {
        return switch (kind) {
            case AGENT -> agentService.requireAccess(resourceId, level).getName();
            case WORKFLOW -> (level == PermissionLevel.EDITOR
                ? workflowService.requireEditorAccess(resourceId)
                : workflowService.requireViewerAccess(resourceId)).getName();
        };
    }

    private String currentActor() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }

    private void recordAudit(McpExposureKind kind, UUID resourceId, String toolName, boolean exposed) {
        try {
            auditLogService.record(currentActor(), exposed ? "mcpserver.exposure.enabled" : "mcpserver.exposure.disabled",
                kind == McpExposureKind.AGENT ? "Agent" : "Workflow", resourceId.toString(), AuditOutcome.SUCCESS,
                "[channel=mcp] " + (exposed ? "Exposed" : "Stopped exposing") + " " + kind.name().toLowerCase()
                    + " " + resourceId + " as MCP tool '" + toolName + "'.");
        } catch (RuntimeException auditFailure) {
            log.warn("Failed to record audit log entry for MCP exposure change on {} {}", kind, resourceId, auditFailure);
        }
    }
}
