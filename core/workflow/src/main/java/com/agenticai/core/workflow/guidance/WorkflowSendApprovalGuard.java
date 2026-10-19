package com.agenticai.core.workflow.guidance;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Nothing is sent to a person without a human check" as a deterministic graph rule, not a prompt
 * instruction. Stateless and Spring-free so the AI generator, the lint panel and the draft dry-run
 * all share one definition of "this node sends something out".
 *
 * <p>A node is an <b>outbound send</b> when it is:
 * <ul>
 *   <li>an {@code output} node whose channel is email / telegram / whatsapp / teams,</li>
 *   <li>a {@code bulk_email} node,</li>
 *   <li>an {@code http} node whose method is not GET / HEAD / OPTIONS,</li>
 *   <li>a {@code tool} node whose tool name starts with {@code send_}, or</li>
 *   <li>a {@code sub_flow} node whose embedded body contains any of the above (the body lives in
 *       config, so the gate can only stand in front of the whole sub-flow).</li>
 * </ul>
 *
 * <p>A send is <b>gated</b> when every path from a Trigger to it passes a human gate first: an
 * {@code approval} node, a {@code human_input} node, or any node (the send itself included) with
 * {@code requiresApproval: true}. Sends no Trigger can reach never run and are ignored.
 *
 * <p>{@link #enforce} repairs an ungated send by routing its ungated incoming edges through ONE new
 * {@code approval} node. When the send is the body of a {@code loop} / {@code fan_out} or a branch
 * of a {@code parallel} node, the approval is placed in front of that container instead: those
 * nodes run exactly the node their edge points at, so an approval wedged between container and
 * body would become the body and the send would escape the loop.
 */
public final class WorkflowSendApprovalGuard {

    public static final String AUTO_APPROVAL_ID_PREFIX = "auto-approval-";
    public static final String AUTO_APPROVAL_LABEL = "Approve before sending";

    /** Output channels that deliver a message to a person. */
    static final Set<String> MESSAGE_CHANNELS = Set.of("email", "telegram", "whatsapp", "teams");
    /** Output channels that leave the platform at all (messages plus the Drive upload) - the dry-run set. */
    static final Set<String> EXTERNAL_CHANNELS = Set.of("email", "telegram", "whatsapp", "teams", "google_drive");
    /** Output channels a dry run stands in for: the external ones plus "event", which can start other workflows. */
    static final Set<String> DRY_RUN_CHANNELS = Set.of("email", "telegram", "whatsapp", "teams", "google_drive", "event");
    static final Set<String> READ_ONLY_HTTP_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    /** Nodes that execute the node their outgoing edge points at, rather than handing over to it. */
    static final Set<String> CONTAINER_TYPES = Set.of("loop", "fan_out", "parallel");

    private WorkflowSendApprovalGuard() {
    }

    /** One approval node the guard added. */
    public record Insertion(String approvalNodeId, String beforeNodeId, String beforeLabel, String sendNodeId) {
    }

    /** {@code graph} is the input instance itself when nothing had to change. */
    public record Result(WorkflowGraphDto graph, List<String> notes, List<Insertion> insertions) {

        public boolean changed() {
            return !insertions.isEmpty();
        }
    }

    /** An ungated send and the node(s) an approval has to be placed in front of to gate it. */
    public record Finding(WorkflowNodeDto send, List<String> insertBeforeNodeIds) {
    }

    // --- classification -------------------------------------------------------------------

    static String typeOf(WorkflowNodeDto node) {
        return node == null || node.type() == null ? "" : node.type().strip().toLowerCase(Locale.ROOT);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().strip();
    }

    /** True when running {@code node} delivers a message or writes to an external system. */
    public static boolean isOutboundSend(WorkflowNodeDto node) {
        return isOutboundSend(node, true);
    }

    private static boolean isOutboundSend(WorkflowNodeDto node, boolean lookInsideSubFlow) {
        Map<String, Object> config = node.config();
        return switch (typeOf(node)) {
            case "output" -> MESSAGE_CHANNELS.contains(text(config.get("channel")).toLowerCase(Locale.ROOT));
            case "bulk_email" -> true;
            case "http" -> isMutatingHttp(node);
            case "tool" -> text(config.get("toolName")).toLowerCase(Locale.ROOT).startsWith("send_");
            case "sub_flow" -> lookInsideSubFlow && subFlowBodySends(config.get("body"));
            default -> false;
        };
    }

    /** An {@code http} node whose method changes something (anything but GET / HEAD / OPTIONS; blank = GET). */
    public static boolean isMutatingHttp(WorkflowNodeDto node) {
        if (!"http".equals(typeOf(node))) {
            return false;
        }
        String method = text(node.config().get("method")).toUpperCase(Locale.ROOT);
        return !method.isEmpty() && !READ_ONLY_HTTP_METHODS.contains(method);
    }

    /**
     * The set a draft dry-run stands in for, i.e. every node whose side effect is known from its
     * configuration alone: everything {@link #isOutboundSend} classifies as a send (output on a
     * message channel, {@code bulk_email}, mutating {@code http}, a {@code tool} node named
     * {@code send_*}), plus the other side-effecting node types {@code WorkflowNodeTestService}
     * refuses to step-test - the Drive upload and the {@code event} output channel (an event can
     * start other workflows), {@code crm_lead}, {@code device_control}, and the {@code workflow}
     * node (the child run is a production run and would send for real). {@code sub_flow}
     * containers are not listed because their body nodes are judged one by one when the engine
     * walks them. NOT in the set, because what they do is decided at run time: {@code agent} /
     * {@code supervisor} nodes and {@code tool} nodes not named {@code send_*} - the dry run marks
     * those in the trace as having run for real.
     */
    public static boolean isSimulatableSend(WorkflowNodeDto node) {
        return switch (typeOf(node)) {
            case "output" -> DRY_RUN_CHANNELS.contains(text(node.config().get("channel")).toLowerCase(Locale.ROOT));
            case "bulk_email", "crm_lead", "device_control", "workflow" -> true;
            // data_table: only its WRITE operations change anything (a read runs for real in a dry run); an unknown operation counts as a write.
            case "data_table" -> com.agenticai.core.tables.DataTableNodeOps.isWrite(node.config().get("operation"));
            case "http" -> isMutatingHttp(node);
            case "tool" -> isOutboundSend(node, false);
            default -> false;
        };
    }

    /**
     * True when {@code node} is an output on a conversational channel with no fixed destination,
     * i.e. it answers whoever triggered the run (the reply-to-sender fallback of the telegram /
     * teams / whatsapp outputs). Such a send is usually meant to go out unattended.
     */
    public static boolean repliesToTriggerSender(WorkflowNodeDto node) {
        if (!"output".equals(typeOf(node))) {
            return false;
        }
        Map<String, Object> config = node.config();
        return switch (text(config.get("channel")).toLowerCase(Locale.ROOT)) {
            case "whatsapp" -> text(config.get("whatsappTo")).isEmpty();
            case "telegram" -> text(config.get("telegramChatId")).isEmpty();
            case "teams" -> text(config.get("teamsConversationId")).isEmpty();
            default -> false;
        };
    }

    /** True when {@code node} pauses the run for a person before anything behind it executes. */
    public static boolean isHumanGate(WorkflowNodeDto node) {
        String type = typeOf(node);
        return "approval".equals(type) || "human_input".equals(type)
            || Boolean.TRUE.equals(node.config().get("requiresApproval"));
    }

    private static boolean subFlowBodySends(Object body) {
        if (!(body instanceof Map<?, ?> bodyMap) || !(bodyMap.get("nodes") instanceof List<?> nodes)) {
            return false;
        }
        for (Object raw : nodes) {
            if (!(raw instanceof Map<?, ?> nodeMap)) {
                continue;
            }
            Object type = nodeMap.get("type");
            Map<String, Object> data = new HashMap<>();
            if (nodeMap.get("data") instanceof Map<?, ?> dataMap) {
                dataMap.forEach((k, v) -> data.put(String.valueOf(k), v));
            }
            WorkflowNodeDto bodyNode = new WorkflowNodeDto(
                String.valueOf(nodeMap.get("id")), type == null ? null : type.toString(), Map.of(), data);
            // One level only: nested sub-flows inside a body are not executable.
            if (isOutboundSend(bodyNode, false)) {
                return true;
            }
        }
        return false;
    }

    // --- detection ------------------------------------------------------------------------

    /** Every send a Trigger can reach without passing a human gate, nearest to a Trigger first. */
    public static List<WorkflowNodeDto> ungatedSends(WorkflowGraphDto graph) {
        return findings(graph).stream().map(Finding::send).toList();
    }

    /** Same as {@link #ungatedSends}, plus where the approval would go (differs from the send for loop bodies). */
    public static List<Finding> findings(WorkflowGraphDto graph) {
        if (graph == null || graph.nodes().isEmpty()) {
            return List.of();
        }
        Analysis analysis = new Analysis(graph);
        List<Finding> out = new ArrayList<>();
        for (String id : analysis.open) { // breadth-first from the Triggers, so upstream sends come first
            WorkflowNodeDto node = analysis.byId.get(id);
            if (isOutboundSend(node)) {
                out.add(new Finding(node, new ArrayList<>(analysis.gateTargets(id))));
            }
        }
        return out;
    }

    /** Reachability "from a Trigger without having passed a gate", computed once per graph. */
    private static final class Analysis {
        final Map<String, WorkflowNodeDto> byId = new LinkedHashMap<>();
        final Map<String, List<WorkflowEdgeDto>> incoming = new HashMap<>();
        /** Non-gate nodes a Trigger reaches along a gate-free path; edges leaving these are "ungated". */
        final Set<String> open = new LinkedHashSet<>();

        Analysis(WorkflowGraphDto graph) {
            for (WorkflowNodeDto node : graph.nodes()) {
                if (node.id() != null) {
                    byId.putIfAbsent(node.id(), node);
                }
            }
            Map<String, List<String>> outgoing = new HashMap<>();
            for (WorkflowEdgeDto edge : graph.edges()) {
                if (edge == null || !byId.containsKey(edge.source()) || !byId.containsKey(edge.target())) {
                    continue; // dangling edges are the validator's problem
                }
                outgoing.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge.target());
                incoming.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge);
            }
            Deque<String> queue = new ArrayDeque<>();
            for (WorkflowNodeDto node : byId.values()) {
                if ("trigger".equals(typeOf(node))) {
                    queue.add(node.id());
                }
            }
            while (!queue.isEmpty()) {
                String id = queue.poll();
                if (isHumanGate(byId.get(id)) || !open.add(id)) {
                    continue; // a gate closes every path through it
                }
                outgoing.getOrDefault(id, List.of()).forEach(queue::add);
            }
        }

        List<WorkflowEdgeDto> ungatedIncoming(String nodeId) {
            List<WorkflowEdgeDto> out = new ArrayList<>();
            for (WorkflowEdgeDto edge : incoming.getOrDefault(nodeId, List.of())) {
                if (open.contains(edge.source())) {
                    out.add(edge);
                }
            }
            return out;
        }

        /**
         * The nodes an approval must precede so {@code sendId} is gated: the send itself, or - when an
         * ungated edge into it comes from a loop / fan_out / parallel - that container (recursively).
         */
        Set<String> gateTargets(String sendId) {
            Set<String> targets = new LinkedHashSet<>();
            Set<String> seen = new HashSet<>();
            Deque<String> work = new ArrayDeque<>();
            work.add(sendId);
            while (!work.isEmpty()) {
                String id = work.poll();
                if (!seen.add(id)) {
                    continue;
                }
                for (WorkflowEdgeDto edge : ungatedIncoming(id)) {
                    if (CONTAINER_TYPES.contains(typeOf(byId.get(edge.source())))) {
                        work.add(edge.source());
                    } else {
                        targets.add(id);
                    }
                }
            }
            return targets;
        }

        /** Ungated edges into {@code targetId} that do not come from a container (those are hoisted). */
        List<WorkflowEdgeDto> reroutableIncoming(String targetId) {
            List<WorkflowEdgeDto> out = new ArrayList<>();
            for (WorkflowEdgeDto edge : ungatedIncoming(targetId)) {
                if (!CONTAINER_TYPES.contains(typeOf(byId.get(edge.source())))) {
                    out.add(edge);
                }
            }
            return out;
        }
    }

    // --- repair ---------------------------------------------------------------------------

    /**
     * Returns a graph in which every reachable outbound send is gated, plus one plain-language note
     * per approval node added. Idempotent: a graph that is already safe (including one this method
     * produced) comes back as the same instance with no notes. Sends are repaired nearest-to-Trigger
     * first and the graph re-checked after each, so one approval covers every send behind it.
     */
    public static Result enforce(WorkflowGraphDto graph) {
        if (graph == null) {
            return new Result(null, List.of(), List.of());
        }
        WorkflowGraphDto current = graph;
        List<String> notes = new ArrayList<>();
        List<Insertion> insertions = new ArrayList<>();
        // Each pass gates at least one more send, so the node count bounds the loop; the cap only
        // guards against a defect here turning into an endless request.
        int maxPasses = graph.nodes().size() + 1;
        for (int pass = 0; pass < maxPasses; pass++) {
            List<Finding> findings = findings(current);
            if (findings.isEmpty()) {
                break;
            }
            WorkflowGraphDto next = gate(current, findings.get(0), notes, insertions);
            if (next == current) {
                break;
            }
            current = next;
        }
        return new Result(current, List.copyOf(notes), List.copyOf(insertions));
    }

    /** Gates one finding; returns {@code graph} itself when there was nothing to reroute. */
    private static WorkflowGraphDto gate(
        WorkflowGraphDto graph, Finding finding, List<String> notes, List<Insertion> insertions
    ) {
        Analysis analysis = new Analysis(graph);
        WorkflowNodeDto send = finding.send();

        Set<String> usedNodeIds = new HashSet<>(analysis.byId.keySet());
        Set<String> usedEdgeIds = new HashSet<>();
        Set<String> usedLabels = new HashSet<>();
        for (WorkflowEdgeDto edge : graph.edges()) {
            if (edge != null && edge.id() != null) {
                usedEdgeIds.add(edge.id());
            }
        }
        for (WorkflowNodeDto node : graph.nodes()) {
            usedLabels.add(node.label());
        }

        List<WorkflowNodeDto> nodes = new ArrayList<>(graph.nodes());
        List<WorkflowEdgeDto> edges = new ArrayList<>(graph.edges());
        boolean changed = false;

        for (String targetId : finding.insertBeforeNodeIds()) {
            WorkflowNodeDto target = analysis.byId.get(targetId);
            List<WorkflowEdgeDto> reroute = analysis.reroutableIncoming(targetId);
            if (target == null || reroute.isEmpty()) {
                continue;
            }
            String approvalId = unique(AUTO_APPROVAL_ID_PREFIX + targetId, usedNodeIds);
            String label = usedLabels.add(AUTO_APPROVAL_LABEL)
                ? AUTO_APPROVAL_LABEL
                : uniqueLabel(AUTO_APPROVAL_LABEL + ": " + send.label(), usedLabels);

            Map<String, Object> config = new LinkedHashMap<>();
            config.put("message", "Check this before \"" + send.label() + "\" sends it.");
            config.put("autoInserted", true);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("label", label);
            data.put("config", config);
            WorkflowNodeDto firstSource = analysis.byId.get(reroute.get(0).source());
            nodes.add(new WorkflowNodeDto(approvalId, "approval", midway(firstSource, target), data));

            Set<WorkflowEdgeDto> toReroute = new HashSet<>(reroute);
            for (int i = 0; i < edges.size(); i++) {
                WorkflowEdgeDto edge = edges.get(i);
                if (edge != null && toReroute.contains(edge)) {
                    // Same id and source handle: a condition's "true" port still leads this way.
                    edges.set(i, new WorkflowEdgeDto(edge.id(), edge.source(), approvalId, edge.sourceHandle(), null));
                }
            }
            edges.add(new WorkflowEdgeDto(unique("e-" + approvalId, usedEdgeIds), approvalId, targetId, null, null));

            changed = true;
            insertions.add(new Insertion(approvalId, targetId, target.label(), send.id()));
            notes.add(targetId.equals(send.id())
                ? "Added an approval step before \"" + send.label()
                    + "\" so nothing is sent until a person approves it."
                : "Added an approval step before \"" + target.label() + "\" because \"" + send.label()
                    + "\" sends inside it - nothing is sent until a person approves it.");
        }
        return changed ? new WorkflowGraphDto(nodes, edges) : graph;
    }

    private static String unique(String base, Set<String> used) {
        String candidate = base;
        int n = 2;
        while (!used.add(candidate)) {
            candidate = base + "-" + n++;
        }
        return candidate;
    }

    private static String uniqueLabel(String base, Set<String> used) {
        String candidate = base;
        int n = 2;
        while (!used.add(candidate)) {
            candidate = base + " " + n++;
        }
        return candidate;
    }

    private static Map<String, Object> midway(WorkflowNodeDto source, WorkflowNodeDto target) {
        double tx = coordinate(target, "x", 0);
        double ty = coordinate(target, "y", 160);
        double sx = coordinate(source, "x", tx - 260);
        double sy = coordinate(source, "y", ty);
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("x", Math.round((sx + tx) / 2.0));
        position.put("y", Math.round((sy + ty) / 2.0));
        return position;
    }

    private static double coordinate(WorkflowNodeDto node, String axis, double fallback) {
        if (node == null || node.position() == null) {
            return fallback;
        }
        Object raw = node.position().get(axis);
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return raw == null ? fallback : Double.parseDouble(raw.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
