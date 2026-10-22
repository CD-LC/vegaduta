package com.agenticai.core.workflow;

import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeTestRequest;
import com.agenticai.core.workflow.expression.NodeOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Test this step" for the workflow canvas: runs ONE node of the saved graph through {@link
 * WorkflowNodeExecutor#executeNode} - the same code path a real run uses - and returns a single
 * {@link NodeTraceEntry} with the same per-step run data (sanitized input/output, duration,
 * tokens/cost) a run's trace carries. Nothing is persisted as a workflow run: no {@code
 * workflow_run} row, no run history entry, no trace.
 *
 * <p><b>Refused with 409</b> (none of these has a dry-run mode today, so the only honest options
 * are "really do it" or "don't"):
 *
 * <ul>
 *   <li>side-effecting nodes - {@code bulk_email}, {@code crm_lead}, {@code device_control}, {@code
 *       tool}, {@code output} (sends email/Telegram/Teams/WhatsApp, writes Drive), {@code workflow}
 *       (starts another workflow's run), {@code http} with any method but GET, and {@code media}
 *       with operation {@code render} (starts a render job);
 *   <li>nodes that need a real run to exist - {@code approval}, any node with {@code
 *       requiresApproval}, {@code human_input}, {@code voice_menu} (all create pending requests
 *       keyed to a run and halt it), and an {@code agent} node with structured {@code inputMapping}
 *       (it writes a {@code subagent_run} row whose {@code workflow_run_id} is a foreign key);
 *   <li>multi-node constructs - {@code loop}, {@code parallel}, {@code fan_out}, {@code sub_flow}.
 * </ul>
 *
 * {@code code} (the Transform node) is deliberately NOT refused: every one of its operations is a
 * pure string transform. {@code agent}/{@code supervisor} run for real, exactly like the agent
 * playground: an agent whose own tools have side effects is governed by that agent's tool approval
 * policy, not by this endpoint.
 */
@Service
public class WorkflowNodeTestService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowNodeTestService.class);

    static final Set<String> SIDE_EFFECT_TYPES =
            Set.of("bulk_email", "crm_lead", "device_control", "tool", "output", "workflow");
    static final Set<String> NEEDS_REAL_RUN_TYPES = Set.of("approval", "human_input", "voice_menu");
    static final Set<String> MULTI_NODE_TYPES = Set.of("loop", "parallel", "fan_out", "sub_flow");

    private final WorkflowService workflowService;
    private final WorkflowNodeExecutor nodeExecutor;
    private final NodeUsageResolver usageResolver;
    private final ObjectMapper objectMapper;
    // Persisted pins (V374): the default source of upstream data. Setter-injected so the hand-built
    // service in tests
    // keeps compiling; null = only the request's own pinnedInputs count.
    private WorkflowPinStore pinStore;

    public WorkflowNodeTestService(
            WorkflowService workflowService,
            WorkflowNodeExecutor nodeExecutor,
            NodeUsageResolver usageResolver,
            ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.nodeExecutor = nodeExecutor;
        this.usageResolver = usageResolver;
        this.objectMapper = objectMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPinStore(WorkflowPinStore pinStore) {
        this.pinStore = pinStore;
    }

    public NodeTraceEntry test(
            UUID workflowId, String nodeId, WorkflowNodeTestRequest request, String requestedBy) {
        WorkflowDefinition definition =
                workflowService.requireAccess(workflowId, PermissionLevel.RUNNER);
        // The live (editable) graph, not the PRODUCTION snapshot a run uses:
        // the author is testing the node they are looking at on the canvas.
        WorkflowGraphDto graph = workflowService.graph(workflowId);
        WorkflowNodeDto node =
                graph.nodes().stream()
                        .filter(n -> nodeId.equals(n.id()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Node '"
                                                        + nodeId
                                                        + "' is not in this workflow's saved graph - save the canvas first."));

        String refusal = refusalReason(node);
        if (refusal != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, refusal);
        }

        List<String> preamble = new ArrayList<>();
        Map<String, Object> pinned =
                effectivePins(definition.getId(), graph, request, canEdit(workflowId));
        String input = resolveInput(node, graph, request, pinned, preamble);
        // A throwaway session id per test: keeps the agent's chat memory and
        // tool-call log for this test apart from every real run, and is the
        // key NodeUsageResolver attributes llm_call_log rows by.
        String testRunId = UUID.randomUUID().toString();

        Instant startedAt = Instant.now();
        NodeTraceEntry entry;
        String output = null;
        try {
            // A throwaway run id with no workflow_run row: tell the executor not to record run data
            // for it.
            ExpressionScopeContext.bind(ExpressionScope.test(suppliedOutputs(graph, pinned)));
            WorkflowNodeExecutor.NodeExecutionResult result;
            try {
                result =
                        nodeExecutor.executeNode(
                                definition.getId().toString(),
                                definition.getName(),
                                testRunId,
                                node,
                                input,
                                requestedBy,
                                WorkflowIntelligenceSettings.from(definition));
            } finally {
                ExpressionScopeContext.clear();
            }
            output = result.output();
            entry =
                    new NodeTraceEntry(
                            node.id(),
                            node.type(),
                            node.label(),
                            result.status(),
                            result.summary(),
                            startedAt,
                            Instant.now(),
                            concat(preamble, result.logLines()));
        } catch (TenantLimitExceededException e) {
            throw e;
        } catch (RuntimeException e) {
            log.info(
                    "Node test of '{}' ({}) in workflow {} failed: {}",
                    node.label(),
                    node.type(),
                    workflowId,
                    e.getMessage());
            entry =
                    new NodeTraceEntry(
                            node.id(),
                            node.type(),
                            node.label(),
                            "ERROR",
                            e.getMessage(),
                            startedAt,
                            Instant.now(),
                            concat(preamble, List.of("Node failed: " + e.getMessage())));
        }
        entry =
                NodeTracePayloads.attach(
                        entry, input, output, NodeTracePayloads.MAX_FIELD_CHARS, 0L);
        return usageResolver.attach(entry, node, testRunId);
    }

    /** Null when the node may be tested; otherwise the 409 message. */
    static String refusalReason(WorkflowNodeDto node) {
        String type = node.type() == null ? "" : node.type().toLowerCase(Locale.ROOT);
        Map<String, Object> config = node.config();
        if (MULTI_NODE_TYPES.contains(type)) {
            return "'"
                    + type
                    + "' runs other nodes, so it cannot be tested on its own - run the workflow instead.";
        }
        if ("wait".equals(type)) {
            // Parks the run and registers a durable wake-up row, so it only exists inside a real
            // run.
            return "A Wait node pauses a real run until its time or resume call arrives, so it cannot be tested on its own - run the workflow instead.";
        }
        if (NEEDS_REAL_RUN_TYPES.contains(type)
                || Boolean.TRUE.equals(config.get("requiresApproval"))) {
            return "This node waits for a person (approval or input), which needs a real run - run the workflow instead.";
        }
        if (SIDE_EFFECT_TYPES.contains(type)) {
            return "'"
                    + type
                    + "' has real side effects (it sends, writes or triggers something) and has no dry-run mode, "
                    + "so it is not run in a test - run the workflow instead.";
        }
        if ("data_table".equals(type)
                && com.agenticai.core.tables.DataTableNodeOps.isWrite(config.get("operation"))) {
            return "This data table step writes to a table and has no dry-run mode, so it is not run in a test - "
                    + "run the workflow instead (reading rows or counting can be tested).";
        }
        if ("http".equals(type)) {
            Object method = config.get("method");
            String m =
                    method == null || method.toString().isBlank()
                            ? "GET"
                            : method.toString().strip().toUpperCase(Locale.ROOT);
            if (!"GET".equals(m)) {
                return "Only GET requests can be tested; this HTTP node uses "
                        + m
                        + ", which may change data on the other side - run the workflow instead.";
            }
        }
        if ("media".equals(type)
                && "render".equalsIgnoreCase(String.valueOf(config.get("operation")).strip())) {
            return "A media render starts a render job, so it is not run in a test - run the workflow instead.";
        }
        if ("agent".equals(type)
                && config.get("inputMapping") instanceof Map<?, ?> mapping
                && !mapping.isEmpty()) {
            return "Agent nodes with structured input record their result against a real run, so they cannot be "
                    + "tested on their own yet - run the workflow instead.";
        }
        return null;
    }

    /**
     * The upstream outputs this test may read: the workflow's persisted pins (spec 5.4 - so "Test
     * this node" needs no re-pasting), overridden node by node by the request's own {@code
     * pinnedInputs}. A node with neither is simply absent, so a {@code $node} reference to it still
     * fails loudly rather than reading empty. The node under test is not special-cased: its own pin
     * never short-circuits it (the scope is a step test), and nothing reads its output.
     */
    private Map<String, Object> effectivePins(
            UUID workflowId,
            WorkflowGraphDto graph,
            WorkflowNodeTestRequest request,
            boolean mayReadPins) {
        Map<String, Object> merged = new HashMap<>();
        // Persisted pins are EDITOR-level data (WorkflowPinService): a RUNNER may run this test,
        // but the pinned
        // text would come straight back in the trace, so below EDITOR only the request's own
        // pinnedInputs apply.
        if (pinStore != null && mayReadPins) {
            Set<String> ids = new java.util.HashSet<>();
            for (WorkflowNodeDto n : graph.nodes()) {
                ids.add(n.id());
            }
            for (WorkflowNodePin pin : pinStore.list(workflowId)) {
                if (ids.contains(pin.getNodeId())) {
                    merged.put(pin.getNodeId(), pin.getDataText());
                }
            }
        }
        if (request != null && request.pinnedInputs() != null) {
            request.pinnedInputs()
                    .forEach(
                            (k, v) -> {
                                if (v != null) {
                                    merged.put(k, v);
                                }
                            });
        }
        return merged;
    }

    private boolean canEdit(UUID workflowId) {
        try {
            workflowService.requireAccess(workflowId, PermissionLevel.EDITOR);
            return true;
        } catch (com.agenticai.core.sharing.ResourceAccessDeniedException e) {
            return false;
        }
    }

    private String resolveInput(
            WorkflowNodeDto node,
            WorkflowGraphDto graph,
            WorkflowNodeTestRequest request,
            Map<String, Object> pinned,
            List<String> preamble) {
        if (request != null && request.input() != null) {
            preamble.add("Test input: supplied directly (" + request.input().length() + " chars).");
            return request.input();
        }
        for (WorkflowEdgeDto edge : graph.edges()) {
            if (node.id().equals(edge.target())
                    && pinned.containsKey(edge.source())
                    && pinned.get(edge.source()) != null) {
                Object value = pinned.get(edge.source());
                preamble.add(
                        "Test input: pinned output of upstream node '"
                                + edge.source()
                                + "' ("
                                + (fromRequest(request, edge.source()) ? "supplied" : "saved pin")
                                + ").");
                return value instanceof String s ? s : writeJson(value);
            }
        }
        preamble.add(
                "Test input: none supplied and no pinned upstream output - ran with empty input.");
        return "";
    }

    /**
     * Every pinned upstream output the caller sent, addressable by {@code $node["Label"]} / {@code
     * $node.id()}; the trigger's also as {@code $trigger}. A step test has no run store, so this is
     * its only source.
     */
    private Map<String, NodeOutput> suppliedOutputs(
            WorkflowGraphDto graph, Map<String, Object> pinned) {
        Map<String, NodeOutput> out = new HashMap<>();
        for (WorkflowNodeDto n : graph.nodes()) {
            Object value = pinned.get(n.id());
            if (value == null) {
                continue;
            }
            String text = value instanceof String s ? s : writeJson(value);
            NodeOutput o = new NodeOutput(n.id(), n.label(), text, "OK", null);
            out.put("id:" + n.id(), o);
            if (n.label() != null && !n.label().isBlank()) {
                out.put("label:" + n.label(), o);
            }
            if ("trigger".equalsIgnoreCase(n.type())) {
                out.put("id:" + RunDataStore.TRIGGER_NODE_ID, o);
            }
        }
        return out;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static boolean fromRequest(WorkflowNodeTestRequest request, String nodeId) {
        return request != null
                && request.pinnedInputs() != null
                && request.pinnedInputs().get(nodeId) != null;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        if (b != null) {
            out.addAll(b);
        }
        return out;
    }
}
