package com.agenticai.core.workflow.portability;

import com.agenticai.core.agents.importer.AgentImportText;
import com.agenticai.core.workflow.portability.N8nImportModel.Built;
import com.agenticai.core.workflow.portability.N8nImportModel.Conn;
import com.agenticai.core.workflow.portability.N8nImportModel.Outputs;
import com.agenticai.core.workflow.portability.N8nImportModel.Src;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns n8n's {@code main} connections into edges for {@link N8nImportMapper}: output indexes
 * become source handles, and a Split In Batches loop body loses its back-connection (a graph cannot
 * hold a cycle here).
 */
final class N8nEdgeBuilder {

    private final PortableWorkflow wf;
    private final Map<String, Src> byName;
    private final Map<String, Built> built;
    private int edgeSeq;

    N8nEdgeBuilder(PortableWorkflow wf, Map<String, Src> byName, Map<String, Built> built) {
        this.wf = wf;
        this.byName = byName;
        this.built = built;
    }

    void build(List<Conn> main) {
        Map<String, List<Conn>> out = new LinkedHashMap<>();
        for (Conn c : main) {
            out.computeIfAbsent(c.from(), k -> new ArrayList<>()).add(c);
        }
        Map<String, Integer> loopOutput = new HashMap<>();
        Map<String, Set<String>> loopBody = new HashMap<>();
        for (Map.Entry<String, Built> entry : built.entrySet()) {
            if (entry.getValue().outputs == Outputs.LOOP) {
                int loopIdx = loopOutputIndex(entry.getKey(), byName.get(entry.getKey()), out);
                loopOutput.put(entry.getKey(), loopIdx);
                loopBody.put(entry.getKey(), reach(entry.getKey(), loopIdx, out));
            }
        }

        Set<String> seen = new HashSet<>();
        Set<String> loopFirst = new HashSet<>();
        Map<String, Set<String>> backSources = new LinkedHashMap<>();
        Map<String, List<String>> doneTargets = new LinkedHashMap<>();
        Map<String, Integer> perHandle = new LinkedHashMap<>();
        for (Conn c : main) {
            Built from = built.get(c.from());
            Built to = built.get(c.to());
            if (from == null || to == null) {
                wf.fidelity.connectionsDropped++;
                wf.report.warn(
                        "A connection from '"
                                + AgentImportText.inline(c.from(), 50)
                                + "' to '"
                                + AgentImportText.inline(c.to(), 50)
                                + "' was not imported because one end is not a step here.");
                continue;
            }
            if (loopOutput.containsKey(c.from())) {
                if (c.outputIndex() == loopOutput.get(c.from())) {
                    if (loopFirst.add(c.from())) {
                        addEdge(from, null, to, seen, perHandle);
                    } else {
                        wf.fidelity.connectionsDropped++;
                        wf.report.warn(
                                "Loop '"
                                        + AgentImportText.inline(c.from(), 50)
                                        + "' feeds more than one node; "
                                        + "this platform repeats a single node per item, so the connection to '"
                                        + AgentImportText.inline(c.to(), 50)
                                        + "' was dropped.");
                    }
                } else {
                    doneTargets.computeIfAbsent(c.from(), k -> new ArrayList<>()).add(c.to());
                }
                continue;
            }
            if (loopBody.containsKey(c.to()) && loopBody.get(c.to()).contains(c.from())) {
                backSources.computeIfAbsent(c.to(), k -> new LinkedHashSet<>()).add(c.from());
                wf.fidelity.connectionsDropped++;
                continue;
            }
            addEdge(from, handleFor(from, c), to, seen, perHandle);
        }
        for (Map.Entry<String, List<String>> done : doneTargets.entrySet()) {
            Set<String> backs = backSources.get(done.getKey());
            for (String target : done.getValue()) {
                Built from =
                        backs != null && backs.size() == 1
                                ? built.get(backs.iterator().next())
                                : null;
                Built to = built.get(target);
                if (from != null && to != null) {
                    addEdge(from, null, to, seen, perHandle);
                } else {
                    wf.fidelity.connectionsDropped++;
                    wf.report.warn(
                            "The 'done' output of loop '"
                                    + AgentImportText.inline(done.getKey(), 50)
                                    + "' to '"
                                    + AgentImportText.inline(target, 50)
                                    + "' could not be reattached after the loop "
                                    + "body; connect it by hand.");
                }
            }
        }
        for (Map.Entry<String, Set<String>> back : backSources.entrySet()) {
            wf.report.unsupported(
                    "Loop-back connection into '" + AgentImportText.inline(back.getKey(), 60) + "'",
                    "A graph cannot contain a cycle here; the loop is expressed by the Loop node repeating its next node "
                            + "per item, so the connection back to the loop was removed.");
        }
        for (Map.Entry<String, Integer> handle : perHandle.entrySet()) {
            if (handle.getValue() > 1) {
                String source = handle.getKey().split("\u0000", -1)[0];
                wf.report.warn(
                        "Node '"
                                + AgentImportText.inline(source, 60)
                                + "' connects to "
                                + handle.getValue()
                                + " nodes on one output. This engine follows only the first non-error connection of an output; "
                                + "add a Parallel node to run them together.");
            }
        }
    }

    private void addEdge(
            Built from, String handle, Built to, Set<String> seen, Map<String, Integer> perHandle) {
        String key = from.node.id() + "\u0000" + handle + "\u0000" + to.node.id();
        if (!seen.add(key)) {
            return;
        }
        wf.edges.add(PortableNodes.edge("e" + (++edgeSeq), from.node.id(), handle, to.node.id()));
        wf.fidelity.connectionsKept++;
        perHandle.merge(from.node.label() + "\u0000" + handle, 1, Integer::sum);
    }

    private String handleFor(Built from, Conn c) {
        return switch (from.outputs) {
            case CONDITION ->
                    c.outputIndex() == 0
                            ? "true"
                            : c.outputIndex() == 1 ? "false" : "output_" + c.outputIndex();
            case INDEXED -> "output_" + c.outputIndex();
            default -> null;
        };
    }

    /**
     * Which output of a Split In Batches node is the loop body: the one whose path leads back to
     * the node.
     */
    private int loopOutputIndex(String name, Src s, Map<String, List<Conn>> out) {
        int found = -1;
        int matches = 0;
        for (int idx = 0; idx <= 1; idx++) {
            if (reach(name, idx, out).contains(name)) {
                found = idx;
                matches++;
            }
        }
        if (matches == 1) {
            return found;
        }
        // n8n's Loop Over Items (typeVersion 3+) has outputs [done, loop]; earlier versions the
        // other way round.
        return s.version() >= 3 ? 1 : 0;
    }

    /**
     * Names reachable from {@code start}'s output {@code outputIndex} (the start node itself is
     * reported, never expanded).
     */
    private static Set<String> reach(String start, int outputIndex, Map<String, List<Conn>> out) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (Conn c : out.getOrDefault(start, List.of())) {
            if (c.outputIndex() == outputIndex && seen.add(c.to())) {
                queue.add(c.to());
            }
        }
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(start)) {
                continue;
            }
            for (Conn c : out.getOrDefault(current, List.of())) {
                if (seen.add(c.to())) {
                    queue.add(c.to());
                }
            }
        }
        return seen;
    }
}
