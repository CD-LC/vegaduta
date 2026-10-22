package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Graph/config helpers extracted from {@link WorkflowExecutionEngine} (extract-only split):
 * loop/fan-out config clamps, sub-flow body parsing, edge routing, per-node retry policy and
 * trace/JSON serialisation. Plain (non-bean) collaborator owned by the engine.
 */
final class WorkflowGraphSupport {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WorkflowGraphSupport.class);

    private final ObjectMapper objectMapper;

    WorkflowGraphSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // Fail-proof hardening (see #1 in the harness's workbench-audit pass):
    // the synchronous engine previously had NO retry at all - one transient
    // exception (an LLM API timeout, a flaky knowledge-search call) failed
    // the entire run immediately, even though most node types are cheap to
    // re-attempt. Exponential backoff with full jitter between attempts
    // (originally a linear 500ms*attempt with no jitter - a problem for the
    // Parallel node above, whose branches all fail a rate-limited provider at
    // the same instant and, with identical deterministic delays, all retried
    // at the same instant too, re-colliding on every attempt).
    //
    // 2026-08-16: attempt COUNT is now per-workflow-configurable
    // (WorkflowDefinition#getMaxRetryAttempts, 0-5 retries after the first
    // try - see that field's javadoc) rather than the fixed constant this
    // comment used to describe; the backoff/jitter shape and the
    // output-node exclusion below are still fixed platform policy, not
    // user-configurable. A TenantLimitExceededException is also never
    // retried regardless of the configured count - a quota rejection is
    // deterministic within this backoff window (the cap doesn't clear in
    // seconds), so retrying it only burns more attempts, and their own
    // reservation-then-rollback token cost, for a guaranteed identical
    // failure - same fix as WorkflowRunWorkflowImpl's Temporal-path
    // ActivityOptions.
    //
    // Output nodes are deliberately EXCLUDED: WorkflowNodeExecutor#executeOutputNode
    // already catches its own expected failure mode (ChannelException) and
    // returns a soft SKIPPED result rather than throwing, so an exception
    // escaping from an Output node is something else going wrong - and
    // blindly retrying a node that emails/WhatsApps/Telegrams/uploads a file
    // risks sending the same message twice if the destination actually
    // received it before the failure surfaced (see TemporalConfig's
    // identical, already-documented at-least-once caveat for the Temporal
    // path). Every other node type here either has no external side effect
    // (trigger/knowledge/condition/code/supervisor-routing) or already
    // catches its own side-effecting failure internally and returns
    // SKIPPED/a fallback instead of throwing (tool/device_control/workflow-
    // trigger) - so in practice this retry only ever fires for the two paths
    // that can still throw past their own handler: the Agent node's
    // AgentChatService#chat call, and the Knowledge node's search call.

    /**
     * Upper bound of the sleep after failed attempt {@code attempt}: base 500ms doubled per attempt
     * (500, 1000, 2000, ...), capped at 10s. Package-private static so the progression is directly
     * assertable in a test without real sleeping.
     */
    static long retryBackoffCapMillis(int attempt) {
        return BackoffCalculator.capMillis(attempt);
    }

    /**
     * Full jitter: uniform in {@code [0, retryBackoffCapMillis(attempt)]} rather than "cap
     * plus/minus a little", because the failure mode being defended against is synchronized retries
     * - the Parallel node's branches (and independent concurrent runs) that all failed the same
     * provider at the same moment should spread out across the whole window, not cluster near the
     * cap.
     */
    static long jitteredRetryDelayMillis(int attempt) {
        return BackoffCalculator.jitteredDelayMillis(attempt);
    }

    /**
     * Per-node retry policy (Tier-3): a node's {@code retryAttempts} config overrides the
     * workflow-level {@code maxRetryAttempts} for that node only - same 0-5 clamp, same "retries
     * after the first try" semantics. A missing/malformed value falls back to the workflow default
     * rather than failing the run over config lint.
     */
    static int nodeMaxAttempts(WorkflowNodeDto node, int workflowDefaultRetries) {
        return RetryPolicy.nodeMaxRetries(node, workflowDefaultRetries);
    }

    /**
     * Per-node error branch (Tier-3): the target of this node's dedicated failure route, or null
     * when it has none (failure then keeps its old behavior - the run fails). Two authorings
     * resolve here, in order: an outgoing edge wired off the node's {@code "error"} source handle
     * (the canvas's visual affordance), then the {@code onErrorNodeId} config escape hatch for
     * API-authored graphs. This is deliberately an EDGE, not a per-node retry flag: "what happens
     * when this node is truly broken" is graph topology (route to a fallback agent, an alert
     * output, a cleanup step), not a number.
     */
    static String errorEdgeTarget(WorkflowNodeDto node, List<WorkflowEdgeDto> nodeOutgoing) {
        for (WorkflowEdgeDto edge : nodeOutgoing) {
            if ("error".equalsIgnoreCase(edge.sourceHandle() == null ? "" : edge.sourceHandle())) {
                return edge.target();
            }
        }
        Object fallback = node.config().get("onErrorNodeId");
        return fallback != null && !fallback.toString().isBlank() ? fallback.toString() : null;
    }

    /**
     * The value handed down an error edge: a JSON envelope naming the failed node, the failure, and
     * the input the node consumed - so the error-branch node (typically an Agent, Output, or Code
     * transform) can act on "what broke and with what" rather than a bare exception string.
     */
    String errorOutputJson(WorkflowNodeDto node, String errorMessage, String input) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of(
                            "error",
                                    errorMessage == null || errorMessage.isBlank()
                                            ? "Node failed."
                                            : errorMessage,
                            "failedNodeId", node.id(),
                            "failedNodeLabel", node.label() == null ? node.id() : node.label(),
                            "input", input == null ? "" : input));
        } catch (Exception e) {
            return "{\"error\":\"Node failed.\"}";
        }
    }

    String nextNodeId(WorkflowNodeDto node, List<WorkflowEdgeDto> edgesFromNode, String branch) {
        return WorkflowTraversalRules.nextNodeId(edgesFromNode, branch);
    }

    int loopMaxIterations(WorkflowNodeDto node) {
        return WorkflowTraversalRules.loopMaxIterations(node);
    }

    /**
     * Fan-out item ceiling - same clamp-don't-reject shape as {@link #loopMaxIterations} (a
     * non-numeric or out-of-range config value degrades to the default/ceiling rather than failing
     * the run; the graph JSON is author-editable data, not trusted input).
     */
    int fanOutMaxItems(WorkflowNodeDto node) {
        return WorkflowTraversalRules.fanOutMaxItems(node);
    }

    /**
     * Fan-out concurrency knob - see {@link #DEFAULT_FAN_OUT_CONCURRENCY}'s javadoc; same clamp
     * shape as above.
     */
    int fanOutConcurrency(WorkflowNodeDto node) {
        return WorkflowTraversalRules.fanOutConcurrency(node);
    }

    /**
     * "Repeat the next node per item" needs a real list to iterate. {@code itemsField} blank means
     * the loop node's own input must already be a JSON array; set means the input is parsed as a
     * JSON object and that field is read as the array instead (e.g. a prior node's output was
     * {@code {"rows": [...]}}). Anything that doesn't resolve to a JSON array iterates zero times -
     * skips straight past the body node - rather than failing the run; a misconfigured or empty
     * list is visible in the loop node's own trace summary, not a run-ending error.
     */
    List<String> resolveLoopItems(WorkflowNodeDto node, String currentOutput) {
        WorkflowTraversalRules.LoopItems resolved =
                WorkflowTraversalRules.resolveItems(objectMapper, node, currentOutput);
        // SE-52: a Loop/Fan-out given something other than a list used to iterate zero times in
        // silence.
        switch (resolved.problem()) {
            case NOT_A_LIST ->
                    log.warn(
                            "Loop/fan-out node '{}' got non-list input{} - iterating 0 items.",
                            node.label(),
                            resolved.itemsField() == null || resolved.itemsField().isEmpty()
                                    ? ""
                                    : " at field '" + resolved.itemsField() + "'");
            case INVALID_JSON ->
                    log.warn(
                            "Loop/fan-out node '{}' input is not valid JSON - iterating 0 items.",
                            node.label());
            case NONE -> {}
        }
        return resolved.items();
    }

    String writeJsonArray(List<String> items) {
        return WorkflowTraversalRules.writeJsonArray(objectMapper, items);
    }

    /**
     * Parses a sub_flow node's embedded body graph ({@code config.body} -> {@code {nodes: [...],
     * edges: [...]}}). The body uses the same {@link WorkflowNodeDto}/{@link WorkflowEdgeDto}
     * shapes as the outer graph so every node type the executor already dispatches works inside it
     * one level down. Structural lint (empty body, dangling edge endpoints) is a validation error,
     * not a silent no-op - a sub-flow that does nothing is always an authoring bug worth failing
     * on.
     */
    WorkflowGraphDto readSubFlowBody(WorkflowNodeDto node) {
        Object raw = node.config().get("body");
        if (!(raw instanceof Map)) {
            throw new WorkflowValidationException(
                    List.of(
                            "Sub-flow node '"
                                    + node.label()
                                    + "' has no 'body' graph in its config."));
        }
        WorkflowGraphDto body;
        try {
            body = objectMapper.convertValue(raw, WorkflowGraphDto.class);
        } catch (IllegalArgumentException e) {
            throw new WorkflowValidationException(
                    List.of(
                            "Sub-flow node '"
                                    + node.label()
                                    + "' has a malformed 'body' graph: "
                                    + e.getMessage()));
        }
        if (body == null || body.nodes() == null || body.nodes().isEmpty()) {
            throw new WorkflowValidationException(
                    List.of("Sub-flow node '" + node.label() + "' has an empty 'body' graph."));
        }
        Set<String> bodyIds = new HashSet<>();
        for (WorkflowNodeDto bodyNode : body.nodes()) {
            bodyIds.add(bodyNode.id());
        }
        List<WorkflowEdgeDto> bodyEdges = body.edges() == null ? List.of() : body.edges();
        for (WorkflowEdgeDto edge : bodyEdges) {
            if (!bodyIds.contains(edge.source()) || !bodyIds.contains(edge.target())) {
                throw new WorkflowValidationException(
                        List.of(
                                "Sub-flow node '"
                                        + node.label()
                                        + "' has a body edge ('"
                                        + edge.id()
                                        + "') that references a node outside the body graph."));
            }
        }
        return body;
    }

    /**
     * The sub-flow body's entry node: an explicit {@code entryNodeId} config wins (lets an author
     * pick among several roots), otherwise the single node with no incoming body edge. Zero or
     * several candidates without an explicit entry is ambiguous and fails validation rather than
     * silently picking one - "which node starts the body" is the author's decision, not a coin
     * flip.
     */
    WorkflowNodeDto resolveSubFlowEntry(
            WorkflowNodeDto node,
            WorkflowGraphDto bodyGraph,
            Map<String, WorkflowNodeDto> bodyById,
            Map<String, List<WorkflowEdgeDto>> bodyOut) {
        Object explicit = node.config().get("entryNodeId");
        if (explicit != null && !explicit.toString().isBlank()) {
            WorkflowNodeDto entry = bodyById.get(explicit.toString());
            if (entry == null) {
                throw new WorkflowValidationException(
                        List.of(
                                "Sub-flow node '"
                                        + node.label()
                                        + "' entryNodeId '"
                                        + explicit
                                        + "' is not a node in its body graph."));
            }
            return entry;
        }
        Set<String> hasIncoming = new HashSet<>();
        List<WorkflowEdgeDto> bodyEdges = bodyGraph.edges() == null ? List.of() : bodyGraph.edges();
        for (WorkflowEdgeDto edge : bodyEdges) {
            hasIncoming.add(edge.target());
        }
        List<WorkflowNodeDto> roots = new ArrayList<>();
        for (WorkflowNodeDto bodyNode : bodyGraph.nodes()) {
            if (!hasIncoming.contains(bodyNode.id())) {
                roots.add(bodyNode);
            }
        }
        if (roots.size() != 1) {
            throw new WorkflowValidationException(
                    List.of(
                            "Sub-flow node '"
                                    + node.label()
                                    + "' body has "
                                    + roots.size()
                                    + " possible entry nodes - set 'entryNodeId' to pick one."));
        }
        return roots.get(0);
    }

    String writeTrace(List<NodeTraceEntry> trace) {
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (Exception e) {
            return "[]";
        }
    }
}
