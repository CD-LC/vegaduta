package com.agenticai.core.mcpserver.tools;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.mcpserver.McpCallerContext;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

/**
 * The workflow-side mirror of {@link McpCallAgentToolCallback} - routes an external MCP client's
 * call into {@link WorkflowService#run}, the EXACT SAME entry point the canvas's own "Run" button,
 * the REST {@code POST /api/workflows/{id}/run} endpoint, scheduled triggers, and the WORKFLOW node
 * type (a workflow calling another workflow, {@code WorkflowNodeExecutor}'s recursion-guarded case)
 * all go through - see {@code WorkflowExecutionEngine}'s javadoc for the "orchestration layer over
 * current services, not a rewrite" framing that entry point documents.
 *
 * <p><b>Honesty note - this is NOT a lightweight operation like {@code call_agent}, say so
 * plainly:</b> a single {@code call_agent} invocation is one governed chat turn against one agent.
 * A workflow run can walk an arbitrarily large author-drawn graph: every AGENT node it visits DOES
 * reuse {@code AgentChatService#chat} internally (so it inherits that same {@code
 * GuardedToolCallback}/autonomy/step-ceiling pipeline - see {@code WorkflowNodeExecutor}'s own
 * imports/javadoc), but a run can also fan out into up to {@code
 * WorkflowExecutionEngine.MAX_PARALLEL_BRANCHES} (10) concurrent branches, loop up to 100
 * iterations, call other tool/webhook/ external-action nodes, and recurse into other saved
 * workflows up to 5 levels deep. One external MCP tool call can therefore trigger many agent turns
 * and external side effects, not one. It can also legitimately SUSPEND rather than finish: a graph
 * containing an Approval node returns with {@code status: AWAITING_APPROVAL} - a real terminal
 * state for this call, not an error, meaning a human still has to act in the platform's own HITL
 * approvals inbox before that run's effects (if any) proceed further. Same {@code
 * requestedBy}-naming-convention traceability posture as {@code McpCallAgentToolCallback} (there is
 * no distinct "an external MCP grant triggered this run" column on {@code workflow_run} itself,
 * mirrored here as {@code "mcp-external:" + grantId}) - but, like that class, this callback DOES
 * also record a coarse, one-row-per-invocation entry to the platform's {@code audit_log_entry}
 * trail (see {@link #recordAudit}), so "was call_workflow invoked, for which workflow, and did it
 * succeed" is answerable from the tenant-admin Audit Log screen without reconstructing it from
 * {@code workflow_run} rows.
 *
 * <p>Unlike {@code call_agent}, {@link WorkflowService#run} is already a plain synchronous method
 * (not a reactive {@code Flux}), so there is no {@code .block()} here - but the request thread
 * handling this MCP call is still held for however long the ENTIRE run takes (potentially every
 * node in a large graph), which for a slow/looping/fanned-out workflow could be materially longer
 * than a single agent turn. Not fixed in this build, same "acceptable for this scaffold, real
 * production concern for a high-traffic external surface" caveat {@code McpCallAgentToolCallback}
 * already flags.
 *
 * <p><b>Per-call authorization</b> happens here, not via which tools are advertised (see {@code
 * McpServerToolConfig}'s javadoc for why): the requested {@code workflow_id} MUST be in {@link
 * McpCallerContext}'s exposed-workflow set for the authenticated grant, checked BEFORE {@code
 * WorkflowService#run} is ever invoked - an id outside that set is rejected here, it never reaches
 * {@code WorkflowService}.
 */
