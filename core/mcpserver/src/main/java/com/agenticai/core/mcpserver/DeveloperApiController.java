package com.agenticai.core.mcpserver;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.mcpserver.tools.McpListAgentsToolCallback;
import com.agenticai.core.mcpserver.tools.McpListMyWorkflowsToolCallback;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.WorkflowRunNotFoundException;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
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

/**
 * "Developer options... let customers generate scoped API keys to call their agents/workflows
 * programmatically" - a plain REST surface (JSON in, JSON out, one {@code curl} away) authenticated
 * by the SAME API key system {@code /mcp/**} already established ({@link McpApiKeyAuthFilter},
 * {@link McpServerAccessGrantService}, key issuance at {@code /api/mcpserver/grants}) rather than a
 * second, parallel key system - a customer's key is already scoped to specific agents/workflows via
 * that existing grant editor. What was actually missing wasn't the key infrastructure, it was a
 * calling surface that doesn't require speaking the MCP JSON-RPC protocol: {@code /mcp/**} needs a
 * real MCP client (Claude Desktop, another agent framework); {@code /api/dev/v1/**} needs nothing
 * but an HTTP client.
 *
 * <p>Authorization is identical to {@code McpCallAgentToolCallback}/{@code
 * McpCallWorkflowToolCallback}: the requested id must be in the authenticated grant's exposed set
 * ({@link McpCallerContext}, populated by {@link McpApiKeyAuthFilter} - see {@code
 * McpServerToolConfig} for where that filter is registered on this controller's own {@code
 * /api/dev/*} path, alongside {@code /mcp/*}). Same {@code "mcpserver"} profile gate as the rest of
 * this module - inert with zero risk when that profile is off.
 */
@RestController
@RequestMapping("/api/dev/v1")
public class DeveloperApiController {

    private static final Logger log = LoggerFactory.getLogger(DeveloperApiController.class);

    private final AgentChatService agentChatService;
    private final AgentService agentService;
    private final WorkflowService workflowService;
    private final AuditLogService auditLogService;

    public DeveloperApiController(
            AgentChatService agentChatService,
            AgentService agentService,
            WorkflowService workflowService,
            AuditLogService auditLogService) {
        this.agentChatService = agentChatService;
        this.agentService = agentService;
        this.workflowService = workflowService;
        this.auditLogService = auditLogService;
    }

    public record ChatRequest(
            // Same ceilings as the console's agents/dto/ChatRequest: one turn is
            // bounded as a DoS control, and a session id is a short client token.
            @NotBlank @Size(max = 8000) String message, @Size(max = 128) String sessionId) {}

    public record ChatResponse(String reply, String sessionId) {}

    public record RunRequest(String input) {}

    /**
     * Id + name only - never the system prompt, model, or tool config {@code AgentResponse} also
     * carries.
     */
    public record AgentSummaryDto(UUID id, String name) {}

    /**
     * Id + name only - the graph JSON (which can embed prompts, webhook URLs, connector details)
     * stays inside the platform.
     */
    public record WorkflowSummaryDto(UUID id, String name) {}

    /** GET /api/dev/v1/agents - the REST equivalent of the MCP {@code list_my_agents} tool. */
    @GetMapping("/agents")
    public ResponseEntity<List<AgentSummaryDto>> listAgents() {
        McpCallerContext caller = requireCaller();
        List<AgentSummaryDto> agents =
                McpListAgentsToolCallback.resolveExposedAgents(
                                agentService, caller.exposedAgentIds())
                        .stream()
                        .map(agent -> new AgentSummaryDto(agent.id(), agent.name()))
                        .toList();
        recordAudit(
                caller.grantId(),
                "Agent",
                "list",
                AuditOutcome.SUCCESS,
                "Developer API grant "
                        + caller.grantId()
                        + " listed its exposed agents ("
                        + agents.size()
                        + ").");
        return ResponseEntity.ok(agents);
    }

    /**
     * GET /api/dev/v1/workflows - the REST equivalent of the MCP {@code list_my_workflows} tool.
     */
    @GetMapping("/workflows")
    public ResponseEntity<List<WorkflowSummaryDto>> listWorkflows() {
        McpCallerContext caller = requireCaller();
        List<WorkflowSummaryDto> workflows =
                McpListMyWorkflowsToolCallback.resolveExposedWorkflows(
                                workflowService, caller.exposedWorkflowIds())
                        .stream()
                        .map(
                                workflow ->
                                        new WorkflowSummaryDto(
                                                workflow.getId(), workflow.getName()))
                        .toList();
        recordAudit(
                caller.grantId(),
                "Workflow",
                "list",
                AuditOutcome.SUCCESS,
                "Developer API grant "
                        + caller.grantId()
                        + " listed its exposed workflows ("
                        + workflows.size()
                        + ").");
        return ResponseEntity.ok(workflows);
    }

