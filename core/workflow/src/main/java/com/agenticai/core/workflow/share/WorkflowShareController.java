package com.agenticai.core.workflow.share;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowRunStatus;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code /api/share/workflows/{shareId}} - the PUBLIC half of workflow sharing, the workflow
 * counterpart of {@code com.agenticai.core.agents.share.AgentShareController} (see that class's
 * javadoc for the full reasoning behind this shape - mirrored closely here). No auth of any kind;
 * possession of the unguessable share id is the whole credential. The OWNER half
 * (mint/inspect/revoke) lives elsewhere.
 *
 * <p>A viewer's run dispatches through the normal {@link WorkflowService#run} path inside the
 * OWNING tenant's own {@link TenantContext} (resolved cross-tenant by {@link
 * WorkflowShareService#resolveActiveShare}, then set by hand exactly like {@code
 * AgentShareController} does), so it is automatically metered against that tenant's own plan/budget
 * exactly like any authenticated run - nothing bespoke to build here. The two share-specific lids
 * on top are {@link WorkflowShareService#tryAcquireRun} (per-IP rate limit) and the
 * report/auto-revoke threshold.
 */
@RestController
@RequestMapping("/api/share/workflows")
public class WorkflowShareController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowShareController.class);

    private final WorkflowShareService shareService;
    private final WorkflowService workflowService;
    private final TenantLimits tenantLimits;
    private final ObjectMapper objectMapper;

    public WorkflowShareController(
            WorkflowShareService shareService,
            WorkflowService workflowService,
            TenantLimits tenantLimits,
            ObjectMapper objectMapper) {
        this.shareService = shareService;
        this.workflowService = workflowService;
        this.tenantLimits = tenantLimits;
        this.objectMapper = objectMapper;
    }

    /**
     * {@code brandDisplayName}/{@code brandLogoUrl} - the owning tenant's own branding, or both
     * {@code null} for platform (VegaDuta) branding. Same white-label pattern as {@code
     * AgentShareController.ShareMetaResponse} (see its javadoc).
     *
     * <p>{@code supportsMediaInput} is a deliberately SIMPLE heuristic, not a real capability
     * probe: {@code true} whenever the workflow's graph contains ANY node of type {@code agent}
     * (case-insensitively), on the theory that only an Agent node can meaningfully consume an
     * inline image/audio attachment today. This over- and under-approximates in real cases (an
     * Agent node whose configured model doesn't actually support multimodal input still reports
     * {@code true}; a future node type that accepts media would report {@code false} until this is
     * updated) - a known, accepted simplification, not a bug.
     */
    public record ShareMetaResponse(
            String workflowName,
            String brandDisplayName,
            String brandLogoUrl,
            boolean supportsMediaInput) {}

    public record ShareRunRequest(@Size(max = 2000) String message) {}

    public record ShareRunResponse(String resultText, String status, String errorMessage) {}

    public record ShareReportRequest(@Size(max = 500) String reason) {}

    /**
     * Public share metadata - what the landing frame renders before the viewer says anything. 410
     * for an unknown/revoked/gone share (deliberately indistinguishable, same posture as the
     * agent-share surface).
     */
    @GetMapping("/{shareId}")
    public ResponseEntity<?> meta(@PathVariable String shareId) {
        Optional<WorkflowShareService.ResolvedShare> resolved =
                shareService.resolveActiveShare(shareId);
        if (resolved.isEmpty()) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }
        try {
            WorkflowDefinition workflow = resolved.get().workflow();
            // resolveActiveShare already set TenantContext to the owning
            // tenant's schema, so this reads the OWNER's entitlement/
            // branding - the anonymous viewer has no tenant of their own.
            String ownerTenantId =
                    TenantContext.current().map(TenantContext::getTenantId).orElse(null);
            String brandDisplayName =
                    ownerTenantId == null
                            ? null
                            : tenantLimits.brandDisplayName(ownerTenantId).orElse(null);
            String brandLogoUrl =
                    ownerTenantId == null
                            ? null
                            : tenantLimits.brandLogoUrl(ownerTenantId).orElse(null);
            boolean supportsMediaInput = hasAgentNode(workflow.getGraphJson());
            return ResponseEntity.ok(
                    new ShareMetaResponse(
                            workflow.getName(),
                            brandDisplayName,
                            brandLogoUrl,
                            supportsMediaInput));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * A viewer's run: per-IP rate limit, then the workflow's normal run path inside the owning
     * tenant's own context.
     */
    @PostMapping("/{shareId}/run")
    public ResponseEntity<?> run(
            @PathVariable String shareId,
            @Valid @RequestBody(required = false) ShareRunRequest request,
            HttpServletRequest servletRequest) {
        String clientIp = servletRequest.getRemoteAddr();
        if (!shareService.tryAcquireRun(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "rate_limited"));
        }
        Optional<WorkflowShareService.ResolvedShare> resolved =
                shareService.resolveActiveShare(shareId);
        if (resolved.isEmpty()) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }
        try {
            WorkflowShare share = resolved.get().share();
            shareService.recordViewer(share);
            String message = request == null || request.message() == null ? "" : request.message();
            WorkflowRunResponse response =
                    workflowService.run(
                            resolved.get().workflow().getId(),
                            null,
                            message,
                            "workflow-share:" + share.getShareId(),
                            null,
                            List.of());
            return ResponseEntity.ok(
                    new ShareRunResponse(
                            deriveResultText(response),
                            response.status().name(),
                            response.errorMessage()));
        } catch (RuntimeException e) {
            log.warn("Workflow share run failed for share {}", shareId, e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "upstream_failed"));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Anyone with the link can report it - same "204 always, revocation decided silently" posture
     * as the agent-share surface.
     */
    @PostMapping("/{shareId}/report")
    public ResponseEntity<?> report(
            @PathVariable String shareId,
            @Valid @RequestBody(required = false) ShareReportRequest request) {
        Optional<WorkflowShareService.ResolvedShare> resolved =
                shareService.resolveActiveShare(shareId);
        if (resolved.isEmpty()) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }
        try {
            shareService.report(resolved.get().share(), request == null ? null : request.reason());
            return ResponseEntity.noContent().build();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Public so a sibling voice controller (built separately) can reuse this exact mapping instead
     * of duplicating it: prefer the last trace entry's summary (the most recent node's own account
     * of what happened), fall back to the run's error message on a failed run, else a generic
     * fallback string.
     */
    public static String deriveResultText(WorkflowRunResponse response) {
        List<NodeTraceEntry> trace = response.trace();
        if (trace != null && !trace.isEmpty()) {
            String summary = trace.get(trace.size() - 1).summary();
            if (summary != null && !summary.isBlank()) {
                return summary;
            }
        }
        if (response.status() == WorkflowRunStatus.FAILED && response.errorMessage() != null) {
            return response.errorMessage();
        }
        return "The workflow finished with no output.";
    }

    /**
     * See {@link ShareMetaResponse#supportsMediaInput}'s javadoc for the heuristic and its known
     * limitations. Never throws - a malformed/legacy graph simply reports {@code false}.
     */
    private boolean hasAgentNode(String graphJson) {
        try {
            WorkflowGraphDto graph = objectMapper.readValue(graphJson, WorkflowGraphDto.class);
            for (WorkflowNodeDto node : graph.nodes()) {
                if (node.type() != null && "agent".equalsIgnoreCase(node.type())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
