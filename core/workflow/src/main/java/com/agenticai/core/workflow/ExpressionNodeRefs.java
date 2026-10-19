package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Graph and template helpers of the expression linter ({@link WorkflowExpressionService#lint}): the {@code $node}
 * references a template makes, with their offsets, and which nodes are upstream of a node.
 */
final class ExpressionNodeRefs {

    private static final Pattern LABEL_BRACKET =
        Pattern.compile("\\$node\\s*\\[\\s*([\"'])((?:(?!\\1).)*)\\1\\s*]");
    private static final Pattern ID_CALL =
        Pattern.compile("\\$node\\s*\\.\\s*id\\s*\\(\\s*([\"'])((?:(?!\\1).)*)\\1\\s*\\)");
    private static final Pattern LABEL_DOT =
        Pattern.compile("\\$node\\s*\\.\\s*(?!id\\s*\\()([A-Za-z_][A-Za-z0-9_]*)");

    private ExpressionNodeRefs() {
    }

    enum RefKind { LABEL, ID }

    record NodeRef(RefKind kind, String name, int start, int end) {
    }

    /** {@code $node} references inside {@code {{ }}} spans, with their offsets in the template. */
    static List<NodeRef> scanNodeRefs(String template) {
        List<NodeRef> refs = new ArrayList<>();
        int pos = 0;
        while (true) {
            int open = template.indexOf("{{", pos);
            if (open < 0) {
                break;
            }
            int close = template.indexOf("}}", open + 2);
            if (close < 0) {
                break;
            }
            String span = template.substring(open + 2, close);
            collect(LABEL_BRACKET.matcher(span), 2, RefKind.LABEL, open + 2, refs);
            collect(ID_CALL.matcher(span), 2, RefKind.ID, open + 2, refs);
            collect(LABEL_DOT.matcher(span), 1, RefKind.LABEL, open + 2, refs);
            pos = close + 2;
        }
        refs.sort((a, b) -> Integer.compare(a.start, b.start));
        return refs;
    }

    private static void collect(Matcher m, int group, RefKind kind, int base, List<NodeRef> out) {
        while (m.find()) {
            out.add(new NodeRef(kind, m.group(group), base + m.start(), base + m.end()));
        }
    }

    /** target node id -> source node ids, in edge order. */
    static Map<String, List<String>> incomingEdges(WorkflowGraphDto graph) {
        Map<String, List<String>> incoming = new HashMap<>();
        for (WorkflowEdgeDto e : graph.edges()) {
            incoming.computeIfAbsent(e.target(), k -> new ArrayList<>()).add(e.source());
        }
        return incoming;
    }

    static Set<String> ancestorsOf(String nodeId, Map<String, List<String>> incoming) {
        return new HashSet<>(orderedAncestors(nodeId, incoming));
    }

    /** Every node that can reach {@code nodeId}, nearest first (breadth-first over reversed edges). */
    static List<String> orderedAncestors(String nodeId, Map<String, List<String>> incoming) {
        List<String> order = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(nodeId);
        Deque<String> queue = new ArrayDeque<>();
        queue.add(nodeId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String parent : incoming.getOrDefault(current, List.of())) {
                if (seen.add(parent)) {
                    order.add(parent);
                    queue.add(parent);
                }
            }
        }
        return order;
    }
}