    /**
     * GET /api/dev/v1/workflows/{workflowId}/runs/{runId} - poll a run started via {@link #run};
     * same {@link WorkflowRunResponse} shape the JWT surface's {@code WorkflowController#getRun}
     * returns. {@code WorkflowService#getRun} is deliberately permission-neutral (the JWT surface's
     * {@code requireAccess} lives in the controller, not the service) and loads via {@code
     * findByIdAndWorkflowId}, so the exposed-set check below plus that structural run-to-workflow
     * binding is the complete authorization here - no widened service method needed.
     */
    @GetMapping("/workflows/{workflowId}/runs/{runId}")
    public ResponseEntity<?> runStatus(@PathVariable UUID workflowId, @PathVariable UUID runId) {
        McpCallerContext caller = requireCaller();
        if (!caller.isWorkflowExposed(workflowId)) {
            log.warn(
                    "Developer API grant {} attempted to read run {} of workflow {} outside its"
                            + " exposed-workflow set",
                    caller.grantId(),
                    runId,
                    workflowId);
            recordAudit(
                    caller.grantId(),
                    "Workflow",
                    workflowId.toString(),
                    AuditOutcome.FAILURE,
                    "Developer API grant "
                            + caller.grantId()
                            + " attempted to read run "
                            + runId
                            + " of workflow "
                            + workflowId
                            + ", which is outside its exposed-workflow set.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(
                            errorBody(
                                    "This API key is not authorized to read runs of that"
                                            + " workflow."));
        }
        try {
            WorkflowRunResponse run = workflowService.getRun(workflowId, runId);
            recordAudit(
                    caller.grantId(),
                    "Workflow",
                    workflowId.toString(),
                    AuditOutcome.SUCCESS,
                    "Developer API grant "
                            + caller.grantId()
                            + " read run "
                            + runId
                            + " of workflow "
                            + workflowId
                            + ".");
            return ResponseEntity.ok(run);
        } catch (WorkflowRunNotFoundException e) {
            // 404, never 403, on a missing or mismatched run - same posture as
            // the JWT surface (see WorkflowService#requireRunOfWorkflow).
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(e.getMessage()));
        } catch (RuntimeException e) {
            log.warn(
                    "Developer API run-status read failed for grant {} / workflow {} / run {}",
                    caller.grantId(),
                    workflowId,
                    runId,
                    e);
            recordAudit(
                    caller.grantId(),
                    "Workflow",
                    workflowId.toString(),
                    AuditOutcome.FAILURE,
                    "Developer API grant "
                            + caller.grantId()
                            + " run-status read failed: "
                            + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    // The exception text (DB/driver detail) is in the warn log above, never
                    // in the response body.
                    .body(errorBody("Run status read failed. Please retry shortly."));
        }
    }

