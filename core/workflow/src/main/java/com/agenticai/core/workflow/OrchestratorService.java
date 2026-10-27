package com.agenticai.core.workflow;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The governance layer the user asked for directly: "orchestrator, which does the governance and
 * ensure nothing goes wrong." Two distinct responsibilities, deliberately kept in one class because
 * they are two halves of the same job (a workflow author and a runtime caller should both go
 * through "the thing that keeps this platform safe", not two unrelated services that happen to both
 * check things):
 *
 * <ul>
 *   <li>{@link #validate} - structural governance, pre-flight. Runs every time a graph is saved or
 *       activated: no cycles, every node type is one this engine can actually execute, every node's
 *       required config is present and well-formed, every edge references a real node. Nothing with
 *       a structural problem is ever allowed to run.
 *   <li>{@link #authorizeSensitiveNode} - runtime governance, in-flight. Called by {@link
 *       WorkflowExecutionEngine} before any node marked {@code requiresApproval} in its config
 *       actually executes, routing through the exact same {@link HumanApprovalGateService} every
 *       other side-effecting action in this platform already goes through - a workflow does not get
 *       its own separate, weaker safety net.
 * </ul>
 */
@Service
public class OrchestratorService {

    /**
     * Defense in depth against a pathological/hand-crafted graph, mirroring {@code
     * ToolStepLimitExceededException}'s guardrail for a single agent's tool-calling loop. {@link
     * WorkflowExecutionEngine} enforces this independently of validation.
     */
    public static final int MAX_STEPS_PER_RUN = 200;

    private final HumanApprovalGateService approvalGateService;
    private final ObjectMapper objectMapper;
    private final WorkflowNodeConfigValidator nodeConfigValidator;
    // ObjectProvider, not a direct field: the notification bell is a
    // best-effort side signal (Tier-3), never a reason an approval gate
    // should fail to evaluate - same injection posture
    // AgentToolCallApprovalExecutor documents for its own notify path.
    private final ObjectProvider<NotificationService> notificationService;

    // nlflow W5: same flag WorkflowNodeExecutor reads. Off (default) = an Output node's
    // whatsappTemplate
    // config is ignored at send time, so it must not block save / activate / run here either.
    // Field-injected so the constructor keeps its arity for the hand-built instances in tests.
    @Value("${app.omnichannel.whatsapp.templates.enabled:false}")
    private boolean whatsAppTemplatesEnabled = false;

    void setWhatsAppTemplatesEnabled(boolean enabled) {
        this.whatsAppTemplatesEnabled = enabled;
    }

    // wfcontrol: Wait node and hosted-form trigger. Field-injected (default off) for the same arity
    // reason as above.
    // Off = a graph containing one is REJECTED here with a clear message, never silently skipped or
    // run as a plain
    // trigger.
    @Value("${app.workflow.wait-node.enabled:false}")
    private boolean waitNodeEnabled = false;

    @Value("${app.workflow.wait-node.max-duration-days:7}")
    private long waitMaxDurationDays = WorkflowWaitService.DEFAULT_MAX_DAYS;

    @Value("${app.workflow.form-trigger.enabled:false}")
    private boolean formTriggerEnabled = false;

    @Value("${app.workflow.execution-engine:legacy}")
    private String executionEngine = "legacy";

    void setExecutionEngine(String engine) {
        this.executionEngine = engine;
    }

    void setWaitNodeEnabled(boolean enabled) {
        this.waitNodeEnabled = enabled;
    }

    void setFormTriggerEnabled(boolean enabled) {
        this.formTriggerEnabled = enabled;
    }

    public OrchestratorService(
            AgentService agentService,
            HumanApprovalGateService approvalGateService,
            ObjectMapper objectMapper,
            ObjectProvider<NotificationService> notificationService) {
        this.approvalGateService = approvalGateService;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
        this.nodeConfigValidator = new WorkflowNodeConfigValidator(agentService, objectMapper);
    }

    /**
     * @throws WorkflowValidationException carrying every problem found, not just the first, so the
     *     canvas can flag every offending node in one round trip.
     *     <p>2026-08-04 fix: this runs on EVERY execution ({@code
     *     WorkflowExecutionEngine#execute}'s "defense in depth" call), not just save/activate - so
     *     its Agent/Tool/Supervisor node checks must use {@link AgentService#requireExists}
     *     (tenant-scoped existence only), not {@link AgentService#get} (per-user access-checked).
     *     {@code get()} here silently broke every channel/schedule/webhook-triggered run of any
     *     workflow with such a node - see {@link AgentService#requireExists}'s javadoc for the full
     *     incident.
     */
    public void validate(WorkflowGraphDto graph) {
        validate(graph, null);
    }

    /**
     * @param ownWorkflowId the id of the workflow definition this graph belongs to, so a {@code
     *     workflow} node's self-reference (targeting its own containing workflow, which would
     *     trigger itself forever) can be rejected here. {@code null} for a graph that has no id yet
     *     (a brand-new, unsaved AI-generated graph - see {@code WorkflowGraphGeneratorService}) -
     *     self-reference simply can't be checked yet in that case, since there is no id to compare
     *     against; it's still caught the first time this same graph is validated again with a real
     *     id (every save/activate/run path already does).
     */
    public void validate(WorkflowGraphDto graph, UUID ownWorkflowId) {
        validate(graph, ownWorkflowId, Set.of());
    }

    /**
     * Same gate as {@link #validate(WorkflowGraphDto, UUID)}, except agent ids in {@code
     * assumedAgentIds} count as existing. Used by the Brief validator, whose graphs reference
     * agents that are only created at apply time (refs mapped to placeholder UUIDs); every other
     * structural rule still runs unchanged.
     */
    public void validate(WorkflowGraphDto graph, UUID ownWorkflowId, Set<UUID> assumedAgentIds) {
        List<String> errors = new ArrayList<>();

        if (graph.nodes().isEmpty()) {
            errors.add("The graph has no nodes - add at least a Trigger node to get started.");
            throw new WorkflowValidationException(errors);
        }

        Set<String> nodeIds = new HashSet<>();
        Map<String, WorkflowNodeDto> nodesById = new HashMap<>();
        for (WorkflowNodeDto node : graph.nodes()) {
            if (node.id() == null || node.id().isBlank()) {
                errors.add("Every node must have an id.");
                continue;
            }
            if (!nodeIds.add(node.id())) {
                errors.add("Duplicate node id '" + node.id() + "' - node ids must be unique.");
            }
            nodesById.put(node.id(), node);
        }

        // Per-node workflow triggers - "each trigger node in a workflow's
        // graph can be independently bound to its own inbound channel"
        // replaces the old "exactly one Trigger node total" model, so a
        // graph with several Trigger nodes is now structurally valid (the
        // triggerCount > 1 branch this used to have is gone) - it just still
        // needs at least one, same as before.
        long triggerCount =
                graph.nodes().stream().filter(n -> "trigger".equalsIgnoreCase(n.type())).count();
        if (triggerCount == 0) {
            errors.add("The graph needs exactly one Trigger node to start a run.");
        }

        WorkflowNodeConfigValidator.Flags flags =
                new WorkflowNodeConfigValidator.Flags(
                        whatsAppTemplatesEnabled,
                        formTriggerEnabled,
                        waitNodeEnabled,
                        waitMaxDurationDays);
        for (WorkflowNodeDto node : graph.nodes()) {
            validateNodeType(node, errors);
            nodeConfigValidator.validate(node, errors, ownWorkflowId, assumedAgentIds, flags);
        }

        // Per-node workflow triggers, continued: each Trigger node's own
        // 'channel' config (validated for presence/shape above, inside
        // WorkflowNodeConfigValidator's "trigger" case) must be unique across the
        // graph's trigger nodes UNLESS it's WEBHOOK - a WEBHOOK-bound trigger
        // node mints its own independent token, so the same WEBHOOK value
        // legitimately appears on more than one trigger node, but two trigger
        // nodes both claiming e.g. WHATSAPP would leave that inbound channel
        // pointing at two different start nodes with no way to pick between
        // them.
        Map<String, List<String>> triggerNodeLabelsByChannel = new HashMap<>();
        for (WorkflowNodeDto node : graph.nodes()) {
            if (!"trigger".equalsIgnoreCase(node.type())) {
                continue;
            }
            Object channelRaw = node.config().get("channel");
            if (channelRaw == null || channelRaw.toString().isBlank()) {
                continue; // already flagged by WorkflowNodeConfigValidator's "trigger" case above
            }
            triggerNodeLabelsByChannel
                    .computeIfAbsent(
                            channelRaw.toString().toUpperCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(node.label());
        }
        for (Map.Entry<String, List<String>> entry : triggerNodeLabelsByChannel.entrySet()) {
            if (WorkflowTriggerChannel.WEBHOOK.name().equals(entry.getKey())
                    || entry.getValue().size() <= 1) {
                continue;
            }
            errors.add(
                    "Multiple trigger nodes are bound to channel "
                            + entry.getKey()
                            + " - only one per workflow is allowed.");
        }

        Map<String, List<String>> adjacency = new HashMap<>();
        for (WorkflowEdgeDto edge : graph.edges()) {
            if (!nodesById.containsKey(edge.source()) || !nodesById.containsKey(edge.target())) {
                errors.add(
                        "Edge '"
                                + edge.id()
                                + "' references a node id that doesn't exist in this graph.");
                continue;
            }
            adjacency.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge.target());
        }

        // Loop/Iterator ("repeat the next node per item") needs exactly one
        // downstream node to repeat - this is a graph-shape requirement, not
        // per-node config, so it's checked here against the real adjacency
        // rather than inside WorkflowNodeConfigValidator. Deliberately NOT a cycle:
        // the loop node's single outgoing edge points at its body node like
        // any other edge, and execution never creates a back edge - see
        // WorkflowNodeType's javadoc.
        for (WorkflowNodeDto node : graph.nodes()) {
            if (!"loop".equalsIgnoreCase(node.type())) {
                continue;
            }
            List<String> outs = adjacency.getOrDefault(node.id(), List.of());
            if (outs.isEmpty()) {
                errors.add(
                        "Loop node '"
                                + node.label()
                                + "' needs exactly one outgoing connection to the node that should repeat per item.");
            } else if (outs.size() > 1) {
                errors.add(
                        "Loop node '"
                                + node.label()
                                + "' has more than one outgoing connection - it can only repeat a single downstream node.");
            }
        }

        // Parallel Fan-Out (Master Roadmap 2026 item #12, §E) needs at least
        // one downstream branch, the same "the engine can't do anything
        // meaningful with zero outgoing edges" reasoning as Loop's check
        // above - see WorkflowExecutionEngine#execute's parallel-handling
        // branch, which throws exactly this condition again at runtime as a
        // defense-in-depth backstop for any graph saved before this rule
        // existed. Deliberately NOT enforcing "every branch converges on the
        // same join node" here - see WorkflowNodeType's javadoc on why that
        // stays a convention the runtime tolerates rather than a hard
        // save-time invariant this validator would need real join-analysis
        // to check correctly.
        for (WorkflowNodeDto node : graph.nodes()) {
            if (!"parallel".equalsIgnoreCase(node.type())) {
                continue;
            }
            List<String> outs = adjacency.getOrDefault(node.id(), List.of());
            if (outs.isEmpty()) {
                errors.add(
                        "Parallel node '"
                                + node.label()
                                + "' needs at least one outgoing connection to a branch to fan out to.");
            } else if (outs.size() > WorkflowExecutionEngine.MAX_PARALLEL_BRANCHES) {
                // Previously only checked at runtime (WorkflowExecutionEngine#execute),
                // which meant a graph with too many branches saved and activated clean
                // and then failed on its very first run. Branch count is fully known
                // from the graph's own edges at save time - unlike Loop's iteration
                // cap, which genuinely can't be known until runtime data arrives - so
                // there's no reason to defer this one to a run that's guaranteed to fail.
                errors.add(
                        "Parallel node '"
                                + node.label()
                                + "' has "
                                + outs.size()
                                + " branches, more than the "
                                + WorkflowExecutionEngine.MAX_PARALLEL_BRANCHES
                                + " this engine allows in one fan-out.");
            }
        }

        // Dynamic Fan-Out needs LOOP's graph shape (exactly one outgoing edge,
        // to the worker node that runs once per item), NOT Parallel's N-edges
        // shape, and this is load-bearing rather than stylistic: a Fan-out
        // node's "branch set" is the runtime list its input resolves to, so a
        // second outgoing edge would have no item to attach to and no defined
        // meaning - and the single-worker shape is what gives the post-fan-in
        // join ONE unambiguous next hop (resolved off the worker node, exactly
        // like Loop's) instead of inheriting Parallel's documented
        // "continues from whichever branch resolved last" limitation. Note
        // what is deliberately NOT checked here: the item/concurrency caps.
        // Unlike Parallel's branch count - fully knowable from the graph's own
        // edges, hence its save-time ceiling check above - a Fan-out node's
        // width is runtime data that cannot exist at save time; its numeric
        // config knobs (maxItems/maxConcurrency) are clamped by both engines
        // at run time, same as Loop's maxIterations. Cycle detection below
        // needs no special case either: like Loop, execution never creates a
        // back edge (the worker's next-hop resolves once, after fan-in), so
        // the graph stays a DAG - see WorkflowNodeType's FAN_OUT javadoc.
        for (WorkflowNodeDto node : graph.nodes()) {
            if (!"fan_out".equalsIgnoreCase(node.type())) {
                continue;
            }
            List<String> outs = adjacency.getOrDefault(node.id(), List.of());
            if (outs.isEmpty()) {
                errors.add(
                        "Fan-out node '"
                                + node.label()
                                + "' needs exactly one outgoing connection to the worker node that should run once per item.");
            } else if (outs.size() > 1) {
                errors.add(
                        "Fan-out node '"
                                + node.label()
                                + "' has more than one outgoing connection - it runs a single worker node per item; "
                                + "the item list comes from its runtime input, not from extra edges.");
            }
        }

        // Switch edges must leave from one of its case ports (see
        // WorkflowDataNodeValidator#validateGraph).
        WorkflowDataNodeValidator.validateGraph(graph, errors);
        // A Wait node parks the whole run: refuse placements the engine can only fail on at run
        // time.
        WorkflowWaitPlacement.validate(
                graph.nodes(),
                nodesById,
                adjacency,
                "temporal".equalsIgnoreCase(executionEngine),
                errors);
        // An approval / human-input halt inside a container body parks the run with a resume
        // envelope that carries nodeId + input but no iteration cursor - same placement rule.
        WorkflowApprovalPlacement.validate(graph.nodes(), nodesById, adjacency, errors);

        detectCycle(nodeIds, adjacency, errors);

        if (!errors.isEmpty()) {
            throw new WorkflowValidationException(errors);
        }
    }

    private void validateNodeType(WorkflowNodeDto node, List<String> errors) {
        WorkflowNodeType type;
        try {
            type = WorkflowNodeType.fromWire(node.type());
        } catch (IllegalArgumentException | NullPointerException e) {
            errors.add(
                    "Node '" + node.label() + "' has an unrecognized type '" + node.type() + "'.");
            return;
        }
        if (!type.isSupported()) {
            errors.add(
                    "Node '"
                            + node.label()
                            + "' uses type '"
                            + node.type()
                            + "', which is not yet supported by the execution engine (coming in a later phase).");
        }
    }

    private void detectCycle(
            Set<String> nodeIds, Map<String, List<String>> adjacency, List<String> errors) {
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        for (String start : nodeIds) {
            if (!visited.contains(start)) {
                visited.add(start);
                inStack.add(start);
                if (dfs(start, adjacency, visited, inStack)) {
                    errors.add(
                            "The graph contains a cycle reachable from node '"
                                    + start
                                    + "' - loops aren't supported yet.");
                    return;
                }
                inStack.remove(start);
            }
        }
    }

    private boolean dfs(
            String node,
            Map<String, List<String>> adjacency,
            Set<String> visited,
            Set<String> inStack) {
        for (String next : adjacency.getOrDefault(node, List.of())) {
            if (inStack.contains(next)) {
                return true;
            }
            if (!visited.contains(next)) {
                visited.add(next);
                inStack.add(next);
                if (dfs(next, adjacency, visited, inStack)) {
                    return true;
                }
                inStack.remove(next);
            }
        }
        return false;
    }

    /**
     * Runtime governance gate: called by {@link WorkflowExecutionEngine} immediately before
     * executing any node whose config sets {@code requiresApproval: true}, and unconditionally
     * before every Approval node (that node's entire purpose is a human checkpoint). Routes through
     * the exact same {@link HumanApprovalGateService} the rest of the platform uses - a workflow
     * node is never a second, weaker way to do something the approval policy engine would otherwise
     * gate.
     *
     * <p>{@code adminOnly} (an Approval node's own per-node config, see {@code
     * NodeConfigDrawer.js}) rides along inside {@code payloadJson} rather than as a new
     * column/param threaded through {@link HumanApprovalGateService} - the same "opaque per-request
     * JSON blob" shape every other approval-triggering caller in this codebase already uses that
     * field for. {@code ApprovalController} reads it back out at decision time to require a {@code
     * ROLE_tenant-admin} caller before letting the request be approved or rejected at all - a
     * UI-only picker with no server-side check would be cosmetic, not a real restriction.
     */
    public ApprovalEvaluation authorizeSensitiveNode(
            String workflowName,
            String runId,
            WorkflowNodeDto node,
            String summary,
            String requestedBy) {
        return authorizeSensitiveNode(workflowName, runId, node, summary, requestedBy, null);
    }

    /**
     * Approval-node resume support (F-05, second half): the cap below bounds what one approval row
     * can add to the {@code approval_request} table - a node input is normally an agent reply or a
     * knowledge lookup, far under this. Over-cap input is deliberately NOT truncated-and-recorded:
     * resuming a run on silently truncated data would corrupt every downstream node's input, so the
     * payload simply omits it and {@code WorkflowApprovalActionExecutor} declines that resume with
     * an honest message instead (the request still gates and audits normally).
     */
    static final int MAX_RECORDED_NODE_INPUT_CHARS = 100_000;

    /**
     * Replay-capable overload (mirrors {@code HumanApprovalGateService}'s 7-arg {@code
     * recordToolCallForApproval}): {@code nodeInput} is the exact input the gated node was about to
     * receive - i.e. the engine's {@code currentOutput} at halt time, which the trace does NOT
     * carry (trace entries store summaries, not outputs). Recording it here is what makes {@code
     * WorkflowApprovalActionExecutor}'s resume possible without a schema change: everything rides
     * in the existing {@code payloadJson} blob, under a nested {@code "resume"} object so the
     * top-level keys ({@code adminOnly} read by {@code ApprovalController}, {@code runId} read by
     * the RL feedback hook) keep their exact pre-existing shape. Tenant/subject are captured from
     * the CURRENT thread's context - this runs on the original run's request thread - so the resume
     * can later restore the run's own tenant rather than trusting whichever thread approves it.
     *
     * <p>Payload is now built with Jackson rather than the old hand-escaped string concat - {@code
     * nodeInput} is arbitrary upstream-node output and hand-escaping it correctly is exactly the
     * bug class ObjectNode exists to prevent.
     */
    public ApprovalEvaluation authorizeSensitiveNode(
            String workflowName,
            String runId,
            WorkflowNodeDto node,
            String summary,
            String requestedBy,
            String nodeInput) {
        String actionType = "workflow.node." + node.type().toLowerCase(Locale.ROOT);
        boolean adminOnly = Boolean.TRUE.equals(node.config().get("adminOnly"));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("workflowName", workflowName);
        payload.put("runId", runId);
        payload.put("nodeId", node.id());
        payload.put("adminOnly", adminOnly);
        // Human-input: the labels the approver is asked to fill in. Their answers come back on
        // approve and are appended to this node's output (WorkflowApprovalActionExecutor).
        if (node.config().get("formFields") instanceof Collection<?> fields) {
            ArrayNode declared = payload.putArray("formFields");
            fields.stream()
                    .filter(f -> f != null && !f.toString().isBlank())
                    .map(f -> f.toString().trim())
                    .map(f -> f.length() > 80 ? f.substring(0, 80) : f)
                    .distinct()
                    .limit(12)
                    .forEach(declared::add);
        }
        if (nodeInput != null && nodeInput.length() <= MAX_RECORDED_NODE_INPUT_CHARS) {
            ObjectNode resume = payload.putObject("resume");
            resume.put("version", 1);
            resume.put("nodeInput", nodeInput);
            TenantContext.current()
                    .ifPresent(
                            context -> {
                                resume.put("tenantId", context.getTenantId());
                                resume.put("subject", context.getSubject());
                            });
        }
        ApprovalEvaluation evaluation =
                approvalGateService.evaluate(actionType, summary, payload.toString(), requestedBy);
        // Notifications (Tier-3): the moment a run actually HALTS for a
        // human is the moment someone must be told - "pending approvals
        // require polling the console" was the audit gap this closes.
        // Broadcast (recipientUserId null) to tenant-admins: they are the
        // approver pool by definition, and the requesting user may not even
        // be an approver. INFO not WARNING - a pending approval is the
        // system working as designed, not a fault.
        if (!evaluation.approved() && evaluation.pendingRequestId() != null) {
            try {
                NotificationService service = notificationService.getIfAvailable();
                if (service != null) {
                    service.create(
                            null,
                            NotificationCategory.WORKFLOW,
                            "Workflow '" + workflowName + "' is awaiting approval",
                            "Node '" + node.label() + "' paused the run pending a human decision.",
                            NotificationSeverity.INFO,
                            null,
                            null);
                }
            } catch (RuntimeException notifyFailure) {
                // Best-effort: a bell-write failure must never change the
                // approval outcome - same convention NotificationService's
                // own javadoc sets for every call site.
            }
        }
        return evaluation;
    }
}
