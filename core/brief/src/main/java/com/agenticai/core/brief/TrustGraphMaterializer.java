package com.agenticai.core.brief;

import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Inserts / removes the APPROVAL (and HUMAN_INPUT) nodes a trust rung implies (contract section 1).
 * The Brief's own graph is stored WITHOUT these nodes; they exist only in the applied workflow and
 * every one is tagged {@code data.eaMaterialised=true} with deterministic ids, so {@link #strip}
 * can take them out again and {@link #materialise} is idempotent (it strips first). Pure functions
 * over {@link WorkflowGraphDto} - it works the same on a Brief graph (refs in node config) and on a
 * live workflow graph (real ids), because it only ever looks at node types and edges.
 *
 * <p>An approval node is placed on every incoming edge of a gated node: {@code S -> T} becomes
 * {@code S -> A -> T} (the source handle of the original edge is kept, so condition branches still
 * route). A gated node with no incoming edge (a graph entry) is left alone - there is nothing to
 * put in front of it.
 *
 * <p>The approval node only pauses a run if the tenant's approval policy requires approval for
 * {@code workflow.node.approval}; that is why {@link TrustLadderService} tightens that policy
 * whenever a rung materialises at least one.
 */
public final class TrustGraphMaterializer {

    public static final String MARK = "eaMaterialised";
    public static final String APPROVAL_TYPE = "approval";
    public static final String HUMAN_INPUT_TYPE = "human_input";
    /** Action type the workflow engine records for an approval node (OrchestratorService#authorizeSensitiveNode). */
    public static final String APPROVAL_ACTION_TYPE = "workflow.node.approval";

    static final String APPROVE_PREFIX = "ea-approve-";
    static final String HUMAN_PREFIX = "ea-human-";
    static final String EDGE_IN_PREFIX = "ea-e-in-";
    static final String EDGE_OUT_PREFIX = "ea-e-out-";

    /** Node types that act on the outside world; UNDER_WATCH puts an approval in front of every one. */
    static final Set<String> SIDE_EFFECT_TYPES = Set.of(
        "output", "tool", "http", "bulk_email", "crm_lead", "device_control", "media");

    private TrustGraphMaterializer() {
    }

    /**
     * @param workflowRef the workflow's brief ref (w1) - trust paths are {@code w1.<nodeId>}
     */
    public static WorkflowGraphDto materialise(
        WorkflowGraphDto graph, String workflowRef, TrustRung rung, Brief.Trust trust
    ) {
        WorkflowGraphDto base = strip(graph);
        Set<String> irreversible = idsFor(workflowRef, trust == null ? List.of() : trust.irreversible());
        Set<String> humanMustDecide = idsFor(workflowRef, trust == null ? List.of() : trust.humanMustDecide());

        Set<String> approvalTargets = new LinkedHashSet<>();
        Set<String> humanTargets = new LinkedHashSet<>();
        for (WorkflowNodeDto node : base.nodes()) {
            String type = typeOf(node);
            switch (rung) {
                case UNDER_WATCH -> {
                    if (SIDE_EFFECT_TYPES.contains(type) || humanMustDecide.contains(node.id())) {
                        approvalTargets.add(node.id());
                    }
                }
                case TRUSTED -> {
                    if (irreversible.contains(node.id()) || humanMustDecide.contains(node.id())) {
                        approvalTargets.add(node.id());
                    }
                }
                case AUTONOMOUS -> {
                    if (humanMustDecide.contains(node.id())) {
                        humanTargets.add(node.id());
                    }
                }
            }
        }
        if (approvalTargets.isEmpty() && humanTargets.isEmpty()) {
            return base;
        }

        List<WorkflowNodeDto> nodes = new ArrayList<>(base.nodes());
        List<WorkflowEdgeDto> edges = new ArrayList<>(base.edges());
        for (WorkflowNodeDto target : base.nodes()) {
            boolean approve = approvalTargets.contains(target.id());
            boolean human = humanTargets.contains(target.id());
            if (!approve && !human) {
                continue;
            }
            List<WorkflowEdgeDto> incoming = edges.stream().filter(e -> target.id().equals(e.target())).toList();
            if (incoming.isEmpty()) {
                continue;
            }
            String gateId = (approve ? APPROVE_PREFIX : HUMAN_PREFIX) + target.id();
            nodes.add(gateNode(gateId, approve, target, rung));
            for (WorkflowEdgeDto in : incoming) {
                edges.remove(in);
                edges.add(new WorkflowEdgeDto(EDGE_IN_PREFIX + in.id(), in.source(), gateId, in.sourceHandle(), null));
            }
            edges.add(new WorkflowEdgeDto(EDGE_OUT_PREFIX + target.id(), gateId, target.id(), null, null));
        }
        return new WorkflowGraphDto(nodes, edges);
    }

    /** The graph with every materialised gate removed and its edges reconnected (the Brief graph again). */
    public static WorkflowGraphDto strip(WorkflowGraphDto graph) {
        Set<String> gateIds = new LinkedHashSet<>();
        for (WorkflowNodeDto n : graph.nodes()) {
            if (isMaterialised(n)) {
                gateIds.add(n.id());
            }
        }
        if (gateIds.isEmpty()) {
            return graph;
        }
        List<WorkflowEdgeDto> edges = new ArrayList<>(graph.edges());
        for (String gateId : gateIds) {
            List<WorkflowEdgeDto> ins = edges.stream().filter(e -> gateId.equals(e.target())).toList();
            List<WorkflowEdgeDto> outs = edges.stream().filter(e -> gateId.equals(e.source())).toList();
            edges.removeAll(ins);
            edges.removeAll(outs);
            for (WorkflowEdgeDto in : ins) {
                String originalId = in.id() != null && in.id().startsWith(EDGE_IN_PREFIX)
                    ? in.id().substring(EDGE_IN_PREFIX.length()) : in.id();
                for (WorkflowEdgeDto out : outs) {
                    edges.add(new WorkflowEdgeDto(originalId, in.source(), out.target(), in.sourceHandle(), out.targetHandle()));
                }
            }
        }
        List<WorkflowNodeDto> nodes = graph.nodes().stream().filter(n -> !gateIds.contains(n.id())).toList();
        return new WorkflowGraphDto(nodes, edges);
    }

    /** Number of materialised APPROVAL nodes (the ones that need the tenant policy to pause a run). */
    public static int approvalCount(WorkflowGraphDto graph) {
        return (int) graph.nodes().stream()
            .filter(n -> isMaterialised(n) && APPROVAL_TYPE.equals(typeOf(n))).count();
    }

    public static int humanInputCount(WorkflowGraphDto graph) {
        return (int) graph.nodes().stream()
            .filter(n -> isMaterialised(n) && HUMAN_INPUT_TYPE.equals(typeOf(n))).count();
    }

    public static boolean isMaterialised(WorkflowNodeDto node) {
        return node.data() != null && Boolean.TRUE.equals(node.data().get(MARK));
    }

    // ------------------------------------------------------------------ internals

    private static WorkflowNodeDto gateNode(String id, boolean approval, WorkflowNodeDto target, TrustRung rung) {
        Map<String, Object> config = new LinkedHashMap<>();
        if (approval) {
            config.put("message", "Earned autonomy (" + rung.name() + "): a person confirms before '"
                + target.label() + "' runs.");
        } else {
            config.put("message", "This step ('" + target.label() + "') always needs a person's decision.");
            config.put("fields", List.of());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", (approval ? "Approve: " : "Decide: ") + target.label());
        data.put("config", config);
        data.put(MARK, true);
        return new WorkflowNodeDto(id, approval ? APPROVAL_TYPE : HUMAN_INPUT_TYPE, shifted(target.position()), data);
    }

    private static Map<String, Object> shifted(Map<String, Object> position) {
        Map<String, Object> out = new LinkedHashMap<>();
        double x = 0;
        double y = 0;
        if (position != null) {
            x = number(position.get("x"));
            y = number(position.get("y"));
        }
        out.put("x", x - 180);
        out.put("y", y);
        return out;
    }

    private static double number(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static Set<String> idsFor(String workflowRef, List<String> paths) {
        Set<String> ids = new LinkedHashSet<>();
        if (workflowRef == null) {
            return ids;
        }
        String prefix = workflowRef + ".";
        for (String p : paths) {
            if (p != null && p.startsWith(prefix)) {
                ids.add(p.substring(prefix.length()));
            }
        }
        return ids;
    }

    private static String typeOf(WorkflowNodeDto node) {
        return node.type() == null ? "" : node.type().toLowerCase(Locale.ROOT);
    }
}