    /**
     * POST /api/dev/v1/agents/{agentId}/chat - the REST equivalent of the MCP {@code call_agent}
     * tool.
     */
    @PostMapping("/agents/{agentId}/chat")
    public ResponseEntity<?> chat(
            @PathVariable UUID agentId, @Valid @RequestBody ChatRequest request) {
        McpCallerContext caller = requireCaller();
        if (!caller.isAgentExposed(agentId)) {
            log.warn(
                    "Developer API grant {} attempted to call agent {} outside its exposed-agent"
                            + " set",
                    caller.grantId(),
                    agentId);
            recordAudit(
                    caller.grantId(),
                    "Agent",
                    agentId.toString(),
                    AuditOutcome.FAILURE,
                    "Developer API grant "
                            + caller.grantId()
                            + " attempted to call agent "
                            + agentId
                            + ", which is outside its exposed-agent set.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(errorBody("This API key is not authorized to call that agent."));
        }
        if (request == null || request.message() == null || request.message().isBlank()) {
            return ResponseEntity.unprocessableEntity().body(errorBody("message is required."));
        }
        String sessionId =
                request.sessionId() != null && !request.sessionId().isBlank()
                        ? request.sessionId()
                        : "dev-api-" + caller.grantId() + "-" + UUID.randomUUID();
        String subjectKey = "dev-api:" + caller.grantId();
        try {
            ChatTurnRequest chatTurn =
                    ChatTurnRequest.builder(agentId, sessionId, subjectKey, request.message())
                            .build();
            TurnContext turnContext =
                    TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
            String reply =
                    agentChatService
                            .chat(turnContext, chatTurn)
                            .collectList()
                            .map(chunks -> String.join("", chunks))
                            .block();
            recordAudit(
                    caller.grantId(),
                    "Agent",
                    agentId.toString(),
                    AuditOutcome.SUCCESS,
                    "Developer API grant "
                            + caller.grantId()
                            + " invoked agent "
                            + agentId
                            + " (session "
                            + sessionId
                            + ").");
            return ResponseEntity.ok(new ChatResponse(reply == null ? "" : reply, sessionId));
        } catch (RuntimeException e) {
            log.warn(
                    "Developer API chat call failed for grant {} / agent {}",
                    caller.grantId(),
                    agentId,
                    e);
            recordAudit(
                    caller.grantId(),
                    "Agent",
                    agentId.toString(),
                    AuditOutcome.FAILURE,
                    "Developer API grant "
                            + caller.grantId()
                            + " agent call failed: "
                            + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(errorBody("Agent call failed: " + e.getMessage()));
        }
    }

    /**
     * POST /api/dev/v1/workflows/{workflowId}/run - the REST equivalent of the MCP {@code
     * call_workflow} tool.
     */
    @PostMapping("/workflows/{workflowId}/run")
    public ResponseEntity<?> run(
            @PathVariable UUID workflowId, @RequestBody(required = false) RunRequest request) {
        McpCallerContext caller = requireCaller();
        if (!caller.isWorkflowExposed(workflowId)) {
            log.warn(
                    "Developer API grant {} attempted to run workflow {} outside its"
                            + " exposed-workflow set",
                    caller.grantId(),
                    workflowId);
            recordAudit(
                    caller.grantId(),
                    "Workflow",
                    workflowId.toString(),
                    AuditOutcome.FAILURE,
                    "Developer API grant "
                            + caller.grantId()
                            + " attempted to run workflow "
                            + workflowId
                            + ", which is outside its exposed-workflow set.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(errorBody("This API key is not authorized to run that workflow."));
        }
        try {
            WorkflowRunResponse run =
                    workflowService.run(
                            workflowId,
                            request == null ? null : request.input(),
                            "dev-api:" + caller.grantId());
            recordAudit(
                    caller.grantId(),
                    "Workflow",
                    workflowId.toString(),
                    AuditOutcome.SUCCESS,
                    "Developer API grant "
                            + caller.grantId()
                            + " ran workflow "
                            + workflowId
                            + " (run "
                            + run.id()
                            + ").");
            return ResponseEntity.ok(run);
        } catch (RuntimeException e) {
            log.warn(
                    "Developer API workflow run failed for grant {} / workflow {}",
                    caller.grantId(),
                    workflowId,
                    e);
            recordAudit(
                    caller.grantId(),
                    "Workflow",
                    workflowId.toString(),
                    AuditOutcome.FAILURE,
                    "Developer API grant "
                            + caller.grantId()
                            + " workflow run failed: "
                            + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(errorBody("Workflow run failed: " + e.getMessage()));
        }
    }

    private McpCallerContext requireCaller() {
        return McpCallerContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Developer API endpoint invoked with no McpCallerContext"
                                            + " set - McpApiKeyAuthFilter should have rejected this"
                                            + " request already."));
    }

    /**
     * Best-effort, same "never let logging break the real call" posture {@code
     * McpCallAgentToolCallback#recordAudit} already takes.
     */
    private void recordAudit(
            UUID grantId, String entityType, String entityId, AuditOutcome outcome, String detail) {
        try {
            auditLogService.record(
                    "dev-api:" + grantId,
                    "mcp.grant.dev_api_call",
                    entityType,
                    entityId,
                    outcome,
                    detail);
        } catch (RuntimeException auditFailure) {
            log.warn(
                    "Failed to record audit log entry for developer API grant {}",
                    grantId,
                    auditFailure);
        }
    }

    private java.util.Map<String, String> errorBody(String message) {
        return java.util.Map.of("error", message);
    }
}
