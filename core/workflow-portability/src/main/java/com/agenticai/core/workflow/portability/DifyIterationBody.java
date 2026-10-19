package com.agenticai.core.workflow.portability;

import com.agenticai.core.agents.importer.AgentImportText;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Accounting for the body of a Dify iteration that could NOT be mapped (a nested iteration, or one
 * with nothing to repeat). Those nodes are excluded from the top-level graph, so without this they
 * would vanish from the report and from the fidelity counts. Each is counted as skipped and listed
 * as unsupported, up to {@link #MAX_LISTED} entries followed by one summary line, so a huge body
 * cannot flood the report.
 */
final class DifyIterationBody {

    static final int MAX_LISTED = 20;

    private DifyIterationBody() {}

    static void reportUnmapped(
            PortableWorkflow wf,
            DifyImportMapper.DNode iteration,
            Map<String, List<DifyImportMapper.DNode>> children,
            List<JsonNode> edges) {
        List<DifyImportMapper.DNode> queue =
                new ArrayList<>(children.getOrDefault(iteration.id(), List.of()));
        Set<String> ids = new HashSet<>();
        String where = " inside iteration '" + AgentImportText.inline(iteration.title(), 60) + "'";
        int counted = 0;
        for (int i = 0; i < queue.size(); i++) {
            DifyImportMapper.DNode n = queue.get(i);
            if (!ids.add(n.id())) {
                continue;
            }
            queue.addAll(children.getOrDefault(n.id(), List.of()));
            if ("iteration-start".equals(n.type()) || "loop-start".equals(n.type())) {
                continue;
            }
            wf.fidelity.sourceNodes++;
            wf.fidelity.skipped++;
            if (++counted <= MAX_LISTED) {
                wf.report.unsupported(
                        "Node '"
                                + AgentImportText.inline(n.title(), 80)
                                + "' ("
                                + n.type()
                                + ")"
                                + where,
                        "The iteration could not be imported, so the nodes inside it were not imported either.");
            }
        }
        if (counted > MAX_LISTED) {
            wf.report.unsupported(
                    (counted - MAX_LISTED) + " more nodes" + where,
                    "The iteration could not be imported, so the nodes inside it were not imported either.");
        }
        for (JsonNode edge : edges) {
            String source = DifyImportMapper.idText(edge, "source");
            String target = DifyImportMapper.idText(edge, "target");
            if (source != null && target != null && ids.contains(source) && ids.contains(target)) {
                wf.fidelity.connectionsDropped++;
            }
        }
    }
}
