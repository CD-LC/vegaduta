package com.agenticai.core.mcpserver.expose;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.mcpserver.McpCallerContext;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.WorkflowStatus;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves WHICH dynamic tools ({@code agent_<slug>}, {@code workflow_<slug>}, {@code get_run})
 * one authenticated caller may see and call. The result is the intersection of three independent
 * decisions, so no single mistake widens access:
 *
 * <ol>
 *   <li>the resource's own switch - an enabled {@code mcp_tool_exposure} row ({@link
 *       McpToolExposureService#listExposed}, which reads only the CURRENT tenant's schema, taken
 *       from the authenticated principal's {@code TenantContext}, never from anything the client
 *       sent);</li>
 *   <li>the caller's grant - the agent / workflow id must be in the {@link McpCallerContext} sets
 *       the auth filter loaded from the API key's own grant row;</li>
 *   <li>the resource still resolves (a deleted agent, or an archived workflow, drops out instead of
 *       failing the whole listing).</li>
 * </ol>
 *
 * <p>This is deliberately a per-caller computation made on every {@code tools/list}: Spring AI's
 * MCP server registers tools application-wide, so advertising per-tenant tools through it would
 * leak across tenants (see {@code McpServerToolConfig}'s javadoc). The handler that uses this
 * class answers {@code tools/list} / {@code tools/call} for these names itself.
 *
 * <p>Output is sorted by tool name so the listing is stable between calls.
 */
public class McpExposedToolCatalog {

    private static final Logger log = LoggerFactory.getLogger(McpExposedToolCatalog.class);

    /** The shared poll tool, advertised only when the caller can see at least one workflow tool. */
    public static final String GET_RUN_TOOL = "get_run";

    private static final String GET_RUN_SCHEMA = """
        {"type":"object","properties":{"run_id":{"type":"string","description":"The run_id returned by a workflow_* tool."}},"required":["run_id"]}""";

    private static final String AGENT_SCHEMA = """
        {"type":"object","properties":{"message":{"type":"string","description":"The message to send to the agent."},"conversationId":{"type":"string","description":"Optional. Reuse the conversationId from a previous reply to continue that conversation; omit to start a new one."}},"required":["message"]}""";

    private final McpToolExposureService exposureService;
    private final AgentService agentService;
    private final WorkflowService workflowService;

    public McpExposedToolCatalog(
        McpToolExposureService exposureService, AgentService agentService, WorkflowService workflowService
    ) {
        this.exposureService = exposureService;
        this.agentService = agentService;
        this.workflowService = workflowService;
    }

    /** Every dynamic tool this caller may see, sorted by name; empty when nothing is both exposed and granted. */
    public List<McpExposedTool> forCaller(McpCallerContext caller) {
        if (caller == null) {
            return List.of();
        }
        List<McpExposedTool> tools = new ArrayList<>();
        boolean anyWorkflow = false;
        for (McpToolExposure row : exposureService.listExposed()) {
            if (row.getKind() == McpExposureKind.AGENT && caller.isAgentExposed(row.getResourceId())) {
                resolveAgent(row).ifPresent(tools::add);
            } else if (row.getKind() == McpExposureKind.WORKFLOW && caller.isWorkflowExposed(row.getResourceId())) {
                Optional<McpExposedTool> workflowTool = resolveWorkflow(row);
                if (workflowTool.isPresent()) {
                    tools.add(workflowTool.get());
                    anyWorkflow = true;
                }
            }
        }
        if (anyWorkflow) {
            tools.add(new McpExposedTool(
                GET_RUN_TOOL,
                "Check a workflow run started by one of the workflow_* tools and return its status and result.",
                GET_RUN_SCHEMA, McpExposedTool.Target.GET_RUN, null, "get_run"));
        }
        tools.sort(Comparator.comparing(McpExposedTool::name));
        return List.copyOf(tools);
    }

    /** The named tool, only if it is in this caller's own listing - never a lookup by name across tenants or grants. */
    public Optional<McpExposedTool> find(McpCallerContext caller, String toolName) {
        if (toolName == null) {
            return Optional.empty();
        }
        return forCaller(caller).stream().filter(tool -> tool.name().equals(toolName)).findFirst();
    }

    private Optional<McpExposedTool> resolveAgent(McpToolExposure row) {
        try {
            AgentResponse agent = agentService.getGrantedAgent(row.getResourceId());
            String name = agent.name() == null ? "agent" : agent.name();
            return Optional.of(new McpExposedTool(
                row.getToolName(),
                "Send a message to the agent \"" + name + "\" and get its reply. Pass conversationId from a previous reply to continue that conversation.",
                AGENT_SCHEMA, McpExposedTool.Target.AGENT, row.getResourceId(), name));
        } catch (RuntimeException e) {
            log.warn("MCP exposure {} references agent {} which no longer resolves - omitting it", row.getToolName(), row.getResourceId());
            return Optional.empty();
        }
    }

    private Optional<McpExposedTool> resolveWorkflow(McpToolExposure row) {
        try {
            WorkflowDefinition workflow = workflowService.get(row.getResourceId());
            if (workflow.getStatus() == WorkflowStatus.ARCHIVED) {
                return Optional.empty();
            }
            String name = workflow.getName() == null ? "workflow" : workflow.getName();
            return Optional.of(new McpExposedTool(
                row.getToolName(),
                "Run the workflow \"" + name + "\" and return its result. Long runs return status RUNNING and a run_id - poll with get_run.",
                schemaFor(workflow), McpExposedTool.Target.WORKFLOW, row.getResourceId(), name));
        } catch (RuntimeException e) {
            log.warn("MCP exposure {} references workflow {} which no longer resolves - omitting it", row.getToolName(), row.getResourceId());
            return Optional.empty();
        }
    }

    /** Input schema from the Trigger node's declared inputs when there are any, else an open object. */
    private String schemaFor(WorkflowDefinition workflow) {
        try {
            WorkflowGraphDto graph = workflowService.resolveRunGraph(workflow);
            for (WorkflowNodeDto node : graph.nodes()) {
                if (node.type() != null && "trigger".equalsIgnoreCase(node.type())) {
                    Map<String, Object> config = node.config();
                    return McpWorkflowInputSchema.fromTriggerConfig(config);
                }
            }
        } catch (RuntimeException e) {
            log.debug("Could not derive an input schema for workflow {} - using an open object", workflow.getId(), e);
        }
        return McpWorkflowInputSchema.openSchemaJson();
    }
}