public class McpCallWorkflowToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(McpCallWorkflowToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final WorkflowService workflowService;
    private final AuditLogService auditLogService;

    public McpCallWorkflowToolCallback(
            WorkflowService workflowService, AuditLogService auditLogService) {
        this.workflowService = workflowService;
        this.auditLogService = auditLogService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("call_workflow")
                .description(
                        "Triggers one of your organization's saved workflows (see list_my_workflows for valid "
                                + "workflow_id values) and waits for it to finish, returning its final status and full "
                                + "execution trace. This is heavier than call_agent - a workflow can call multiple agents, "
                                + "tools, and external actions in one run, and may return status AWAITING_APPROVAL if the "
                                + "workflow contains a step that requires a human to approve it in the platform before "
                                + "continuing.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "workflow_id": {"type": "string", "description": "One of the ids returned by list_my_workflows."},
                        "input": {"type": "string", "description": "Optional trigger input/message passed into the workflow's starting node."}
                    },
                    "required": ["workflow_id"]
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        McpCallerContext caller =
                McpCallerContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "call_workflow invoked with no McpCallerContext set - McpApiKeyAuthFilter should have rejected "
                                                        + "this request before it ever reached tool dispatch."));
        try {
            JsonNode inputNode = MAPPER.readTree(toolInput);
            String workflowIdRaw = textOrNull(inputNode, "workflow_id");
            String input = textOrNull(inputNode, "input");

            if (!StringUtils.hasText(workflowIdRaw)) {
                return errorJson("workflow_id is required.");
            }
            UUID workflowId;
            try {
                workflowId = UUID.fromString(workflowIdRaw);
            } catch (IllegalArgumentException e) {
                return errorJson(
                        "workflow_id is not a valid id - call list_my_workflows to see valid ids.");
            }
            if (!caller.isWorkflowExposed(workflowId)) {
                log.warn(
                        "MCP grant {} attempted to call workflow {} which is not in its exposed-workflow set - rejected",
                        caller.grantId(),
                        workflowId);
                // Same "a rejected out-of-scope attempt is itself compliance-relevant" posture as
                // McpCallAgentToolCallback - see that class's recordAudit javadoc.
                recordAudit(
                        caller.grantId(),
                        workflowIdRaw,
                        AuditOutcome.FAILURE,
                        "MCP grant "
                                + caller.grantId()
                                + " attempted to call workflow "
                                + workflowIdRaw
                                + ", which is outside its exposed-workflow set.");
                return errorJson(
                        "This API key is not authorized to call that workflow - call list_my_workflows to see which workflows you can use.");
            }

            // Same traceability posture as McpCallAgentToolCallback's subjectKey.
            String requestedBy = "mcp-external:" + caller.grantId();
            WorkflowRunResponse response =
                    workflowService.run(workflowId, input == null ? "" : input, requestedBy);
            recordAudit(
                    caller.grantId(),
                    workflowId.toString(),
                    AuditOutcome.SUCCESS,
                    "MCP grant "
                            + caller.grantId()
                            + " invoked workflow "
                            + workflowId
                            + " (run "
                            + (response.id() == null ? "unknown" : response.id())
                            + ", status "
                            + (response.status() == null ? "unknown" : response.status())
                            + ").");
            return MAPPER.writeValueAsString(toResultMap(response));
        } catch (Exception e) {
            log.warn("call_workflow tool call failed for grant {}", caller.grantId(), e);
            recordAudit(
                    caller.grantId(),
                    null,
                    AuditOutcome.FAILURE,
                    "MCP grant "
                            + caller.grantId()
                            + " call_workflow invocation failed: "
                            + e.getMessage());
            return errorJson("Workflow call failed: " + e.getMessage());
        }
    }

    /**
     * Best-effort {@link AuditLogService#record} wrapper - same "never let logging itself break the
     * real call" posture {@code McpCallAgentToolCallback}'s own {@code recordAudit} already takes.
     * A failure here must never propagate and change what {@link #call(String)} was already about
     * to return.
     */
    private void recordAudit(
            UUID grantId, String workflowIdOrNull, AuditOutcome outcome, String detail) {
        try {
            auditLogService.record(
                    "mcp-external:" + grantId,
                    "mcp.grant.call_workflow",
                    "Workflow",
                    workflowIdOrNull,
                    outcome,
                    detail);
        } catch (RuntimeException auditFailure) {
            log.warn(
                    "Failed to record audit log entry for MCP grant {} call_workflow invocation",
                    grantId,
                    auditFailure);
        }
    }

    private Map<String, Object> toResultMap(WorkflowRunResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id", response.id() == null ? null : response.id().toString());
        result.put(
                "workflow_id",
                response.workflowId() == null ? null : response.workflowId().toString());
        result.put("status", response.status() == null ? null : response.status().name());
        if (StringUtils.hasText(response.errorMessage())) {
            result.put("error_message", response.errorMessage());
        }
        List<NodeTraceEntry> trace = response.trace();
        if (trace != null && !trace.isEmpty()) {
            result.put(
                    "trace",
                    trace.stream()
                            .map(
                                    entry -> {
                                        Map<String, Object> entryMap = new LinkedHashMap<>();
                                        entryMap.put(
                                                "node",
                                                StringUtils.hasText(entry.label())
                                                        ? entry.label()
                                                        : entry.nodeId());
                                        entryMap.put("node_type", entry.nodeType());
                                        entryMap.put("status", entry.status());
                                        entryMap.put("summary", entry.summary());
                                        return entryMap;
                                    })
                            .toList());
        }
        if (StringUtils.hasText(response.outputFileName())) {
            result.put("output_file_name", response.outputFileName());
        }
        return result;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"" + message.replace("\"", "'") + "\"}";
        }
    }
}
