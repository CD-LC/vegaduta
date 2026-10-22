package com.agenticai.core.workflow;

import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Single-node execution logic, extracted out of {@link WorkflowExecutionEngine} so it has exactly
 * one home shared by both execution paths this codebase now has:
 *
 * <ul>
 *   <li>{@link WorkflowExecutionEngine} - the original synchronous, in-process path (still the
 *       default; the canvas's "Run" button and webhook/schedule triggers all still go through it
 *       unless the new {@code app.workflow.execution-engine: temporal} flag is set).
 *   <li>{@code com.agenticai.core.workflow.temporal.WorkflowNodeActivitiesImpl} - the new Temporal
 *       Activity implementation, invoked once per node from inside a durable, crash-resumable
 *       {@code WorkflowRunWorkflowImpl} execution.
 * </ul>
 *
 * <p>This class is the thin dispatcher: it owns the cross-cutting steps (pin lookup, approval gate,
 * expression resolution, dry run, run-data recording) and delegates the node itself to the {@link
 * NodeHandler} registered for its type (one handler class per node type, injected as a {@code
 * List<NodeHandler>}).
 *
 * <p>Nothing about a single node's execution semantics changes by this extraction - every method
 * body here is unchanged from what previously lived directly on {@code WorkflowExecutionEngine},
 * just moved. The run-level concerns (graph traversal, trace persistence, run-row lifecycle,
 * lifecycle event publishing) stay wherever the caller lives, since those differ meaningfully
 * between the two paths (the synchronous engine does them in-process; the Temporal path does them
 * via its own separate Activities - see {@code WorkflowRunWorkflowImpl}'s javadoc for why a
 * Temporal Workflow method can't safely do direct DB/Kafka I/O itself).
 */
@Component
public class WorkflowNodeExecutor {

    private final OrchestratorService orchestratorService;

    /** Registry: lower-case node type to the single handler that executes it. */
    private final Map<String, NodeHandler> handlers;

    // Expression resolver (spec 4.2): resolves the node's {{ }} config fields before dispatch and
    // records outputs for $node. Defaults to a store-less resolver so hand-built executors (tests)
    // still template {{input}}/{{field}}; Spring replaces it with the real bean, which reads/writes
    // the run data store.
    private WorkflowExpressionResolver expressions;

    // Pins (V374): read only for draft/test runs, through ExpressionScope#honoursPins.
    // Setter-injected so hand-constructed executors in tests keep compiling; null = pins are never
    // served.
    private WorkflowPinStore pinStore;

    @Autowired(required = false)
    void setPinStore(WorkflowPinStore pinStore) {
        this.pinStore = pinStore;
    }

    /**
     * Node types that decide WHERE the run goes next rather than produce data. A pin carries no
     * routing decision, so serving one would send the run down an arbitrary edge; they always
     * execute, pinned or not. (loop, parallel, fan_out and sub_flow are engine-level containers and
     * never reach this class.)
     */
    static final java.util.Set<String> PIN_NOT_HONOURED_TYPES =
            java.util.Set.of("condition", "supervisor", "switch", "question_classifier");

    // nlflow W5: WhatsApp approved-template sends from the Output node. Field-injected (default
    // off)
    // and handed to handlers through the NodeInvocation; off = a node's template config is ignored
    // and plain text is sent, exactly as before.
    @org.springframework.beans.factory.annotation.Value(
            "${app.omnichannel.whatsapp.templates.enabled:false}")
    private boolean whatsAppTemplatesEnabled = false;

    void setWhatsAppTemplatesEnabled(boolean enabled) {
        this.whatsAppTemplatesEnabled = enabled;
    }

    @Autowired(required = false)
    void setExpressions(WorkflowExpressionResolver expressions) {
        if (expressions != null) {
            this.expressions = expressions;
        }
    }

    /**
     * @param handlers every {@link NodeHandler} bean; each claims its node types, and two handlers
     *     claiming the same type is a wiring error
     */
    @Autowired
    public WorkflowNodeExecutor(
            OrchestratorService orchestratorService,
            ObjectMapper objectMapper,
            List<NodeHandler> handlers) {
        this.orchestratorService = orchestratorService;
        Map<String, NodeHandler> registry = new java.util.HashMap<>();
        for (NodeHandler handler : handlers) {
            for (String type : handler.nodeTypes()) {
                NodeHandler previous = registry.put(type, handler);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Node type '"
                                    + type
                                    + "' is claimed by both "
                                    + previous.getClass().getSimpleName()
                                    + " and "
                                    + handler.getClass().getSimpleName());
                }
            }
        }
        this.handlers = Map.copyOf(registry);
        this.expressions = WorkflowExpressionResolver.standalone(objectMapper);
    }

    /**
     * @param workflowDefinitionId/{@code workflowDefinitionName} deliberately plain strings, not
     *     the {@link WorkflowDefinition} JPA entity - the only two fields any node handler below
     *     actually reads off it. This keeps this class callable from the Temporal activity path too
     *     (Activity inputs are plain serializable data, never a live Hibernate entity reference
     *     from a different execution/thread).
     *     <p>Delegates to the 7-arg overload with {@link WorkflowIntelligenceSettings#defaults()}
     *     so every existing caller (Temporal activities, tests) compiles unchanged.
     */
    public NodeExecutionResult executeNode(
            String workflowDefinitionId,
            String workflowDefinitionName,
            String runId,
            WorkflowNodeDto node,
            String input,
            String requestedBy) {
        return executeNode(
                workflowDefinitionId,
                workflowDefinitionName,
                runId,
                node,
                input,
                requestedBy,
                WorkflowIntelligenceSettings.defaults());
    }

    /**
     * Full entry point with per-workflow intelligence settings (step caching, cost routing). Called
     * by {@link WorkflowExecutionEngine} via {@code executeNodeWithRetry}, which passes {@link
     * WorkflowIntelligenceSettings#from(WorkflowDefinition)} built from the definition currently
     * executing. The 6-arg overload above delegates here with {@link
     * WorkflowIntelligenceSettings#defaults()} for every caller that does not yet supply settings.
     */
    public NodeExecutionResult executeNode(
            String workflowDefinitionId,
            String workflowDefinitionName,
            String runId,
            WorkflowNodeDto node,
            String input,
            String requestedBy,
            WorkflowIntelligenceSettings intelligenceSettings) {
        return executeNode(
                workflowDefinitionId,
                workflowDefinitionName,
                runId,
                node,
                input,
                requestedBy,
                intelligenceSettings,
                List.of());
    }

    /**
     * Fullest entry point - additionally carries the run's optional inline attachments (Workflow
     * Share mic/video), ONLY meaningful for the single node {@link WorkflowExecutionEngine}
     * determines runs immediately after the Trigger node (see that class's {@code runTraversal}
     * javadoc/ comments). Every other overload above delegates here with {@code List.of()}, so
     * nothing about their behavior changes.
     */
    public NodeExecutionResult executeNode(
            String workflowDefinitionId,
            String workflowDefinitionName,
            String runId,
            WorkflowNodeDto node,
            String input,
            String requestedBy,
            WorkflowIntelligenceSettings intelligenceSettings,
            List<ChatInlineAttachment> attachments) {
        // Draft/test runs: a pinned node is not executed at all (no approval wait, no side effect),
        // its pin stands
        // in for it. Checked before the approval gate on purpose - pin wins in draft, the wait is
        // skipped.
        NodeExecutionResult pinned =
                pinnedResult(
                        workflowDefinitionId,
                        workflowDefinitionName,
                        runId,
                        node,
                        input,
                        requestedBy);
        if (pinned != null) {
            return pinned;
        }

        Map<String, Object> config = node.config();
        boolean requiresApproval =
                "approval".equalsIgnoreCase(node.type())
                        || Boolean.TRUE.equals(config.get("requiresApproval"));

        if (requiresApproval) {
            // Approval-node resume (F-05, second half): when a human already
            // approved this exact halt and WorkflowApprovalActionExecutor is
            // re-entering the graph at this node, the single-use, same-thread
            // ApprovalReplayGrant is how that decision crosses back into this
            // gate - without it, re-executing the node would (correctly,
            // absent the grant) record a SECOND pending request and halt the
            // resumed run all over again, an infinite approve-halt loop. The
            // action-type construction below must stay in sync with
            // OrchestratorService#authorizeSensitiveNode's - duplicated for
            // the same one-string-constant reason ApprovalController
            // documents for the mcp_tool prefix.
            String actionType = "workflow.node." + node.type().toLowerCase(java.util.Locale.ROOT);
            if (ApprovalReplayGrant.consume(actionType)) {
                if ("approval".equalsIgnoreCase(node.type())) {
                    return NodeExecutionResult.ok(
                            input, "Approved by human decision - run resumed.", null);
                }
                // A non-Approval node gated by requiresApproval=true: the
                // human approved THIS node's execution - fall through to the
                // dispatch below and actually run it.
            } else {
                // #640: an Approval node's own "Instructions for approver" config
                // field (free text the workflow author writes, e.g. "double-check
                // the refund amount against the original order before approving")
                // - appended to the summary shown in the Approvals inbox so a
                // decider sees the author's actual guidance, not just the raw
                // truncated input. Absent for every other requiresApproval-flagged
                // node type (agent/output) since only the Approval node type has
                // this config field today (see nodeDefs.js's defaultConfig).
                String customMessage = WorkflowNodeSupport.stringConfig(node, "message", null);
                String summary =
                        "Workflow '"
                                + workflowDefinitionName
                                + "' node '"
                                + node.label()
                                + "' requests approval. Input: "
                                + WorkflowNodeSupport.truncate(input, 500)
                                + (customMessage != null
                                        ? "\n\nInstructions from workflow author: " + customMessage
                                        : "");
                // The full (untruncated) input rides along as the payload's
                // resume envelope - it is the engine's currentOutput at halt
                // time, which nothing else persists, and the resume needs it
                // verbatim. See authorizeSensitiveNode's 6-arg overload.
                ApprovalEvaluation evaluation =
                        orchestratorService.authorizeSensitiveNode(
                                workflowDefinitionName, runId, node, summary, requestedBy, input);
                if (!evaluation.approved()) {
                    return NodeExecutionResult.halted(
                            WorkflowRunStatus.AWAITING_APPROVAL,
                            "Awaiting human approval (request "
                                    + evaluation.pendingRequestId()
                                    + ").");
                }
                if ("approval".equalsIgnoreCase(node.type())) {
                    // SE-52: name what auto-approved it. The approval gate does not report which
                    // policy
                    // matched, so state the action type it evaluated and that no human was asked.
                    return NodeExecutionResult.ok(
                            input,
                            "Auto-approved by policy for action '"
                                    + actionType
                                    + "' (requested by "
                                    + (requestedBy == null ? "unknown" : requestedBy)
                                    + "); no human decision was needed.",
                            null);
                }
                // Falls through: a non-Approval node with requiresApproval=true was
                // auto-approved by policy - execute it normally below.
            }
        }

        // Expression resolution (spec 4.2): the single place {{ }} in this node's config is
        // evaluated, so
        // the handlers below receive final text and never substitute (or re-scan) anything
        // themselves.
        // Past the approval gate on purpose: a node that only halts for a human resolves nothing.
        ExpressionScope scope = ExpressionScopeContext.current();
        WorkflowExpressionResolver.NodeEvalInfo evalInfo =
                new WorkflowExpressionResolver.NodeEvalInfo(
                        workflowDefinitionId, workflowDefinitionName, runId, requestedBy);
        WorkflowNodeDto resolvedNode = expressions.resolve(node, input, evalInfo, scope);
        // Dry run (RunMode.DRAFT_DRY_RUN): a node that would send something out reports it instead.
        // Null = run it.
        NodeExecutionResult dryRun =
                WorkflowDryRun.simulateIfDryRun(
                        resolvedNode, input, scope, whatsAppTemplatesEnabled);
        NodeExecutionResult result =
                dryRun != null
                        ? dryRun
                        : WorkflowDryRun.markRealExecution(
                                resolvedNode,
                                scope,
                                dispatchNode(
                                        resolvedNode,
                                        workflowDefinitionId,
                                        workflowDefinitionName,
                                        runId,
                                        input,
                                        requestedBy,
                                        intelligenceSettings,
                                        attachments,
                                        evalInfo,
                                        scope,
                                        node));
        expressions.record(evalInfo, node, scope, input, result);
        return result;
    }

    /**
     * Records the aggregate output of an engine-level container node (loop, parallel, fan_out,
     * sub_flow). Those never reach {@link #executeNode}, so without this {@code $node["<container
     * label>"]} would read nothing. Seq 0: the container runs once per run; its body nodes carry
     * the per-iteration seq. Best effort, like every run-data write. Both engines call it (the
     * Temporal one from an activity, never workflow code).
     */
    public void recordContainerOutput(
            String workflowDefinitionId,
            String workflowDefinitionName,
            String runId,
            String requestedBy,
            WorkflowNodeDto node,
            String output) {
        expressions.record(
                new WorkflowExpressionResolver.NodeEvalInfo(
                        workflowDefinitionId, workflowDefinitionName, runId, requestedBy),
                node,
                null,
                "",
                NodeExecutionResult.ok(output, node.type() + " finished.", null));
    }

    /**
     * {@code node} with its {@code {{ }}} expressions resolved exactly as {@link #executeNode}
     * would, for an engine-level node that never reaches dispatch (the Wait node's {@code
     * until}/{@code duration}).
     */
    public WorkflowNodeDto resolveConfig(
            String workflowDefinitionId,
            String workflowDefinitionName,
            String runId,
            WorkflowNodeDto node,
            String input,
            String requestedBy) {
        return expressions.resolve(
                node,
                input,
                new WorkflowExpressionResolver.NodeEvalInfo(
                        workflowDefinitionId, workflowDefinitionName, runId, requestedBy),
                ExpressionScopeContext.current());
    }

    /**
     * The PINNED result standing in for {@code node}, or null when the node must execute. Null
     * unless the bound scope says the run is a draft/test run ({@link
     * ExpressionScope#honoursPins}); a production run binds no mode, so it cannot reach the lookup
     * below - the pin store is not even consulted.
     *
     * <p>The pinned text becomes the node's output exactly like a real output: it is recorded in
     * the run data store (so a downstream {@code $node["Label"]} reads it, and a pinned trigger
     * becomes {@code $trigger}) and it flows to the next node. A failure reading the pin propagates
     * instead of falling back to execution: a draft run must not quietly run the side effect the
     * author pinned in order to avoid.
     */
    private NodeExecutionResult pinnedResult(
            String workflowDefinitionId,
            String workflowDefinitionName,
            String runId,
            WorkflowNodeDto node,
            String input,
            String requestedBy) {
        ExpressionScope scope = ExpressionScopeContext.current();
        if (pinStore == null || scope == null || !scope.honoursPins() || node.id() == null) {
            return null;
        }
        String type = node.type() == null ? "" : node.type().toLowerCase(java.util.Locale.ROOT);
        if (PIN_NOT_HONOURED_TYPES.contains(type)) {
            return null;
        }
        UUID workflowId;
        try {
            workflowId = UUID.fromString(workflowDefinitionId);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
        WorkflowNodePin pin = pinStore.find(workflowId, node.id()).orElse(null);
        if (pin == null) {
            return null;
        }
        String data = pin.getDataText();
        NodeExecutionResult result =
                NodeExecutionResult.pinned(
                        data,
                        "Used pinned data instead of running this node ("
                                + data.length()
                                + " chars).",
                        List.of(
                                "Pinned data used (source: "
                                        + pin.getSource()
                                        + ", pinned "
                                        + (pin.getPinnedAt() == null
                                                ? "earlier"
                                                : pin.getPinnedAt().toString())
                                        + "); the node did not run."));
        expressions.record(
                new WorkflowExpressionResolver.NodeEvalInfo(
                        workflowDefinitionId, workflowDefinitionName, runId, requestedBy),
                node,
                scope,
                data,
                result);
        return result;
    }

    private NodeExecutionResult dispatchNode(
            WorkflowNodeDto node,
            String workflowDefinitionId,
            String workflowDefinitionName,
            String runId,
            String input,
            String requestedBy,
            WorkflowIntelligenceSettings intelligenceSettings,
            List<ChatInlineAttachment> attachments,
            WorkflowExpressionResolver.NodeEvalInfo evalInfo,
            ExpressionScope scope,
            WorkflowNodeDto unresolvedNode) {
        String type = node.type() == null ? "" : node.type().toLowerCase(java.util.Locale.ROOT);
        NodeHandler handler = handlers.get(type);
        if (handler == null) {
            return NodeExecutionResult.ok(
                    input,
                    "Node type '"
                            + node.type()
                            + "' is not yet executable - passed input through unchanged.",
                    null);
        }
        return handler.handle(
                new NodeInvocation(
                        node,
                        unresolvedNode,
                        input,
                        workflowDefinitionId,
                        workflowDefinitionName,
                        runId,
                        requestedBy,
                        intelligenceSettings,
                        attachments,
                        expressions,
                        evalInfo,
                        scope,
                        whatsAppTemplatesEnabled));
    }

    /** The registered handler for a lower-case node type, or null. Test seam. */
    NodeHandler handlerFor(String type) {
        return handlers.get(type);
    }

    public record NodeExecutionResult(
            String output,
            String status,
            String summary,
            String branch,
            WorkflowRunStatus haltRun,
            List<String> logLines,
            String fileName,
            String fileData) {

        public static NodeExecutionResult ok(String output, String summary, String branch) {
            return new NodeExecutionResult(
                    output, "OK", summary, branch, null, List.of(), null, null);
        }

        public static NodeExecutionResult ok(
                String output, String summary, String branch, List<String> logLines) {
            return new NodeExecutionResult(
                    output, "OK", summary, branch, null, logLines, null, null);
        }

        public static NodeExecutionResult ok(
                String output,
                String summary,
                String branch,
                List<String> logLines,
                String fileName,
                String fileData) {
            return new NodeExecutionResult(
                    output, "OK", summary, branch, null, logLines, fileName, fileData);
        }

        public static NodeExecutionResult skipped(String output, String summary) {
            return new NodeExecutionResult(
                    output, "SKIPPED", summary, null, null, List.of(), null, null);
        }

        public static NodeExecutionResult skipped(
                String output, String summary, List<String> logLines) {
            return new NodeExecutionResult(
                    output, "SKIPPED", summary, null, null, logLines, null, null);
        }

        /**
         * A node served from its pin in a draft/test run; continues downstream like OK (no halt, no
         * branch).
         */
        public static NodeExecutionResult pinned(
                String output, String summary, List<String> logLines) {
            return new NodeExecutionResult(
                    output, "PINNED", summary, null, null, logLines, null, null);
        }

        public static NodeExecutionResult halted(WorkflowRunStatus status, String summary) {
            return new NodeExecutionResult(
                    null, status.name(), summary, null, status, List.of(), null, null);
        }
    }
}
